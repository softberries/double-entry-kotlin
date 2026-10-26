package com.softberries.doubleentry.transfer

import com.softberries.doubleentry.balance.cache.AccountBalanceUpdater
import com.softberries.doubleentry.config.DoubleEntryConfiguration
import com.softberries.doubleentry.config.DoubleEntryTables
import com.softberries.doubleentry.errors.AccountWouldBeSentNegative
import com.softberries.doubleentry.errors.AccountWouldBeSentPositiveError
import com.softberries.doubleentry.errors.MismatchedCurrencies
import com.softberries.doubleentry.errors.TransferIsNegative
import com.softberries.doubleentry.errors.TransferNotAllowed
import com.softberries.doubleentry.ledger.DetailRef
import com.softberries.doubleentry.ledger.LineWriter
import com.softberries.doubleentry.locking.Locking
import com.softberries.doubleentry.locking.LockingSettings
import com.softberries.doubleentry.locking.errors.LockMustBeOutermostTransaction
import com.softberries.doubleentry.locking.errors.LockNotHeld
import com.softberries.doubleentry.parity.ExceptionComparator
import com.softberries.doubleentry.parity.MoneyComparator
import com.softberries.doubleentry.parity.OracleFixtureConfig
import com.softberries.doubleentry.parity.OracleScenarios
import com.softberries.doubleentry.parity.SqlCapture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * M-012 on PostgreSQL 17: `TransferService.transfer` (BR-020..028, BR-030, BR-031, BR-034, BR-051, X-07), replaying the
 * json-mode oracle scenarios named in the issue and asserting the Kotlin rows, return values, exceptions and SQL
 * against them.
 *
 * Ports `spec/double_entry/transfer_spec.rb` (json mode) [1:2:1:1], [1:2:1:2], [1:2:1:3:1], [1:2:1:3:2], [1:2:2:1:1],
 * [1:2:2:1:2], [1:2:2:1:3], [1:2:3:1:1], [1:2:3:1:2], and `spec/double_entry/locking_spec.rb` [1:4], [1:5], [1:6].
 *
 * Not ported (X-02, D-004: the legacy `double_entry_line_metadata` table mode does not exist in Kotlin):
 * transfer_spec [1:2:1:4:1], [1:2:1:4:2], [1:2:2:2:1]..[1:2:2:2:6], [1:2:3:2:1]..[1:2:3:2:4]. Their "no metadata rows"
 * counterpart is [X02LineMetadataAbsenceTest].
 *
 * Deviations asserted here (Ruby oracle ≠ Kotlin expectation): X-07 (`transfer_amount_currency_differs_*`:
 * `MismatchedCurrencies` before any write instead of `Money::Bank::UnknownRate` after the balance rows were committed)
 * and BR-032 (`transfer_bigint_overflow`: `ArithmeticException` instead of `ActiveModel::RangeError`, same rows).
 */
class TransferIntegrationTest : TransferIntegrationTestBase() {
    @Autowired
    private lateinit var locking: Locking

    @Autowired
    private lateinit var configuration: DoubleEntryConfiguration

    @Autowired
    private lateinit var lineWriter: LineWriter

    @Autowired
    private lateinit var balanceUpdater: AccountBalanceUpdater

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val bank get() = acct("bank")
    private val cash get() = acct("cash")
    private val store get() = acct("store")

    private fun withFixturesFlag() =
        TransferService(
            configuration.transfers,
            locking.copy(settings = LockingSettings(runningInsideTransactionalFixtures = true)),
            lineWriter,
            balanceUpdater,
            resolver,
        )

    private fun assertNothingWritten() {
        lineCount() shouldBe 0
        balanceRowCount() shouldBe 0
    }

    // --- BR-025 write protocol ---------------------------------------------------------------------------------

    @Test
    fun `scoped basic transfer writes two partnered lines and both balance rows (transfer_scoped_basic)`() {
        val oracle = OracleScenarios.load("transfer_scoped_basic")

        val result = transfers.transfer(usd(1000), from = bank, to = acct("checking", 1), code = "deposit")

        assertLineMatches(oracle.result!![0], result.credit)
        assertLineMatches(oracle.result!![1], result.debit)
        cachedBalances() shouldContainExactly listOf("bank" to -1000L, "checking@1" to 1000L)
        assertDbMatches(oracle)
        // The credit line's partner-id update is a later clock read than its insert.
        result.credit.updatedAt shouldBeGreaterThan result.credit.createdAt
        result.debit.updatedAt shouldBe result.debit.createdAt
    }

