package com.softberries.doubleentry.locking

import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.balance.BalanceIntegrationTest
import com.softberries.doubleentry.balance.BalanceTestConfiguration
import com.softberries.doubleentry.balance.cache.AccountBalanceRepository
import com.softberries.doubleentry.config.DoubleEntryTables
import com.softberries.doubleentry.locking.errors.LockDisaster
import com.softberries.doubleentry.locking.errors.LockMustBeOutermostTransaction
import com.softberries.doubleentry.locking.errors.LockNotHeld
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.parity.DbSnapshot
import com.softberries.doubleentry.parity.ExceptionComparator
import com.softberries.doubleentry.parity.OracleScenario
import com.softberries.doubleentry.parity.OracleScenarios
import com.softberries.doubleentry.parity.RowComparator
import com.softberries.doubleentry.parity.SqlCapture
import com.softberries.doubleentry.parity.SqlProtocolStep
import com.softberries.doubleentry.parity.SqlShape
import com.softberries.doubleentry.testing.TestUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * M-011 on PostgreSQL 17: the `lockAccounts` protocol (BR-031, BR-060..066, BR-071, BR-073, BR-074).
 *
 * Ports `spec/double_entry/locking_spec.rb` [1:1], [1:2], [1:3], [1:7], [1:8], [1:9], [1:10], [1:11]. Transfers do
 * not exist yet (M-012), so where a Ruby example or oracle scenario transfers inside the lock, the block writes a
 * line and updates the locked balance rows with plain SQL in the lock transaction instead; SQL comparisons then cover
 * the locking statements only ([lockProtocol]).
 *
 * Oracle: `lock_inside_transaction`, `lock_order_sql`, `lock_creates_missing_balances`,
 * `lock_balance_from_lines_after_flush`, `lock_nested_subset_ok`, `lock_nested_superset`, `lock_wrong_locks`,
 * `lock_balance_for_locked_account_outside_lock`, `lock_exception_rollback`, `lock_rollback`,
 * `lock_returns_block_value` (deviation X-05), `lock_empty`, `transfer_inside_transaction_with_fixtures_flag` and
 * `transfer_inside_two_transactions_with_fixtures_flag` (lock-level equivalents).
 */
@Import(BalanceTestConfiguration::class, LockingTestConfiguration::class)
class LockingIntegrationTest : BalanceIntegrationTest() {
    @Autowired
    private lateinit var locking: Locking

    @Autowired
    private lateinit var repository: AccountBalanceRepository

    @Autowired
    private lateinit var creator: BalanceRowCreator

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var restartable: RestartableTransaction

    private val bank get() = account("bank")
    private val cash get() = account("cash")
    private val store get() = account("store")
    private val user1 = TestUser(1)
    private val checking1 get() = resolver.account("checking", scope = user1)

    private fun usd(minor: Long) = Money.ofMinor(minor, bank.currency)

    private fun balanceRows(): List<Map<String, Any?>> =
        jdbcTemplate.queryForList("SELECT account, scope, balance FROM double_entry_account_balances ORDER BY id")

    private fun lineCount(): Long = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM double_entry_lines", Long::class.java)!!

    private fun createRow(
        account: AccountInstance,
        balance: Long = 0,
    ) = creator.createIgnoringDuplicates(account, Money.ofMinor(balance, account.currency)).shouldNotBeNull()

    /** Stands in for a transfer of [amount] from [from] to [to] (M-012): a line plus both cached balances. */
    private fun writeInsideLock(
        from: AccountInstance,
        to: AccountInstance,
        amount: Long,
    ) {
        jdbcTemplate.update(
            "INSERT INTO double_entry_lines (account, code, amount, balance, partner_account, created_at, updated_at) " +
                "VALUES (?, 'salary', ?, ?, ?, now(), now())",
            from.identifier,
            -amount,
            -amount,
            to.identifier,
        )
        jdbcTemplate.update("UPDATE double_entry_account_balances SET balance = balance - ? WHERE account = ?", amount, from.identifier)
        jdbcTemplate.update("UPDATE double_entry_account_balances SET balance = balance + ? WHERE account = ?", amount, to.identifier)
    }

