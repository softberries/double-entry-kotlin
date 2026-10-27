package com.softberries.doubleentry.parity.locking

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.softberries.doubleentry.DoubleEntry
import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.balance.cache.AccountBalanceEntity
import com.softberries.doubleentry.balance.cache.AccountBalanceRepository
import com.softberries.doubleentry.concurrency.ConcurrencyIntegrationTest
import com.softberries.doubleentry.config.DoubleEntryTables
import com.softberries.doubleentry.locking.BalanceRowCreator
import com.softberries.doubleentry.locking.BalanceRowInserter
import com.softberries.doubleentry.locking.Locking
import com.softberries.doubleentry.locking.SqlStateTranslator
import com.softberries.doubleentry.locking.errors.LockWaitTimeout
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.parity.Comparison
import com.softberries.doubleentry.parity.DbSnapshot
import com.softberries.doubleentry.parity.ExceptionComparator
import com.softberries.doubleentry.parity.NotificationCapture
import com.softberries.doubleentry.parity.ObservedNotification
import com.softberries.doubleentry.parity.OracleException
import com.softberries.doubleentry.parity.OracleFixtureConfig
import com.softberries.doubleentry.parity.OracleScenario
import com.softberries.doubleentry.parity.OracleScenarios
import com.softberries.doubleentry.parity.ParityArea
import com.softberries.doubleentry.parity.ParityReport
import com.softberries.doubleentry.parity.ParityStatus
import com.softberries.doubleentry.parity.RowComparator
import com.softberries.doubleentry.parity.SqlCapture
import com.softberries.doubleentry.parity.SqlProtocolStep
import com.softberries.doubleentry.parity.SqlShape
import com.softberries.doubleentry.parity.config.KotlinOutcome
import com.softberries.doubleentry.parity.transfer.TransferLineOutcomes
import com.softberries.doubleentry.parity.transfer.TransferLineSerializer
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.io.path.readText

/**
 * Parity driver for the locking and concurrency area (M-021): replays every `lock_*` and `compat_*` scenario of
 * `oracle/api/json` against the Kotlin API and compares result or exception (class and message exactly), `db_after`
 * rows, the double_entry SQL protocol (D-017) and notifications. The threaded scenarios are compared on their final
 * state only ([Compared.FINAL_STATE]). The `oracle/concurrency/jack_hammer_*.txt` runs are replayed with the M-015
 * jack hammer at the oracle's shape, and their reconciliation output is compared line by line.
 *
 * It runs in the concurrency suite's context (M-015: the oracle's standard accounts, the jack hammer accounts, a pool
 * large enough for 20 workers, SQL capture) plus notification capture. Scenarios listed in
 * `parity/locking.deviations.json` are still run: they must match their [LockingExpectation] (from
 * `docs/decisions.md`), must differ from the oracle, and are reported as `deviation (X-nn)` with the oracle diff. The
 * report is `build/reports/parity/locking.md`.
 */