    @Test
    fun `the SQL sequence matches Ruby statement by statement (BR-025, D-017, transfer_scoped_basic)`() {
        val oracle = OracleScenarios.load("transfer_scoped_basic")

        transfers.transfer(usd(1000), from = bank, to = acct("checking", 1), code = "deposit")

        val steps = sqlCapture.protocolSteps
        steps shouldContainExactly SqlCapture.protocol(SqlCapture.fromRubyLog(oracle.sql))
        val lockTransaction = steps.takeLast(8)
        lockTransaction.map { it.verb to it.table } shouldContainExactly
            listOf(
                "SELECT" to DoubleEntryTables.ACCOUNT_BALANCES,
                "SELECT" to DoubleEntryTables.ACCOUNT_BALANCES,
                "UPDATE" to DoubleEntryTables.ACCOUNT_BALANCES,
                "UPDATE" to DoubleEntryTables.ACCOUNT_BALANCES,
                "INSERT" to DoubleEntryTables.LINES,
                "INSERT" to DoubleEntryTables.LINES,
                "UPDATE" to DoubleEntryTables.LINES,
                "COMMIT" to null,
            )
        val inserts = sqlCapture.shapes.filter { it.verb == "INSERT" && it.table == DoubleEntryTables.LINES }
        inserts.size shouldBe 2
    }

    @Test
    fun `unscoped basic transfer (transfer_unscoped_basic)`() {
        val oracle = OracleScenarios.load("transfer_unscoped_basic")

        transfers.transfer(usd(100_000), from = bank, to = cash, code = "salary")

        sqlCapture.protocolSteps shouldContainExactly SqlCapture.protocol(SqlCapture.fromRubyLog(oracle.sql))
        assertDbMatches(oracle)
    }

    @Test
    fun `the result is (credit, debit) with the from-line first (transfer_return_value)`() {
        val oracle = OracleScenarios.load("transfer_return_value")
        val expected = oracle.result!!

        val (credit, debit) = transfers.transfer(usd(250), from = bank, to = cash, code = "salary")

        credit.id shouldBe expected["credit"]["id"].asLong()
        MoneyComparator.compare(expected["credit"]["amount"], observed(credit.amount)).assertMatches("credit.amount")
        MoneyComparator.compare(expected["credit"]["balance"], observed(credit.balance)).assertMatches("credit.balance")
        credit.account.toString() shouldBe expected["credit"]["account"]["account_instance"].asText()
        credit.partnerAccount.toString() shouldBe expected["credit"]["partner_account"]["account_instance"].asText()
        credit.partnerId shouldBe expected["credit"]["partner_id"].asLong()
        credit.code shouldBe expected["credit"]["code"]["symbol"].asText()
        credit.isDecrease shouldBe expected["credit"]["decrease?"].asBoolean()
        credit.isIncrease shouldBe expected["credit"]["increase?"].asBoolean()
        credit.currency.code shouldBe expected["credit"]["currency"].asText()
        debit.id shouldBe expected["debit"]["id"].asLong()
        MoneyComparator.compare(expected["debit"]["amount"], observed(debit.amount)).assertMatches("debit.amount")
        MoneyComparator.compare(expected["debit"]["balance"], observed(debit.balance)).assertMatches("debit.balance")
        debit.account.toString() shouldBe expected["debit"]["account"]["account_instance"].asText()
        debit.partnerId shouldBe expected["debit"]["partner_id"].asLong()
        assertDbMatches(oracle)
    }

    // --- BR-021 check order --------------------------------------------------------------------------------------

    @Test
    fun `a negative amount raises TransferIsNegative first (transfer_negative_amount)`() {
        val oracle = OracleScenarios.load("transfer_negative_amount")

        // Also not allowed and identical: the negative check wins.
        val thrown = shouldThrow<TransferIsNegative> { transfers.transfer(usd(-1), from = cash, to = cash, code = "nope") }

        ExceptionComparator.compare(oracle.exception!!, thrown).assertMatches(oracle.id)
        assertNothingWritten()
        assertDbMatches(oracle)
    }