    /**
     * The locking part of the protocol (D-017): transaction control, balance-row selects and inserts, and the line
     * selects of the balance calculation. Writes made by the block (transfers in Ruby, plain SQL here) are dropped.
     */
    private fun lockProtocol(steps: List<SqlProtocolStep>): List<SqlProtocolStep> =
        steps.filter {
            it.verb in SqlShape.TRANSACTION_VERBS ||
                (it.table == DoubleEntryTables.ACCOUNT_BALANCES && it.verb in setOf("SELECT", "INSERT")) ||
                (it.table == DoubleEntryTables.LINES && it.verb == "SELECT")
        }

    private fun rubyLockProtocol(
        scenario: OracleScenario,
        fromLine: Int = 0,
    ) = lockProtocol(SqlCapture.protocol(SqlCapture.fromRubyLog(scenario.sql.drop(fromLine))))

    private fun assertDbMatches(scenario: OracleScenario) =
        RowComparator.compare(scenario.dbAfter, DbSnapshot.read(jdbcTemplate.dataSource!!)).assertMatches(scenario.id)

    // --- BR-060 outermost transaction ------------------------------------------------------------------------

    @Test
    fun `LK 1-3 lockAccounts inside a transaction raises LockMustBeOutermostTransaction (lock_inside_transaction)`() {
        val oracle = OracleScenarios.load("lock_inside_transaction")

        val thrown =
            shouldThrow<LockMustBeOutermostTransaction> {
                TransactionTemplate(transactionManager).execute { locking.lockAccounts(bank) { "x" } }
            }

        ExceptionComparator.compare(oracle.exception!!, thrown).assertMatches(oracle.id)
        assertDbMatches(oracle)
    }

    @Test
    fun `with the fixtures flag one enclosing transaction is allowed (transfer_inside_transaction_with_fixtures_flag)`() {
        val oracle = OracleScenarios.load("transfer_inside_transaction_with_fixtures_flag")
        val fixtures = locking.copy(settings = LockingSettings(runningInsideTransactionalFixtures = true))

        val result =
            TransactionTemplate(transactionManager).execute {
                fixtures.lockAccounts(bank, cash) {
                    writeInsideLock(bank, cash, 1)
                    "ran"
                }
            }

        result shouldBe "ran"
        balanceRows() shouldContainExactly
            listOf(
                mapOf("account" to "bank", "scope" to null, "balance" to -1L),
                mapOf("account" to "cash", "scope" to null, "balance" to 1L),
            )
        oracle.exception.shouldBeNull()
    }

    @Test
    fun `with the fixtures flag outer plus NESTED savepoint raises (transfer_inside_two_transactions_with_fixtures_flag)`() {
        val oracle = OracleScenarios.load("transfer_inside_two_transactions_with_fixtures_flag")
        // Hibernate's JpaDialect has no savepoints, so the NESTED level runs on a JDBC transaction manager carrying
        // its own depth tracker; the check fails before the lock transaction would start.
        val jdbcManager = DataSourceTransactionManager(jdbcTemplate.dataSource!!)
        val tracker = TransactionDepthTracker().apply { attachTo(jdbcManager) shouldBe true }
        val fixtures =
            Locking(restartable, repository, creator, calculator, LockingSettings(runningInsideTransactionalFixtures = true), tracker)
        val nested = TransactionTemplate(jdbcManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_NESTED }

        val thrown =
            shouldThrow<LockMustBeOutermostTransaction> {
                TransactionTemplate(jdbcManager).execute {
                    tracker.depth() shouldBe 1
                    nested.execute { status ->
                        status.hasSavepoint() shouldBe true
                        tracker.depth() shouldBe 2
                        fixtures.lockAccounts(bank, cash) { "never" }
                    }
                }
            }

        ExceptionComparator.compare(oracle.exception!!, thrown).assertMatches(oracle.id)
        tracker.depth() shouldBe 0
        assertDbMatches(oracle)
    }