@Import(LockingParityConfiguration::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LockingParityTest : ConcurrencyIntegrationTest() {
    @Autowired
    private lateinit var doubleEntry: DoubleEntry

    @Autowired
    private lateinit var locking: Locking

    @Autowired
    private lateinit var accountBalances: AccountBalanceRepository

    @Autowired
    private lateinit var rowCreator: BalanceRowCreator

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var notifications: NotificationCapture

    @BeforeEach
    fun clearNotifications() = notifications.clear()

    /** One scenario: the Kotlin port of its `tools/oracle.rb` block, and for a deviation the Kotlin expectation. */
    private class Case(
        val kotlinExpectation: LockingExpectation? = null,
        val compared: Compared = Compared.EXACT,
        /** Makes an oracle result comparable where the two runtimes render the same fact differently. */
        val oracleResult: (JsonNode) -> JsonNode = { it },
        val run: LockingParityTest.() -> Any?,
    )

    /** `Money.new(cents, from.currency)` of `xfer` (`tools/oracle.rb:144`). */
    private fun xfer(
        minor: Long,
        from: AccountInstance,
        to: AccountInstance,
        code: String,
    ) = doubleEntry.transfer(Money.ofMinor(minor, from.currency), from, to, code)

    /**
     * `AccountBalance.create!(account:, balance: Money.new(minor))`: a plain insert in its own transaction. A database
     * error surfaces as a Spring [DataAccessException]; classify it by SQLSTATE (D-008), not by its class.
     */
    private fun createBalanceRow(
        account: AccountInstance,
        minor: Long,
    ) {
        val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
        TransactionTemplate(transactionManager).execute {
            BalanceRowInserter(entityManager).insert(AccountBalanceEntity(account, Money.ofMinor(minor, account.currency), now))
        }
    }

    /** `AccountBalance.where(account: identifier).count` */
    private fun balanceRowCount(identifier: String): Long =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM double_entry_account_balances WHERE account = ?",
            Long::class.java,
            identifier,
        )!!

    /** `Line.where(account:).order(:id).each_cons(2).all? { |x, y| x.balance + y.amount == y.balance }` */
    private fun runningBalancesConsistent(identifier: String): Boolean =
        jdbcTemplate
            .query("SELECT amount, balance FROM double_entry_lines WHERE account = ? ORDER BY id", { rs, _ ->
                rs.getLong("amount") to rs.getLong("balance")
            }, identifier)
            .zipWithNext()
            .all { (x, y) -> x.second + y.first == y.second }

    /** `8.times.map { Thread.new { 25.times { xfer(1_00, bank, cash, :salary) } } }.each(&:join)`, started together. */
    private fun concurrentSalaries(
        bank: AccountInstance,
        cash: AccountInstance,
    ) {
        val start = CyclicBarrier(CONCURRENT_THREADS)
        runInParallel(CONCURRENT_THREADS) {
            start.await(30, TimeUnit.SECONDS)
            repeat(TRANSFERS_PER_THREAD) { xfer(1_00, bank, cash, "salary") }
        }
    }

    /**
     * `SET lock_timeout` for the library's next connection. Ruby sets it on the connection the main thread uses; the
     * Kotlin library takes any pooled connection, so the setting becomes the role's default and idle pooled
     * connections are evicted, both undone afterwards.
     */
    private fun <T> withLockTimeout(
        timeout: String,
        block: () -> T,
    ): T {
        val pool = dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean
        jdbcTemplate.execute("ALTER ROLE CURRENT_USER SET lock_timeout = '$timeout'")
        pool.softEvictConnections()
        try {
            return block()
        } finally {
            jdbcTemplate.execute("ALTER ROLE CURRENT_USER RESET lock_timeout")
            pool.softEvictConnections()
        }
    }

    /** `compat_nulls_not_distinct_index` `duplicate_insert_error`, in the form of [PgError]. */
    private fun duplicateInsertError(account: AccountInstance): String =
        try {
            createBalanceRow(account, 0)
            "none"
        } catch (e: DataAccessException) {
            // The PostgreSQL message is `ERROR: <server message>` followed by a `Detail:` line.
            val pg = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<SQLException>().first()
            PgError.render(
                pg.sqlState,
                pg.message
                    ?.lineSequence()
                    ?.first()
                    ?.removePrefix("ERROR: "),
            )
        }

    @Order(1)
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarioIds")
    fun `replays the oracle scenario`(id: String) {
        val oracle = OracleScenarios.load(id)
        val case = CASES[id]
        if (case == null) {
            report.record(id, Comparison.of("scenario is not driven by LockingParityTest"))
            throw AssertionError("$id: no Kotlin driver case")
        }
        val outcome = TransferLineSerializer.outcome { case.run(this) }
        // Before the snapshot: its SELECTs go through the captured data source too.
        val actualSql = SqlCapture.withoutReadOnlyTransactions(sqlCapture.protocolSteps)
        val actualDb = DbSnapshot.read(jdbcTemplate.dataSource!!)
        val actualNotifications = notifications.notifications
        val oracleExpectation =
            LockingExpectation(
                result = oracle.result?.let(case.oracleResult),
                exception = oracle.exception,
                dbAfter = oracle.dbAfter,
                sql = SqlCapture.withoutReadOnlyTransactions(SqlCapture.protocol(SqlCapture.fromRubyLog(oracle.sql))),
                finalState = FinalState.of(oracle.dbAfter),
                allowedNotifications = oracle.notifications.map(ObservedNotificationKey::of).toSet(),
            )
        val actual = Actual(outcome, actualSql, actualDb, actualNotifications)
        val vsOracle = compare(case.compared, oracle, oracleExpectation, actual)
        val recorded = report.record(id, vsOracle)

        val deviation = report.deviations.deviationFor(id)
        if (deviation == null) {
            case.kotlinExpectation shouldBe null
            vsOracle.assertMatches(id)
        } else {
            val expectation = checkNotNull(case.kotlinExpectation) { "$id is a deviation (${deviation.xRef}) without a Kotlin expectation" }
            val kotlinExpectation =
                LockingExpectation(
                    result = expectation.result ?: oracleExpectation.result.takeIf { expectation.exception == null },
                    exception = expectation.exception,
                    check = expectation.check,
                    dbAfter = expectation.dbAfter ?: oracleExpectation.dbAfter,
                    sql = expectation.sql?.let(SqlCapture::withoutReadOnlyTransactions) ?: oracleExpectation.sql,
                    finalState = expectation.finalState ?: oracleExpectation.finalState,
                    allowedNotifications = expectation.allowedNotifications ?: oracleExpectation.allowedNotifications,
                )
            compare(case.compared, oracle, kotlinExpectation, actual).assertMatches("$id Kotlin expectation (${deviation.xRef})")
            // A listed deviation that no longer differs from Ruby must be removed from the deviation file.
            vsOracle.matches shouldBe false
            recorded.status shouldBe ParityStatus.DEVIATION
        }
    }

    private class Actual(
        val outcome: KotlinOutcome,
        val sql: List<SqlProtocolStep>,
        val db: Map<String, List<ObjectNode>>,
        val notifications: List<ObservedNotification>,
    )

    private fun compare(
        compared: Compared,
        oracle: OracleScenario,
        expected: LockingExpectation,
        actual: Actual,
    ): Comparison {
        val outcome = expected.check?.invoke(actual.outcome) ?: compareOutcome(expected.result, expected.exception, actual.outcome)
        return when (compared) {
            Compared.EXACT ->
                outcome +
                    Comparison.of("sql: expected ${expected.sql} but was ${actual.sql}".takeUnless { expected.sql == actual.sql }) +
                    RowComparator.compare(checkNotNull(expected.dbAfter), actual.db) +
                    NotificationSets.exact(oracle.notifications, actual.notifications)
            Compared.FINAL_STATE -> {
                // The oracle logged only the first 400 statements of the threads: there is no protocol to compare.
                check(oracle.sqlTruncated) { "${oracle.id}: compared on final state although its SQL log is complete" }
                outcome +
                    FinalState.compare(checkNotNull(expected.finalState), FinalState.of(actual.db)) +
                    NotificationSets.compare(checkNotNull(expected.allowedNotifications), actual.notifications)
            }
        }
    }

    /**
     * Exception class and message exactly (a Ruby `#<User:0x…>` scope rendered as oracle user 1, the only user of the
     * area's scenarios), or the result semantically ([TransferLineOutcomes.compare]).
     */
    private fun compareOutcome(
        result: JsonNode?,
        exception: OracleException?,
        actual: KotlinOutcome,
    ): Comparison =
        if (exception != null) {
            ExceptionComparator.compare(exception, actual.exception, scope = OracleFixtureConfig.user(1))
        } else {
            TransferLineOutcomes.compare(result, null, actual)
        }

    // --- acceptance criteria ---------------------------------------------------------------------------------------

    @Test
    fun `lock_order_sql locks bank, cash, store (scope blank) then checking (scope 1), as Ruby`() {
        CASES.getValue("lock_order_sql").run(this)

        fun lockedAccount(
            whereColumns: List<String>,
            binds: List<Any?>,
        ): Pair<String, String> {
            val values = whereColumns.filterNot { it.endsWith(" IS NULL") }.zip(binds).toMap()
            return values.getValue("account").toString() to (values["scope"]?.toString() ?: "")
        }
        val kotlin =
            sqlCapture.statements
                .filter { it.shape.forUpdate }
                .map { lockedAccount(it.shape.whereColumns, it.binds) }
        val ruby =
            SqlCapture
                .rubyStatements(OracleScenarios.load("lock_order_sql").sql)
                .map { it to SqlShape.parse(it.sql, it.binds) }
                .filter { (_, shape) -> shape.forUpdate }
                .map { (statement, shape) -> lockedAccount(shape.whereColumns, statement.binds) }
        val order = listOf("bank" to "", "cash" to "", "store" to "", "checking" to "1")

        // Two lock_accounts calls; the first locks twice (rows missing → create → relock): 3 × the sorted order.
        kotlin shouldContainExactly List(3) { order }.flatten()
        kotlin shouldContainExactly ruby
    }

    @Test
    fun `LockNotHeld messages match the oracle exactly`() {
        fun message(id: String): String? {
            cleanDatabase()
            return runCatching { CASES.getValue(id).run(this) }.exceptionOrNull()?.message
        }
        message("lock_wrong_locks") shouldBe "No lock held for account: checking, scope ${OracleFixtureConfig.user(1)}"
        message("lock_nested_superset") shouldBe "No lock held for account: cash, scope "
        message("lock_balance_for_locked_account_outside_lock") shouldBe "No lock held for account: bank, scope "
        OracleScenarios.load("lock_nested_superset").exception!!.message shouldBe "No lock held for account: cash, scope "
    }

    @Test
    fun `rolled back locks keep the balance rows created before the block (lock_exception_rollback, lock_rollback)`() {
        for (id in listOf("lock_exception_rollback", "lock_rollback")) {
            cleanDatabase()
            runCatching { CASES.getValue(id).run(this) }
            val db = DbSnapshot.read(jdbcTemplate.dataSource!!)
            db.getValue(DoubleEntryTables.LINES) shouldHaveSize 0
            db.getValue(DoubleEntryTables.ACCOUNT_BALANCES).map { it["account"].asText() to it["balance"].asLong() } shouldContainExactly
                listOf("bank" to 0L, "cash" to 0L)
            RowComparator.compare(OracleScenarios.load(id).dbAfter, db).assertMatches(id)
        }
    }

    @Test
    fun `lock_create_ignoring_duplicates_scoped publishes one duplicate_ignore with the unique violation`() {
        CASES.getValue("lock_create_ignoring_duplicates_scoped").run(this) shouldBe 1L

        val observed = notifications.notifications
        observed.map { it.event } shouldContainExactly listOf("duplicate_ignore.double_entry")
        observed.single().exception!! shouldContain "Key (scope, account)=(1, checking) already exists."
        NotificationCapture
            .compare(OracleScenarios.load("lock_create_ignoring_duplicates_scoped").notifications, observed)
            .assertMatches("notifications")
    }

    @Test
    fun `lock_wait_timeout_pg raises the library LockWaitTimeout caused by SQLSTATE 55P03 (X-04)`() {
        val thrown = shouldThrow<LockWaitTimeout> { CASES.getValue("lock_wait_timeout_pg").run(this) }

        thrown.message shouldBe "DoubleEntry::Locking::LockWaitTimeout"
        SqlStateTranslator.sqlStateOf(thrown.cause!!) shouldBe SqlStateTranslator.LOCK_NOT_AVAILABLE
        OracleScenarios.load("lock_wait_timeout_pg").exception!!.`class` shouldBe "ActiveRecord::LockWaitTimeout"
        // The role default is reset afterwards: a new connection has no lock timeout.
        jdbcTemplate.queryForObject("SHOW lock_timeout", String::class.java) shouldBe "0"
    }

    @Test
    fun `compat_nulls_not_distinct_index with Kotlin threads - 2 rows, 400 lines, plus or minus 200, duplicate insert 23505`() {
        @Suppress("UNCHECKED_CAST")
        val result = CASES.getValue("compat_nulls_not_distinct_index").run(this) as Map<String, Any?>

        result["balance_rows"] shouldBe 2L
        result["lines"] shouldBe 400L
        result["cash"].toString() shouldBe "200.00"
        result["bank"].toString() shouldBe "-200.00"
        result["duplicate_insert_error"].toString() shouldBe
            "SQLSTATE 23505: duplicate key value violates unique constraint \"index_account_balances_on_scope_and_account\""
        // The Ruby rendering of the same error normalises to the Kotlin one.
        PgError
            .fromRuby(OracleScenarios.load("compat_nulls_not_distinct_index").result!!["duplicate_insert_error"])
            .asText() shouldBe result["duplicate_insert_error"]
        assertReconciled()
    }

    @Test
    fun `lock_concurrent_threads_same_accounts keeps 2 balance rows instead of 16, same lines and balances (X-03)`() {
        @Suppress("UNCHECKED_CAST")
        val result = CASES.getValue("lock_concurrent_threads_same_accounts").run(this) as Map<String, Any?>

        result["lines"] shouldBe 400L
        result["cash"].toString() shouldBe "200.00"
        result["bank"].toString() shouldBe "-200.00"
        result["running_balances_consistent"] shouldBe true
        balanceRowsPerAccount() shouldBe mapOf("bank" to 1L, "cash" to 1L)
        OracleScenarios.load("lock_concurrent_threads_same_accounts").dbAfter.getValue(DoubleEntryTables.ACCOUNT_BALANCES) shouldHaveSize 16
    }

    @Test
    fun `lock_null_scope_duplicate_balance_rows keeps 1 row where Ruby kept 2 (X-03)`() {
        CASES.getValue("lock_null_scope_duplicate_balance_rows").run(this) shouldBe 1L
        OracleScenarios.load("lock_null_scope_duplicate_balance_rows").result!!.asInt() shouldBe 2
        // The second insert is the one rejected (SQLSTATE 23505): its transaction rolled back.
        sqlCapture.protocolSteps.filter { it.verb in setOf("COMMIT", "ROLLBACK") }.map { it.verb } shouldContainExactly
            listOf("COMMIT", "ROLLBACK")
    }

    @Test
    fun `lock_returns_block_value returns the block value on both calls (X-05)`() {
        CASES.getValue("lock_returns_block_value").run(this) shouldBe
            linkedMapOf("first_call_rows_created" to "block_value", "second_call_rows_exist" to "block_value")
    }

    // --- jack hammer reconciliation ------------------------------------------------------------------------------

    /**
     * Replays a `script/jack_hammer` run at the oracle's shape (workers, flushes, transfers) with the M-015 jack hammer
     * (Kotlin threads: the JVM has no `fork`, so the processes run `jack_hammer_t2000` is replayed with threads too)
     * and compares the reconciliation output, rendered by Ruby's `reconcile` rules (`script/jack_hammer:159-200`).
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["jack_hammer_t2000", "jack_hammer_t2000f4", "jack_hammer_t2000z"])
    fun `jack hammer reconciliation matches the oracle run`(
        name: String,
        reporter: TestReporter,
    ) {
        val oracle = JackHammerOutcome.load(name)
        oracle.exitCode shouldBe 0

        val run = jackHammer(balanceFlushCount = oracle.flushes, reporter = reporter, workers = oracle.workers, total = oracle.transfers)

        val kotlin =
            JackHammerOutcome(
                workers = oracle.workers,
                workerMode = oracle.workerMode,
                flushes = oracle.flushes,
                transfers = run.transfers,
                reconciliation = reconcile(run.transfers),
                exitCode = 0,
            )
        kotlin shouldBe oracle
        oracle.reconciliation shouldContainExactly
            listOf(JackHammerOutcome.LINES_WRITTEN, JackHammerOutcome.ALL_RECONCILED, JackHammerOutcome.DONE)
        // The stricter M-015 checks on top: BR-026 on every line, Σ amount = 0, one balance row per account (X-03).
        assertReconciled()
        sumOfAmounts() shouldBe 0
        balanceRowsPerAccount() shouldBe jackHammerAccounts.associate { "${it.identifier}@1" to 1L }
    }

    /** `reconcile` (`script/jack_hammer:167-200`): the lines it prints for the current ledger. */
    private fun reconcile(transfers: Int): List<String> {
        var errors = 0
        val output = mutableListOf<String>()
        if (lineCount() == transfers * 2L) {
            output += JackHammerOutcome.LINES_WRITTEN
        } else {
            output += JackHammerOutcome.LINES_MISSING
            errors++
        }
        val failed = jackHammerAccounts.filterNot(::reconciled)
        if (failed.isEmpty()) {
            output += JackHammerOutcome.ALL_RECONCILED
        } else {
            failed.forEach { output += "Account ${it.identifier} failed to reconcile. :(" }
            errors += failed.size
        }
        if (errors == 0) output += JackHammerOutcome.DONE
        return output
    }

    /** `reconciled?` (`script/jack_hammer:159-165`): latest line balance = Σ amounts = cached balance. */
    private fun reconciled(account: AccountInstance): Boolean {
        val row =
            jdbcTemplate.queryForMap(
                """
                SELECT COALESCE(SUM(amount), 0) AS total,
                       (SELECT balance FROM double_entry_lines WHERE account = ? AND scope = ? ORDER BY id DESC LIMIT 1) AS final_balance,
                       (SELECT balance FROM double_entry_account_balances WHERE account = ? AND scope = ?) AS cached_balance
                  FROM double_entry_lines WHERE account = ? AND scope = ?
                """.trimIndent(),
                account.identifier,
                account.scopeIdentity,
                account.identifier,
                account.scopeIdentity,
                account.identifier,
                account.scopeIdentity,
            )
        val total = (row["total"] as Number).toLong()
        return (row["final_balance"] as Number?)?.toLong() == total && (row["cached_balance"] as Number?)?.toLong() == total
    }

    @Order(2)
    @Test
    fun `every scenario of the area is driven and the report has no mismatches`() {
        CASES.keys shouldContainExactlyInAnyOrder ParityArea.LOCKING.scenarioIds
        report.assertParity()
        val written = ParityReport.reportDir().resolve("${ParityArea.LOCKING.key}.md").readText()
        ParityArea.LOCKING.scenarioIds.forEach { id ->
            val line = written.lines().single { it.startsWith("| $id |") }
            (line.contains("| match |") || line.contains("| deviation (X-")) shouldBe true
        }
        written.lines().none { it.contains("| mismatch |") || it.contains("| not run |") } shouldBe true
        report.outcomes().filter { it.status == ParityStatus.DEVIATION }.map { it.scenario } shouldContainExactlyInAnyOrder
            report.deviations.entries.keys
    }

    companion object {
        private val report = ParityReport(ParityArea.LOCKING)

        private const val CONCURRENT_THREADS = 8
        private const val TRANSFERS_PER_THREAD = 25

        @JvmStatic
        fun scenarioIds(): List<String> = ParityArea.LOCKING.scenarioIds

        /** X-05 (D-010): `lockAccounts` returns the block value; Ruby returned nil at the outermost level. */
        private fun blockValue(value: Any?) = LockingExpectation(result = TransferLineSerializer.ser(value))

        /** Both threaded scenarios may see losers of the balance-row creation race (BR-069): duplicates of bank or cash. */
        private val CREATION_RACE =
            listOf("bank", "cash")
                .map { ObservedNotificationKey("duplicate_ignore.double_entry", "Key (scope, account)=(null, $it)") }
                .toSet()

        /** The oracle's SQL protocol of [id] with its [n]-th (0-based) `COMMIT` turned into a `ROLLBACK`. */
        private fun withRollback(
            id: String,
            n: Int,
        ): List<SqlProtocolStep> {
            val steps = SqlCapture.protocol(SqlCapture.fromRubyLog(OracleScenarios.load(id).sql))
            val commit = steps.withIndex().filter { it.value.verb == "COMMIT" }[n].index
            return steps.mapIndexed { i, step -> if (i == commit) step.copy(verb = "ROLLBACK") else step }
        }

        /** The scenario blocks of `tools/oracle.rb:247-317` and `:444-457`, in the same order. */
        private val CASES: Map<String, Case> =
            mapOf(
                "lock_creates_missing_balances" to
                    Case(blockValue("ok")) { doubleEntry.lockAccounts(acct("checking", 1), acct("bank")) { "ok" } },
                "lock_returns_block_value" to
                    Case(
                        LockingExpectation(
                            result =
                                TransferLineSerializer.ser(
                                    linkedMapOf("first_call_rows_created" to "block_value", "second_call_rows_exist" to "block_value"),
                                ),
                        ),
                    ) {
                        val first = doubleEntry.lockAccounts(acct("checking", 1)) { "block_value" }
                        val second = doubleEntry.lockAccounts(acct("checking", 1)) { "block_value" }
                        linkedMapOf("first_call_rows_created" to first, "second_call_rows_exist" to second)
                    },
                "lock_balance_from_lines_after_flush" to
                    Case {
                        val (b, c) = listOf(acct("bank"), acct("cash"))
                        xfer(10_00, b, c, "salary")
                        xfer(5_00, b, c, "bonus")
                        // `AccountBalance.delete_all`
                        jdbcTemplate.update("DELETE FROM double_entry_account_balances")
                        doubleEntry.lockAccounts(c) { "ok" }
                        linkedMapOf(
                            "cash_cached" to checkNotNull(accountBalances.findByAccount(c)).balance(resolver),
                            "cash_balance" to doubleEntry.balance(c),
                        )
                    },
                "lock_multiple_transfers_atomic" to
                    Case {
                        val (b, ch, sv) = listOf(acct("bank"), acct("checking", 1), acct("savings", 1))
                        doubleEntry.lockAccounts(b, ch, sv) {
                            xfer(10_00, b, ch, "deposit")
                            xfer(4_00, ch, sv, "save")
                            // `User.create!(username: 'inside-lock')`: the oracle's `users` table is not part of the library.
                        }
                        linkedMapOf("checking" to doubleEntry.balance(ch), "savings" to doubleEntry.balance(sv))
                    },
                "lock_wrong_locks" to
                    Case { doubleEntry.lockAccounts(acct("bank")) { xfer(1, acct("bank"), acct("checking", 1), "deposit") } },
                "lock_nested_subset_ok" to
                    Case(blockValue("inner")) {
                        doubleEntry.lockAccounts(acct("bank"), acct("cash")) { doubleEntry.lockAccounts(acct("cash")) { "inner" } }
                    },
                "lock_nested_superset" to
                    Case {
                        doubleEntry.lockAccounts(acct("bank")) { doubleEntry.lockAccounts(acct("bank"), acct("cash")) { "inner" } }
                    },
                "lock_inside_transaction" to
                    Case { TransactionTemplate(transactionManager).execute { doubleEntry.lockAccounts(acct("bank")) { "x" } } },
                // `raise ActiveRecord::Rollback` is `LockContext.rollback()` (U-009); the block yields no value, as in Ruby.
                "lock_rollback" to
                    Case {
                        doubleEntry.lockAccounts(acct("bank"), acct("cash")) { ctx ->
                            xfer(1_00, acct("bank"), acct("cash"), "salary")
                            ctx.rollback()
                            null
                        }
                    },
                "lock_exception_rollback" to
                    Case {
                        doubleEntry.lockAccounts(acct("bank"), acct("cash")) {
                            xfer(1_00, acct("bank"), acct("cash"), "salary")
                            throw RuntimeException("boom")
                        }
                    },
                "lock_order_sql" to
                    Case(blockValue("ok")) {
                        val accounts = listOf(acct("store"), acct("checking", 1), acct("bank"), acct("cash"))
                        doubleEntry.lockAccounts(accounts) { "ok" }
                        doubleEntry.lockAccounts(accounts) { "ok" }
                    },
                "lock_empty" to Case(blockValue("empty")) { doubleEntry.lockAccounts { "empty" } },
                "lock_balance_for_locked_account_outside_lock" to Case { locking.balanceForLockedAccount(acct("bank")) },
                "lock_wait_timeout_pg" to
                    Case(
                        LockingExpectation(
                            exception = OracleException("DoubleEntry::Locking::LockWaitTimeout", "DoubleEntry::Locking::LockWaitTimeout"),
                        ),
                    ) {
                        val b = acct("bank")
                        doubleEntry.lockAccounts(b) { "create_row" }
                        val ready = CountDownLatch(1)
                        val release = CountDownLatch(1)
                        val holderThread = Executors.newSingleThreadExecutor()
                        try {
                            val holder =
                                holderThread.submit {
                                    doubleEntry.lockAccounts(b) {
                                        ready.countDown()
                                        release.await(30, TimeUnit.SECONDS)
                                    }
                                }
                            check(ready.await(30, TimeUnit.SECONDS)) { "the holder did not lock bank" }
                            try {
                                withLockTimeout("300ms") { doubleEntry.lockAccounts(b) { "never" } }
                            } finally {
                                release.countDown()
                                holder.get(30, TimeUnit.SECONDS)
                            }
                        } finally {
                            holderThread.shutdownNow()
                        }
                    },
                // Ruby inserts a second NULL-scope `cash` row; the V2 index rejects it with 23505 (D-005), so the
                // port counts the rows after that rejection.
                "lock_null_scope_duplicate_balance_rows" to
                    Case(
                        LockingExpectation(
                            result = TransferLineSerializer.ser(1L),
                            dbAfter =
                                OracleScenarios.load("lock_null_scope_duplicate_balance_rows").dbAfter.mapValues { (table, rows) ->
                                    if (table == DoubleEntryTables.ACCOUNT_BALANCES) rows.filter { it["id"].asLong() == 1L } else rows
                                },
                            sql = withRollback("lock_null_scope_duplicate_balance_rows", 1),
                        ),
                    ) {
                        val c = acct("cash")
                        createBalanceRow(c, 1)
                        try {
                            createBalanceRow(c, 2)
                        } catch (e: DataAccessException) {
                            check(SqlStateTranslator.sqlStateOf(e) == SqlStateTranslator.UNIQUE_VIOLATION) { "not a unique violation: $e" }
                        }
                        balanceRowCount("cash")
                    },
                "lock_create_ignoring_duplicates_scoped" to
                    Case {
                        val ch = acct("checking", 1)
                        rowCreator.createIgnoringDuplicates(ch, Money.ofMinor(1, ch.currency))
                        rowCreator.createIgnoringDuplicates(ch, Money.ofMinor(2, ch.currency))
                        balanceRowCount("checking")
                    },
                "lock_concurrent_threads_same_accounts" to
                    Case(
                        LockingExpectation(
                            finalState = FinalState(400, listOf("bank@null=-20000", "cash@null=20000")),
                            allowedNotifications = CREATION_RACE,
                        ),
                        Compared.FINAL_STATE,
                    ) {
                        val (b, c) = listOf(acct("bank"), acct("cash"))
                        concurrentSalaries(b, c)
                        linkedMapOf(
                            "lines" to lineCount(),
                            "cash" to doubleEntry.balance(c),
                            "bank" to doubleEntry.balance(b),
                            "cash_cached" to checkNotNull(accountBalances.findByAccount(c)).balance(resolver),
                            "running_balances_consistent" to runningBalancesConsistent("cash"),
                        )
                    },
                // D-005 probe. Ruby swapped in the NULLS NOT DISTINCT index; Kotlin's Flyway V2 already has it.
                "compat_nulls_not_distinct_index" to
                    Case(compared = Compared.FINAL_STATE, oracleResult = { result ->
                        result.deepCopy<ObjectNode>().apply {
                            set<JsonNode>(
                                "duplicate_insert_error",
                                PgError.fromRuby(result["duplicate_insert_error"]),
                            )
                        }
                    }) {
                        val index =
                            jdbcTemplate.queryForObject(
                                "SELECT indexdef FROM pg_indexes WHERE indexname = 'index_account_balances_on_scope_and_account'",
                                String::class.java,
                            )!!
                        check(index.endsWith("(scope, account) NULLS NOT DISTINCT")) { "unexpected index: $index" }
                        val (b, c) = listOf(acct("bank"), acct("cash"))
                        concurrentSalaries(b, c)
                        linkedMapOf(
                            "balance_rows" to balanceRowCount(),
                            "lines" to lineCount(),
                            "cash" to doubleEntry.balance(c),
                            "bank" to doubleEntry.balance(b),
                            "duplicate_insert_error" to duplicateInsertError(c),
                        )
                    },
            )
    }
}