    @Test
    fun `the reverse direction is not allowed (transfer_not_allowed_direction)`() {
        val oracle = OracleScenarios.load("transfer_not_allowed_direction")

        val thrown = shouldThrow<TransferNotAllowed> { transfers.transfer(usd(1), from = cash, to = bank, code = "salary") }

        thrown.message shouldBe "[:cash, :bank, :salary]"
        ExceptionComparator.compare(oracle.exception!!, thrown).assertMatches(oracle.id)
        assertNothingWritten()
    }

    @Test
    fun `an unknown code is not allowed (transfer_not_allowed_code)`() {
        val oracle = OracleScenarios.load("transfer_not_allowed_code")

        val thrown = shouldThrow<TransferNotAllowed> { transfers.transfer(usd(1), from = bank, to = cash, code = "nope") }

        thrown.message shouldBe "[:bank, :cash, :nope]"
        ExceptionComparator.compare(oracle.exception!!, thrown).assertMatches(oracle.id)
        assertNothingWritten()
    }

    @Test
    fun `the allowlist is checked before identical accounts and currencies`() {
        // bank → bank is not defined: TransferNotAllowed with the key, not the "identical" message.
        shouldThrow<TransferNotAllowed> { transfers.transfer(usd(1), from = bank, to = bank, code = "salary") }.message shouldBe
            "[:bank, :bank, :salary]"
        // An undefined USD → BTC transfer: TransferNotAllowed, not MismatchedCurrencies.
        shouldThrow<TransferNotAllowed> { transfers.transfer(usd(1), from = cash, to = acct("btc_store"), code = "x") }
        assertNothingWritten()
    }

    @Test
    fun `the same account on both sides is not allowed even when defined (transfer_same_account)`() {
        val oracle = OracleScenarios.load("transfer_same_account")

        val thrown =
            shouldThrow<TransferNotAllowed> {
                transfers.transfer(usd(1), from = acct("savings", 1), to = acct("savings", 1), code = "self")
            }

        thrown.message shouldBe "from account and to account are identical"
        ExceptionComparator.compare(oracle.exception!!, thrown).assertMatches(oracle.id)
        assertNothingWritten()
    }

    @Test
    fun `account currencies must match (transfer_mismatched_account_currencies)`() {
        val oracle = OracleScenarios.load("transfer_mismatched_account_currencies")

        val thrown =
            shouldThrow<MismatchedCurrencies> { transfers.transfer(usd(1), from = bank, to = acct("btc_store"), code = "mismatch") }

        thrown.message shouldBe "Mismatched currency (BTC <> USD)"
        ExceptionComparator.compare(oracle.exception!!, thrown).assertMatches(oracle.id)
        assertNothingWritten()
    }

    @Test
    fun `account currencies are checked before the amount currency`() {
        val thrown =
            shouldThrow<MismatchedCurrencies> {
                transfers.transfer(money(1, "EUR"), from = bank, to = acct("btc_store"), code = "mismatch")
            }

        thrown.message shouldBe "Mismatched currency (BTC <> USD)"
    }

    // --- BR-024 / X-07 amount currency -----------------------------------------------------------------------------

    @Test
    fun `X-07 an amount in another currency raises MismatchedCurrencies up front (transfer_amount_currency_differs_from_accounts)`() {
        val oracle = OracleScenarios.load("transfer_amount_currency_differs_from_accounts")

        val thrown = shouldThrow<MismatchedCurrencies> { transfers.transfer(money(100, "BTC"), from = bank, to = cash, code = "salary") }

        sqlCapture.shapes.shouldBeEmpty()
        thrown.message shouldBe "Mismatched currency (BTC <> USD)"
        // Ruby: Money::Bank::UnknownRate after the lock had committed both balance rows.
        oracle.exception!!.`class` shouldBe "Money::Bank::UnknownRate"
        assertNothingWritten()
    }