    @Test
    fun `with the fixtures flag an outer transaction plus REQUIRES_NEW raises too`() {
        val fixtures = locking.copy(settings = LockingSettings(runningInsideTransactionalFixtures = true))
        val requiresNew =
            TransactionTemplate(transactionManager).apply {
                propagationBehavior =
                    TransactionDefinition.PROPAGATION_REQUIRES_NEW
            }

        shouldThrow<LockMustBeOutermostTransaction> {
            TransactionTemplate(transactionManager).execute { requiresNew.execute { fixtures.lockAccounts(bank) { "never" } } }
        }
    }

    @Test
    fun `with the fixtures flag a participating REQUIRED transaction adds no level`() {
        val fixtures = locking.copy(settings = LockingSettings(runningInsideTransactionalFixtures = true))

        val result =
            TransactionTemplate(transactionManager).execute {
                TransactionTemplate(transactionManager).execute { fixtures.lockAccounts(bank) { "ran" } }
            }

        result shouldBe "ran"
    }

    @Test
    fun `without the fixtures flag a participating transaction is still one too many`() {
        shouldThrow<LockMustBeOutermostTransaction> {
            TransactionTemplate(transactionManager).execute {
                TransactionTemplate(transactionManager).execute { locking.lockAccounts(bank) { "never" } }
            }
        }
        balanceRows().shouldBeEmpty()
    }

    // --- BR-061 lock order -----------------------------------------------------------------------------------

    @Test
    fun `LK 1-11 accounts are locked FOR UPDATE in scope-then-identifier order (lock_order_sql)`() {
        val oracle = OracleScenarios.load("lock_order_sql")

        locking.lockAccounts(store, checking1, bank, cash) { "ok" }
        locking.lockAccounts(store, checking1, bank, cash) { "ok" }

        val steps = sqlCapture.protocolSteps
        val locks = sqlCapture.shapes.filter { it.table == DoubleEntryTables.ACCOUNT_BALANCES && it.forUpdate }
        locks.take(4).map { it.whereColumns } shouldContainExactly
            List(3) { listOf("scope IS NULL", "account") } + listOf(listOf("scope", "account"))
        lockProtocol(steps) shouldContainExactly rubyLockProtocol(oracle)
        assertDbMatches(oracle)
    }

    // --- BR-062 missing balance rows -------------------------------------------------------------------------

    @Test
    fun `LK 1-1 missing balance rows are created with the line balance, then locked (lock_creates_missing_balances)`() {
        val oracle = OracleScenarios.load("lock_creates_missing_balances")

        val result = locking.lockAccounts(checking1, bank) { ctx -> locking.balanceForLockedAccount(checking1).id to ctx.isRollbackOnly }

        val steps = sqlCapture.protocolSteps
        result shouldBe (2L to false)
        assertDbMatches(oracle)
        lockProtocol(steps) shouldContainExactly rubyLockProtocol(oracle)
        // One INSERT per missing account, each in its own transaction, before the second lock transaction.
        val inserts = steps.filter { it.verb == "INSERT" }
        inserts.map { it.table } shouldContainExactly List(2) { DoubleEntryTables.ACCOUNT_BALANCES }
    }

    @Test
    fun `LK 1-1 a scoped account gets one balance row of zero`() {
        locking.lockAccounts(checking1) {}

        balanceRows() shouldContainExactly listOf(mapOf("account" to "checking", "scope" to "1", "balance" to 0L))
        repository.findByAccount(checking1).shouldNotBeNull().balance(resolver) shouldBe usd(0)
    }

    @Test
    fun `LK 1-2 the balance of a new row is taken from the latest line`() {
        val now = clock.instant()
        fixtures.line("checking", "1", "test", 300, 300, now)
        fixtures.line("checking", "1", "test", 700, 1000, now)

        locking.lockAccounts(checking1) {}

        balanceRows() shouldContainExactly listOf(mapOf("account" to "checking", "scope" to "1", "balance" to 1000L))
    }