    @Test
    fun `X-07 also on a first transfer with no balance rows (transfer_amount_currency_differs_first_transfer_zero_balance)`() {
        val oracle = OracleScenarios.load("transfer_amount_currency_differs_first_transfer_zero_balance")

        shouldThrow<MismatchedCurrencies> { transfers.transfer(money(500, "EUR"), from = cash, to = store, code = "purchase") }

        oracle.dbAfter[DoubleEntryTables.ACCOUNT_BALANCES]!!.size shouldBe 2
        assertNothingWritten()
    }

    @Test
    fun `X-07 an amount currency check also applies when the accounts already have rows`() {
        transfers.transfer(usd(100), from = bank, to = cash, code = "salary")
        sqlCapture.clear()

        shouldThrow<MismatchedCurrencies> { transfers.transfer(money(100, "JPY"), from = bank, to = cash, code = "salary") }

        sqlCapture.shapes.shouldBeEmpty()
        cachedBalances() shouldContainExactly listOf("bank" to -100L, "cash" to 100L)
        lineCount() shouldBe 2
    }

    // --- BR-022, BR-023 ------------------------------------------------------------------------------------------

    @Test
    fun `BR-022 a zero amount writes two zero lines and leaves balances unchanged (transfer_zero_amount)`() {
        val oracle = OracleScenarios.load("transfer_zero_amount")

        val (credit, debit) = transfers.transfer(usd(0), from = bank, to = cash, code = "salary")

        val steps = sqlCapture.protocolSteps
        credit.amountMinorUnits shouldBe 0
        debit.amountMinorUnits shouldBe 0
        // AR's update_attribute writes nothing for an unchanged value: no balance UPDATE at all.
        steps.filter { it.verb == "UPDATE" }.map { it.table } shouldContainExactly listOf(DoubleEntryTables.LINES)
        steps shouldContainExactly SqlCapture.protocol(SqlCapture.fromRubyLog(oracle.sql))
        cachedBalances() shouldContainExactly listOf("bank" to 0L, "cash" to 0L)
        assertDbMatches(oracle)
    }

    @Test
    fun `BR-023 the same identifier with another scope is allowed (transfer_same_identifier_other_scope)`() {
        val oracle = OracleScenarios.load("transfer_same_identifier_other_scope")

        transfers.transfer(usd(500), from = bank, to = acct("checking", 1), code = "deposit")
        transfers.transfer(usd(300), from = acct("checking", 1), to = acct("checking", 2), code = "p2p")

        MoneyComparator.compare(oracle.result!!["alice"], observed(calculator.calculate(acct("checking", 1)))).assertMatches("alice")
        MoneyComparator.compare(oracle.result!!["bob"], observed(calculator.calculate(acct("checking", 2)))).assertMatches("bob")
        assertDbMatches(oracle)
    }

    // --- BR-030, BR-031 positive/negative only ---------------------------------------------------------------------

    @Test
    fun `positive-only account sent negative rolls back, the lock-created row stays (transfer_positive_only_violation)`() {
        val oracle = OracleScenarios.load("transfer_positive_only_violation")
        val savings = acct("savings", 1)
        transfers.transfer(usd(500), from = bank, to = savings, code = "deposit")

        val thrown =
            shouldThrow<AccountWouldBeSentNegative> {
                transfers.transfer(usd(600), from = savings, to = acct("checking", 1), code = "withdraw")
            }
        val steps = sqlCapture.protocolSteps

        thrown.message shouldBe "#{Account account: savings scope:  currency: USD}"
        ExceptionComparator.compare(oracle.exception!!, thrown).assertMatches(oracle.id)
        lineCount() shouldBe 2
        cachedBalances() shouldContainExactly listOf("bank" to -500L, "savings@1" to 500L, "checking@1" to 0L)
        assertDbMatches(oracle)
        steps.last().verb shouldBe "ROLLBACK"
    }

    @Test
    fun `positive-only account drawn to exactly zero is fine (transfer_positive_only_exact_zero)`() {
        val oracle = OracleScenarios.load("transfer_positive_only_exact_zero")
        val savings = acct("savings", 1)

        transfers.transfer(usd(500), from = bank, to = savings, code = "deposit")
        transfers.transfer(usd(500), from = savings, to = acct("checking", 1), code = "withdraw")

        MoneyComparator.compare(oracle.result!!, observed(calculator.calculate(savings))).assertMatches(oracle.id)
        assertDbMatches(oracle)
    }