    @Test
    fun `with lines but the cache flushed the row is recreated with 15_00 (lock_balance_from_lines_after_flush)`() {
        val oracle = OracleScenarios.load("lock_balance_from_lines_after_flush")
        fixtures.load(oracle.id)
        jdbcTemplate.update("DELETE FROM double_entry_account_balances")
        // In the Ruby run the two transfers had used balance ids 1 and 2 before the flush.
        jdbcTemplate.execute("SELECT setval(pg_get_serial_sequence('double_entry_account_balances', 'id'), 3, false)")
        sqlCapture.clear()

        locking.lockAccounts(cash) { "ok" }
        val cached = repository.findByAccount(cash).shouldNotBeNull().balance(resolver)
        val balance = calculator.calculate(cash)

        val steps = sqlCapture.protocolSteps
        cached shouldBe usd(1500)
        balance shouldBe usd(1500)
        cached.toString() shouldBe oracle.result!!["cash_cached"]["money"]["to_s"].asText()
        balance.toString() shouldBe oracle.result!!["cash_balance"]["money"]["to_s"].asText()
        assertDbMatches(oracle)
        val afterFlush = oracle.sql.indexOfFirst { "Delete All" in it } + 1
        lockProtocol(steps) shouldContainExactly rubyLockProtocol(oracle, fromLine = afterFlush)
    }

    @Test
    fun `a row still missing after the single retry raises LockDisaster (fault injection, U-016)`() {
        val faulty = spyk(creator)
        every { faulty.createIgnoringDuplicates(any(), any()) } answers {
            val row = callOriginal()
            // Another writer deletes the row between its creation and the relock.
            jdbcTemplate.update("DELETE FROM double_entry_account_balances")
            row
        }

        val thrown = shouldThrow<LockDisaster> { faulty.let { locking.copy(rowCreator = it) }.lockAccounts(bank) { "never" } }

        thrown.message shouldBe "DoubleEntry::Locking::LockDisaster"
        verify(exactly = 1) { faulty.createIgnoringDuplicates(any(), any()) }
        sqlCapture.shapes.count { it.table == DoubleEntryTables.ACCOUNT_BALANCES && it.forUpdate } shouldBe 2
    }

    // --- BR-063 nested locks ---------------------------------------------------------------------------------

    @Test
    fun `LK 1-7 a nested lock on a subset of the outer accounts runs in the outer transaction (lock_nested_subset_ok)`() {
        val oracle = OracleScenarios.load("lock_nested_subset_ok")

        val result =
            locking.lockAccounts(bank, cash) { outer ->
                locking.lockAccounts(cash) { inner ->
                    inner shouldBeSameInstanceAs outer
                    "inner"
                }
            }

        val steps = sqlCapture.protocolSteps
        result shouldBe "inner"
        assertDbMatches(oracle)
        lockProtocol(steps) shouldContainExactly rubyLockProtocol(oracle)
    }

    @Test
    fun `LK 1-7 a nested lock on the same accounts is allowed`() {
        locking.lockAccounts(checking1, account("checking", "2")) {
            locking.lockAccounts(checking1, account("checking", "2")) { "inner" }
        } shouldBe "inner"
    }

    @Test
    fun `LK 1-8 a nested lock on an account the outer lock does not hold raises LockNotHeld (lock_nested_superset)`() {
        val oracle = OracleScenarios.load("lock_nested_superset")

        val thrown = shouldThrow<LockNotHeld> { locking.lockAccounts(bank) { locking.lockAccounts(bank, cash) { "inner" } } }

        val steps = sqlCapture.protocolSteps
        thrown.message shouldBe "No lock held for account: cash, scope "
        ExceptionComparator.compare(oracle.exception!!, thrown).assertMatches(oracle.id)
        assertDbMatches(oracle)
        lockProtocol(steps) shouldContainExactly rubyLockProtocol(oracle)
    }

    @Test
    fun `LK 1-8 the message names the scope object of a scoped account (lock_wrong_locks)`() {
        val oracle = OracleScenarios.load("lock_wrong_locks")

        val thrown = shouldThrow<LockNotHeld> { locking.lockAccounts(bank) { locking.lockAccounts(bank, checking1) { "inner" } } }

        thrown.message shouldBe "No lock held for account: checking, scope TestUser(id=1)"
        ExceptionComparator.compare(oracle.exception!!, thrown, scope = user1).assertMatches(oracle.id)
        assertDbMatches(oracle)
    }

    @Test
    fun `an account built from a scope identity has a blank scope in the message`() {
        val thrown =
            shouldThrow<LockNotHeld> { locking.lockAccounts(checking1) { locking.lockAccounts(account("checking", "2")) {} } }

        thrown.message shouldBe "No lock held for account: checking, scope "
    }

    // --- BR-073 balanceForLockedAccount ----------------------------------------------------------------------

    @Test
    fun `balanceForLockedAccount outside a lock raises LockNotHeld (lock_balance_for_locked_account_outside_lock)`() {
        val oracle = OracleScenarios.load("lock_balance_for_locked_account_outside_lock")

        val thrown = shouldThrow<LockNotHeld> { locking.balanceForLockedAccount(bank) }

        thrown.message shouldBe "No lock held for account: bank, scope "
        ExceptionComparator.compare(oracle.exception!!, thrown).assertMatches(oracle.id)
        assertDbMatches(oracle)
    }

    @Test
    fun `balanceForLockedAccount inside a lock returns the locked row, and raises for an account not locked`() {
        createRow(bank, 250)

        locking.lockAccounts(bank) {
            val row = locking.balanceForLockedAccount(bank)
            row.accountIdentifier shouldBe "bank"
            row.balanceMinorUnits shouldBe 250L
            shouldThrow<LockNotHeld> { locking.balanceForLockedAccount(cash) }.message shouldBe "No lock held for account: cash, scope "
        }
    }

    // --- BR-064, BR-065, BR-031 writes, rollback ---------------------------------------------------------------

    @Test
    fun `BR-064 writes inside one lock commit together`() {
        locking.lockAccounts(bank, cash) {
            writeInsideLock(bank, cash, 100)
            writeInsideLock(bank, cash, 50)
        }

        lineCount() shouldBe 2
        balanceRows().map { it["balance"] } shouldContainExactly listOf(-150L, 150L)
    }

    @Test
    fun `LK 1-10 an exception rolls back the writes and propagates, created balance rows stay (lock_exception_rollback)`() {
        val oracle = OracleScenarios.load("lock_exception_rollback")
        val boom = RuntimeException("boom")

        val thrown =
            shouldThrow<RuntimeException> {
                locking.lockAccounts(bank, cash) {
                    writeInsideLock(bank, cash, 100)
                    throw boom
                }
            }

        val steps = sqlCapture.protocolSteps
        thrown shouldBeSameInstanceAs boom
        ExceptionComparator.compare(oracle.exception!!, thrown).assertMatches(oracle.id)
        lineCount() shouldBe 0
        // BR-031: the rows created before the block ran were committed in their own transactions.
        balanceRows().map { it["account"] to it["balance"] } shouldContainExactly listOf("bank" to 0L, "cash" to 0L)
        assertDbMatches(oracle)
        lockProtocol(steps) shouldContainExactly rubyLockProtocol(oracle)
        steps.last().verb shouldBe "ROLLBACK"
    }

    @Test
    fun `LK 1-9 ctx rollback rolls back silently and returns the block value (lock_rollback, U-009)`() {
        val oracle = OracleScenarios.load("lock_rollback")

        val result =
            locking.lockAccounts(bank, cash) { ctx ->
                writeInsideLock(bank, cash, 100)
                ctx.rollback()
                ctx.isRollbackOnly shouldBe true
                "rolled back"
            }

        val steps = sqlCapture.protocolSteps
        result shouldBe "rolled back"
        lineCount() shouldBe 0
        balanceRows().map { it["account"] to it["balance"] } shouldContainExactly listOf("bank" to 0L, "cash" to 0L)
        assertDbMatches(oracle)
        lockProtocol(steps) shouldContainExactly rubyLockProtocol(oracle)
        steps.last().verb shouldBe "ROLLBACK"
    }