    @Test
    fun `negative-only account sent positive raises (transfer_negative_only_violation)`() {
        val oracle = OracleScenarios.load("transfer_negative_only_violation")

        val thrown =
            shouldThrow<AccountWouldBeSentPositiveError> { transfers.transfer(usd(1), from = cash, to = acct("liability"), code = "repay") }

        ExceptionComparator.compare(oracle.exception!!, thrown).assertMatches(oracle.id)
        lineCount() shouldBe 0
        assertDbMatches(oracle)
    }

    @Test
    fun `negative-only account sent negative is fine (transfer_negative_only_ok)`() {
        val oracle = OracleScenarios.load("transfer_negative_only_ok")

        transfers.transfer(usd(700), from = acct("liability"), to = cash, code = "borrow")

        MoneyComparator.compare(oracle.result!!, observed(calculator.calculate(acct("liability")))).assertMatches(oracle.id)
        assertDbMatches(oracle)
    }

    // --- BR-027, BR-028 detail and metadata ------------------------------------------------------------------------

    @Test
    fun `metadata is stored identically on both lines (transfer_with_metadata)`() {
        val oracle = OracleScenarios.load("transfer_with_metadata")
        val metadata = mapOf("country" to "AU", "tax" to listOf("GST", "VAT"), "n" to 1, "nested" to mapOf("a" to 1))

        val (credit, debit) = transfers.transfer(usd(100), from = bank, to = acct("checking", 1), code = "deposit", metadata = metadata)

        assertLineMatches(oracle.result!![0], credit)
        assertLineMatches(oracle.result!![1], debit)
        assertDbMatches(oracle)
    }

    @Test
    fun `simple metadata (transfer_with_metadata_simple)`() {
        val oracle = OracleScenarios.load("transfer_with_metadata_simple")

        transfers.transfer(
            usd(100),
            from = bank,
            to = acct("checking", 1),
            code = "deposit",
            metadata = mapOf("country" to "AU", "tax" to listOf("GST", "VAT")),
        )

        assertDbMatches(oracle)
    }

    @Test
    fun `empty metadata stays an empty object (transfer_with_empty_metadata)`() {
        val oracle = OracleScenarios.load("transfer_with_empty_metadata")

        val (credit, debit) = transfers.transfer(usd(100), from = bank, to = acct("checking", 1), code = "deposit", metadata = emptyMap())

        credit.metadata shouldBe emptyMap()
        debit.metadata shouldBe emptyMap()
        jdbcTemplate.queryForList("SELECT metadata::text FROM double_entry_lines ORDER BY id", String::class.java) shouldContainExactly
            listOf("{}", "{}")
        assertDbMatches(oracle)
    }

    @Test
    fun `no metadata is SQL NULL`() {
        transfers.transfer(usd(100), from = bank, to = cash, code = "salary")

        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM double_entry_lines WHERE metadata IS NULL", Long::class.java) shouldBe 2
    }

    @Test
    fun `later changes to the caller's metadata map do not reach the stored lines`() {
        val metadata = mutableMapOf<String, Any?>("country" to "AU")

        val (credit, _) = transfers.transfer(usd(100), from = bank, to = cash, code = "salary", metadata = metadata)
        metadata["country"] = "NZ"

        credit.metadata shouldBe mapOf("country" to "AU")
    }

    @Test
    fun `detail is stored on both lines (transfer_with_detail)`() {
        val oracle = OracleScenarios.load("transfer_with_detail")

        val (credit, debit) =
            transfers.transfer(usd(100), from = bank, to = acct("checking", 1), code = "deposit", detail = DetailRef("User", 1))

        credit.detail shouldBe DetailRef("User", 1)
        debit.detail shouldBe DetailRef("User", 1)
        assertLineMatches(oracle.result!![0], credit)
        assertLineMatches(oracle.result!![1], debit)
        assertDbMatches(oracle)
    }

    // --- currencies, BR-032 bigint -------------------------------------------------------------------------------

    @Test
    fun `BTC transfer stores satoshis (transfer_btc)`() {
        val oracle = OracleScenarios.load("transfer_btc")

        val (credit, debit) =
            transfers.transfer(money(12_345_678, "BTC"), from = acct("btc_store"), to = acct("btc_wallet", 1), code = "btc_buy")

        credit.amountMinorUnits shouldBe -12_345_678
        debit.amountMinorUnits shouldBe 12_345_678
        assertDbMatches(oracle)
    }

    @Test
    fun `JPY transfer has no subunits (transfer_jpy)`() {
        val oracle = OracleScenarios.load("transfer_jpy")

        val (credit, debit) = transfers.transfer(money(500, "JPY"), from = acct("jpy_bank"), to = acct("jpy_wallet"), code = "jpy_in")

        credit.amountMinorUnits shouldBe -500
        debit.amount.toString() shouldBe "500"
        assertDbMatches(oracle)
    }

    @Test
    fun `Long MAX_VALUE succeeds (transfer_bigint_max)`() {
        val oracle = OracleScenarios.load("transfer_bigint_max")

        transfers.transfer(usd(Long.MAX_VALUE), from = bank, to = cash, code = "salary")

        assertDbMatches(oracle)
    }

    @Test
    fun `a balance overflowing bigint raises ArithmeticException before any write (transfer_bigint_overflow)`() {
        val oracle = OracleScenarios.load("transfer_bigint_overflow")
        transfers.transfer(usd(Long.MAX_VALUE), from = bank, to = cash, code = "salary")
        sqlCapture.clear()

        shouldThrow<ArithmeticException> { transfers.transfer(usd(Long.MAX_VALUE), from = bank, to = cash, code = "salary") }

        // BR-032: Ruby raised ActiveModel::RangeError on the write; the rows are the same.
        oracle.exception!!.`class` shouldBe "ActiveModel::RangeError"
        sqlCapture.shapes.filter { it.verb in setOf("UPDATE", "INSERT") }.shouldBeEmpty()
        assertDbMatches(oracle)
    }

    // --- BR-026, BR-051 running balances ---------------------------------------------------------------------------

    @Test
    fun `BR-026 running balances after a sequence of transfers (transfer_sequence_running_balances)`() {
        val oracle = OracleScenarios.load("transfer_sequence_running_balances")

        transfers.transfer(usd(100_000), from = bank, to = cash, code = "salary")
        transfers.transfer(usd(20_000), from = cash, to = store, code = "purchase")
        transfers.transfer(usd(10_000), from = cash, to = store, code = "layby")
        transfers.transfer(usd(5_000), from = cash, to = bank, code = "refund")

        for (name in listOf("cash", "bank", "store")) {
            MoneyComparator.compare(oracle.result!![name], observed(calculator.calculate(acct(name)))).assertMatches(name)
        }
        cachedBalances() shouldContainExactly listOf("bank" to -95_000L, "cash" to 65_000L, "store" to 30_000L)
        assertRunningBalances()
        assertDbMatches(oracle)
    }

    @Test
    fun `BR-051 new balances are computed from the locked cache rows, not from the lines`() {
        transfers.transfer(usd(100), from = bank, to = cash, code = "salary")
        // A stale cache (as after a lost update) spreads into the next lines until AccountFixer repairs it.
        jdbcTemplate.update("UPDATE double_entry_account_balances SET balance = 5000 WHERE account = 'cash'")

        val (credit, debit) = transfers.transfer(usd(40), from = bank, to = cash, code = "salary")

        credit.balanceMinorUnits shouldBe -140
        debit.balanceMinorUnits shouldBe 5040
        cachedBalances() shouldContainExactly listOf("bank" to -140L, "cash" to 5040L)
    }

    // --- BR-034 transfers and locks --------------------------------------------------------------------------------

    @Test
    fun `LK 1-4 a transfer inside a transaction raises LockMustBeOutermostTransaction (transfer_inside_ar_transaction)`() {
        val oracle = OracleScenarios.load("transfer_inside_ar_transaction")

        val thrown =
            shouldThrow<LockMustBeOutermostTransaction> {
                TransactionTemplate(transactionManager).execute { transfers.transfer(usd(1), from = bank, to = cash, code = "salary") }
            }

        ExceptionComparator.compare(oracle.exception!!, thrown).assertMatches(oracle.id)
        assertNothingWritten()
        assertDbMatches(oracle)
    }