    @Test
    fun `ctx rollback in a nested block rolls back the outermost lock transaction`() {
        createRow(bank)
        createRow(cash)

        locking.lockAccounts(bank, cash) {
            writeInsideLock(bank, cash, 100)
            locking.lockAccounts(bank) { inner -> inner.rollback() }
        }

        lineCount() shouldBe 0
        balanceRows().map { it["balance"] } shouldContainExactly listOf(0L, 0L)
    }

    // --- BR-066 return value (X-05), BR-074 empty lock ---------------------------------------------------------

    @Test
    fun `X-05 lockAccounts returns the block value at the outermost level (lock_returns_block_value)`() {
        val oracle = OracleScenarios.load("lock_returns_block_value")

        val first = locking.lockAccounts(checking1) { 42 }
        val second = locking.lockAccounts(checking1) { 42 }

        val steps = sqlCapture.protocolSteps
        first shouldBe 42
        second shouldBe 42
        // X-05: Ruby returned nil at the outermost level, whether or not the rows had to be created.
        oracle.result!!["first_call_rows_created"].isNull shouldBe true
        oracle.result!!["second_call_rows_exist"].isNull shouldBe true
        assertDbMatches(oracle)
        lockProtocol(steps) shouldContainExactly rubyLockProtocol(oracle)
    }

    @Test
    fun `BR-074 lockAccounts without accounts runs the block in a transaction (lock_empty)`() {
        val oracle = OracleScenarios.load("lock_empty")

        val result =
            locking.lockAccounts {
                TransactionSynchronizationManager.isActualTransactionActive() shouldBe true
                1
            }

        val steps = sqlCapture.protocolSteps
        result shouldBe 1
        assertDbMatches(oracle)
        lockProtocol(steps) shouldContainExactly rubyLockProtocol(oracle)
    }

    // --- BR-071 thread-local lock map --------------------------------------------------------------------------

    @Test
    fun `the lock map is cleared in finally when the block throws`() {
        shouldThrow<IllegalStateException> { locking.lockAccounts(bank) { error("fails") } }

        shouldThrow<LockNotHeld> { locking.balanceForLockedAccount(bank) }
        // A leaked map would send this call down the nested path and raise LockNotHeld for cash.
        locking.lockAccounts(cash) { "outermost again" } shouldBe "outermost again"
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `another thread does not see the lock map`() {
        val seenByOtherThread = AtomicReference<Throwable?>()

        locking.lockAccounts(bank) {
            thread { seenByOtherThread.set(runCatching { locking.balanceForLockedAccount(bank) }.exceptionOrNull()) }.join()
            locking.balanceForLockedAccount(bank).accountIdentifier shouldBe "bank"
        }

        seenByOtherThread.get().shouldBeInstanceOf<LockNotHeld>().message shouldBe "No lock held for account: bank, scope "
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `threads locking disjoint accounts proceed concurrently`() {
        createRow(bank)
        createRow(cash)
        val holding = CountDownLatch(1)
        val holderWaited = AtomicReference<Duration>()
        val holderError = AtomicReference<Throwable?>()

        val holder =
            thread {
                holderError.set(
                    runCatching {
                        val start = System.nanoTime()
                        locking.lockAccounts(bank) {
                            holderWaited.set(Duration.ofNanos(System.nanoTime() - start))
                            holding.countDown()
                            Thread.sleep(2_000)
                        }
                    }.exceptionOrNull(),
                )
            }
        holding.await(10, TimeUnit.SECONDS) shouldBe true

        val start = System.nanoTime()
        val waited = locking.lockAccounts(cash) { Duration.ofNanos(System.nanoTime() - start) }
        holder.join()

        holderError.get().shouldBeNull()
        waited.toMillis() shouldBeLessThan 1_000L
        holderWaited.get().toMillis() shouldBeLessThan 1_000L
    }
}