    @Test
    fun `LK 1-4 with the spec accounts`() {
        shouldThrow<LockMustBeOutermostTransaction> {
            TransactionTemplate(transactionManager).execute {
                transfers.transfer(
                    usd(1000),
                    from = resolver.account("account_a", scope = "1"),
                    to = resolver.account("account_b", scope = "2"),
                    code = "test",
                )
            }
        }
        assertNothingWritten()
    }

    @Test
    fun `with the fixtures flag one enclosing transaction is allowed (transfer_inside_transaction_with_fixtures_flag)`() {
        val oracle = OracleScenarios.load("transfer_inside_transaction_with_fixtures_flag")
        val service = withFixturesFlag()

        val result = TransactionTemplate(transactionManager).execute { service.transfer(usd(1), from = bank, to = cash, code = "salary") }!!

        assertLineMatches(oracle.result!![0], result.credit)
        assertLineMatches(oracle.result!![1], result.debit)
        assertDbMatches(oracle)
    }

    @Test
    fun `with the fixtures flag two enclosing transactions raise (transfer_inside_two_transactions_with_fixtures_flag)`() {
        val oracle = OracleScenarios.load("transfer_inside_two_transactions_with_fixtures_flag")
        val service = withFixturesFlag()
        val requiresNew =
            TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

        val thrown =
            shouldThrow<LockMustBeOutermostTransaction> {
                TransactionTemplate(transactionManager).execute {
                    requiresNew.execute { service.transfer(usd(1), from = bank, to = cash, code = "salary") }
                }
            }

        ExceptionComparator.compare(oracle.exception!!, thrown).assertMatches(oracle.id)
        assertNothingWritten()
        assertDbMatches(oracle)
    }

    @Test
    fun `LK 1-5 transfers inside a lock holding both accounts commit atomically (lock_multiple_transfers_atomic)`() {
        val oracle = OracleScenarios.load("lock_multiple_transfers_atomic")
        val checking = acct("checking", 1)
        val savings = acct("savings", 1)

        locking.lockAccounts(bank, checking, savings) {
            transfers.transfer(usd(1000), from = bank, to = checking, code = "deposit")
            transfers.transfer(usd(400), from = checking, to = savings, code = "save")
        }

        val steps = sqlCapture.protocolSteps
        MoneyComparator.compare(oracle.result!!["checking"], observed(calculator.calculate(checking))).assertMatches("checking")
        MoneyComparator.compare(oracle.result!!["savings"], observed(calculator.calculate(savings))).assertMatches("savings")
        assertRunningBalances()
        assertDbMatches(oracle)
        // The oracle ends with the two balance queries of its result; the transfers end at the lock's COMMIT.
        val ruby = SqlCapture.protocol(SqlCapture.fromRubyLog(oracle.sql))
        steps shouldContainExactly ruby.take(ruby.indexOfLast { it.verb == "COMMIT" } + 1)
    }

    @Test
    fun `LK 1-5 a transfer inside a lock on its spec accounts does not raise`() {
        val a = resolver.account("account_a", scope = "1")
        val b = resolver.account("account_b", scope = "2")

        locking.lockAccounts(a, b) { transfers.transfer(usd(1000), from = a, to = b, code = "test") }

        cachedBalances() shouldContainExactly listOf("account_a@1" to -1000L, "account_b@2" to 1000L)
    }

    @Test
    fun `a failure in the second transfer rolls back the first one inside the same lock`() {
        val checking = acct("checking", 1)
        val savings = acct("savings", 1)

        shouldThrow<AccountWouldBeSentNegative> {
            locking.lockAccounts(bank, checking, savings) {
                transfers.transfer(usd(1000), from = bank, to = checking, code = "deposit")
                transfers.transfer(usd(1), from = savings, to = checking, code = "withdraw")
            }
        }

        lineCount() shouldBe 0
        cachedBalances().map { it.second } shouldContainExactly listOf(0L, 0L, 0L)
    }

    @Test
    fun `LK 1-6 a transfer inside a lock that does not hold the to-account raises LockNotHeld`() {
        val a = resolver.account("account_a", scope = "1")
        val b = resolver.account("account_b", scope = "2")
        val c = resolver.account("account_c", scope = "3")

        val thrown =
            shouldThrow<LockNotHeld> { locking.lockAccounts(a, c) { transfers.transfer(usd(1000), from = a, to = b, code = "test") } }

        thrown.message shouldBe "No lock held for account: account_b, scope 2"
        lineCount() shouldBe 0
    }

    @Test
    fun `LK 1-6 the message names the scope object (lock_wrong_locks)`() {
        val oracle = OracleScenarios.load("lock_wrong_locks")
        val user = OracleFixtureConfig.user(1)

        val thrown =
            shouldThrow<LockNotHeld> {
                locking.lockAccounts(bank) { transfers.transfer(usd(1), from = bank, to = acct("checking", 1), code = "deposit") }
            }

        ExceptionComparator.compare(oracle.exception!!, thrown, scope = user).assertMatches(oracle.id)
        assertDbMatches(oracle)
    }

    // --- transfer_spec.rb (json mode) ------------------------------------------------------------------------------

    private val specAmount get() = usd(1000)
    private val test get() = acct("test", 1)
    private val savings get() = acct("savings", 1)

    private fun specTransfer(metadata: Map<String, Any?>? = null) =
        transfers.transfer(specAmount, from = test, to = savings, code = "bonus", metadata = metadata)

    private fun newLinesMetadata(): List<String?> =
        jdbcTemplate.queryForList("SELECT metadata::text FROM double_entry_lines ORDER BY id DESC LIMIT 2", String::class.java)

    @Test
    fun `TR 1-2-1-1 creates lines`() {
        val before = lineCount()
        specTransfer()
        lineCount() - before shouldBe 2
    }

    @Test
    fun `TR 1-2-1-2 returns the credit and debit`() {
        val (credit, debit) = specTransfer()

        credit.amount shouldBe -specAmount
        debit.amount shouldBe specAmount
        credit.id shouldNotBe debit.id
    }

    @Test
    fun `TR 1-2-1-3-1 without metadata no metadata rows are created (there is no metadata table, X-02)`() {
        specTransfer()

        metadataTableExists() shouldBe false
    }

    @Test
    fun `TR 1-2-1-3-2 without metadata the lines carry none`() {
        specTransfer()

        newLinesMetadata() shouldContainExactly listOf(null, null)
    }

    @Test
    fun `TR 1-2-2-1-1 with metadata no metadata rows are created (X-02)`() {
        specTransfer(mapOf("country" to "AU", "tax" to "GST"))

        metadataTableExists() shouldBe false
    }

    @Test
    fun `TR 1-2-2-1-2 and 1-2-2-1-3 both key-value pairs are on both lines`() {
        specTransfer(mapOf("country" to "AU", "tax" to "GST"))

        val lines = jdbcTemplate.queryForList("SELECT metadata->>'country' AS country, metadata->>'tax' AS tax FROM double_entry_lines")
        lines.count { it["country"] == "AU" } shouldBe 2
        lines.count { it["tax"] == "GST" } shouldBe 2
    }

    @Test
    fun `TR 1-2-3-1-1 an array value creates no metadata rows (X-02)`() {
        specTransfer(mapOf("tax" to listOf("GST", "VAT")))

        metadataTableExists() shouldBe false
    }

    @Test
    fun `TR 1-2-3-1-2 both array values are stored under the same key on both lines`() {
        val (credit, debit) = specTransfer(mapOf("tax" to listOf("GST", "VAT")))

        listOf(credit, debit).count { it.metadata?.get("tax") == listOf("GST", "VAT") } shouldBe 2
        newLinesMetadata().count { it == """{"tax": ["GST", "VAT"]}""" } shouldBe 2
    }

    private fun metadataTableExists(): Boolean =
        jdbcTemplate.queryForObject("SELECT to_regclass('double_entry_line_metadata') IS NOT NULL", Boolean::class.java)!!
}
