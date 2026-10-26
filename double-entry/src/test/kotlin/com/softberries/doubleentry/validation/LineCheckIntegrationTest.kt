package com.softberries.doubleentry.validation

import com.softberries.doubleentry.config.DoubleEntryTables
import com.softberries.doubleentry.errors.AccountWouldBeSentNegative
import com.softberries.doubleentry.locking.errors.LockMustBeOutermostTransaction
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.parity.ExceptionComparator
import com.softberries.doubleentry.parity.MoneyComparator
import com.softberries.doubleentry.parity.ObservedMoney
import com.softberries.doubleentry.parity.OracleFixtureConfig
import com.softberries.doubleentry.parity.OracleScenario
import com.softberries.doubleentry.parity.OracleScenarios
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.ZoneOffset

/**
 * M-013 on PostgreSQL 17: `LineCheckService` and `AccountFixer` (BR-090..096) replaying the json-mode oracle scenarios
 * `linecheck_*` and `accountfixer_*` (`tools/oracle.rb:393-441`) and asserting results, rows, logs and exceptions.
 *
 * Deviation asserted here: X-06 (D-011). The `log` column's header lines are compared byte for byte with the oracle;
 * the Ruby record dumps (`#<DoubleEntry::Line …>`) are mapped to the documented Kotlin rendering
 * ([LineCheckLog.dump]) with the same fields and values.
 */
class LineCheckIntegrationTest : ValidationIntegrationTestBase() {
    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val bank get() = acct("bank")
    private val cash get() = acct("cash")
    private val store get() = acct("store")

    private fun savings() = acct("savings", 1)

    private fun observed(money: Money) = ObservedMoney(money.minorUnits, money.currency.code, money.toString())

    /** `created_at`/`updated_at` of every line as the Kotlin dump renders them (ISO-8601 UTC). */
    private fun lineStamps(): Map<Long, Pair<String, String>> =
        jdbcTemplate
            .queryForList("SELECT id, created_at, updated_at FROM double_entry_lines")
            .associate { row ->
                fun iso(column: String) = (row[column] as Timestamp).toLocalDateTime().toInstant(ZoneOffset.UTC).toString()
                (row["id"] as Number).toLong() to (iso("created_at") to iso("updated_at"))
            }

    /**
     * The oracle log with each Ruby `#<DoubleEntry::Line …>` dump rewritten into the Kotlin format (X-06): same fields,
     * `nil` → `null`, and the timestamps of the Kotlin run (the oracle's are wall-clock).
     */
    private fun expectedKotlinLog(
        rubyLog: String,
        stamps: Map<Long, Pair<String, String>>,
    ): String =
        RUBY_LINE_DUMP.replace(rubyLog) { match ->
            val id = match.groupValues[2].toLong()
            val (created, updated) = stamps.getValue(id)
            "Line(${match.groupValues[1].replace(": nil", ": null")}, created_at: $created, updated_at: $updated)"
        }

    private fun oracleLog(scenario: OracleScenario): String = scenario.result!!["attributes"]["log"].asText()

    private fun assertResultMatches(
        scenario: OracleScenario,
        actual: LineCheck?,
        expectedLog: String = oracleLog(scenario),
    ) {
        val expected = scenario.result!!["attributes"]
        actual!!.id shouldBe expected["id"].asLong()
        actual.lastLineId shouldBe expected["last_line_id"].asLong()
        actual.errorsFound shouldBe expected["errors_found"].asBoolean()
        actual.log shouldBe expectedLog
        actual.createdAt shouldBe actual.updatedAt
    }

    // --- BR-094 result row --------------------------------------------------------------------------------------

    @Test
    fun `no lines - null and no row (linecheck_no_lines)`() {
        val oracle = OracleScenarios.load("linecheck_no_lines")

        lineCheck.perform() shouldBe null

        lineCheckCount() shouldBe 0
        assertDbMatches(oracle)
    }

    @Test
    fun `consistent data - a row with last_line_id 2, no errors and an empty log (linecheck_consistent)`() {
        val oracle = OracleScenarios.load("linecheck_consistent")
        transfer(100_00, bank, savings(), "deposit")

        val result = lineCheck.perform()

        assertResultMatches(oracle, result)
        lineChecks.findById(result!!.id)?.let { LineCheck.of(it) } shouldBe result
        assertDbMatches(oracle)
    }

    @Test
    fun `a second run without new lines returns null (linecheck_second_run_nothing_new)`() {
        val oracle = OracleScenarios.load("linecheck_second_run_nothing_new")
        transfer(100_00, bank, savings(), "deposit")
        lineCheck.perform()

        lineCheck.perform() shouldBe null

        lineCheckCount() shouldBe 1
        assertDbMatches(oracle)
    }

    @Test
    fun `the second run checks only ids above the last checked one (linecheck_incremental)`() {
        val oracle = OracleScenarios.load("linecheck_incremental")
        clock.set(OracleFixtureConfig.T0)
        transfer(100_00, bank, savings(), "deposit")
        lineCheck.perform()
        clock.set(OracleFixtureConfig.T0.plus(Duration.ofHours(1)))
        transfer(1_00, bank, savings(), "deposit")
        sqlCapture.clear()

        val result = lineCheck.perform()

        assertResultMatches(oracle, result)
        assertDbMatches(oracle)
        val pages = sqlCapture.shapes.filter { it.table == DoubleEntryTables.LINES && it.limit == LineCheckService.BATCH_SIZE.toLong() }
        pages.size shouldBe 1
        // One previous-line lookup per visited line: only ids 3 and 4.
        val lookups =
            sqlCapture.shapes.filter {
                it.table == DoubleEntryTables.LINES && it.orderBy == listOf("id DESC") &&
                    "id" in it.whereColumns
            }
        lookups.size shouldBe 2
    }

    // --- BR-090 -------------------------------------------------------------------------------------------------

    @Test
    fun `lastLineIdChecked is 0 without checks, else the newest by created_at (linecheck_last_line_id_checked)`() {
        val oracle = OracleScenarios.load("linecheck_last_line_id_checked")
        val none = lineCheck.lastLineIdChecked()
        insertCheck(100, minutesBeforeT0 = 3)
        insertCheck(300, minutesBeforeT0 = 1)
        insertCheck(200, minutesBeforeT0 = 2)

        val after = lineCheck.lastLineIdChecked()

        none shouldBe oracle.result!!["none"].asLong()
        after shouldBe oracle.result!!["after"].asLong()
        assertDbMatches(oracle)
    }

    // --- BR-091, BR-093, BR-095 line errors ---------------------------------------------------------------------

    @Test
    fun `a corrupted line without fixer is logged and not corrected (linecheck_line_error_no_fixer)`() {
        val oracle = OracleScenarios.load("linecheck_line_error_no_fixer")
        transfer(100_00, bank, savings(), "deposit")
        sql("UPDATE double_entry_lines SET balance = balance + 1 WHERE id = (SELECT MIN(id) FROM double_entry_lines)")
        val stamps = lineStamps()

        val result = lineCheck.perform()

        result!!.log!! shouldStartWith
            "*********************************\n" +
            "Error on line #1: balance:-99.99 != 0.00 + -100.00\n" +
            "*********************************\n"
        result.log!! shouldContain
            "\n\n*********************************\n" +
            "Error on account #{Account account: bank scope:  currency: USD}: -100.00 (cached balance) != -99.99 (running balance)\n"
        assertResultMatches(oracle, result, expectedKotlinLog(oracleLog(oracle), stamps))
        lineBalances() shouldContainExactly listOf(-9999L, 10000L)
        cachedBalance(bank) shouldBe -10000L
        assertDbMatches(oracle, ignoreLog = true)
    }

    @Test
    fun `the log renders the record dumps in the documented Kotlin format, each indented by 2 spaces (X-06)`() {
        transfer(100_00, bank, savings(), "deposit")
        sql("UPDATE double_entry_lines SET balance = balance + 1 WHERE id = 1")
        val (created, updated) = lineStamps().getValue(1)

        val lines = lineCheck.perform()!!.log!!.split("\n")

        lines[3] shouldBe "  []"
        lines[4] shouldBe
            "  Line(id: 1, account: \"bank\", scope: null, code: \"deposit\", amount: -10000, balance: -9999, partner_id: 2, " +
            "partner_account: \"savings\", partner_scope: \"1\", detail_type: null, detail_id: null, metadata: null, " +
            "created_at: $created, updated_at: $updated)"
        lines[5] shouldBe ""
    }

    @Test
    fun `a corrupted line with AccountFixer is corrected (linecheck_line_error_with_fixer)`() {
        val oracle = OracleScenarios.load("linecheck_line_error_with_fixer")
        transfer(100_00, bank, savings(), "deposit")
        sql("UPDATE double_entry_lines SET balance = balance + 1 WHERE id = (SELECT MIN(id) FROM double_entry_lines)")
        val stamps = lineStamps()

        val result = lineCheck.perform(fixer)

        assertResultMatches(oracle, result, expectedKotlinLog(oracleLog(oracle), stamps))
        lineBalances() shouldContainExactly listOf(-10000L, 10000L)
        cachedBalance(bank) shouldBe -10000L
        assertDbMatches(oracle, ignoreLog = true)
    }

    @Test
    fun `unscoped lines use scope IS NULL for the previous-line lookup (linecheck_unscoped_accounts)`() {
        val oracle = OracleScenarios.load("linecheck_unscoped_accounts")
        transfer(10_00, bank, cash, "salary")
        transfer(3_00, cash, store, "purchase")
        sql(
            "UPDATE double_entry_lines SET balance = balance + 7 WHERE id = (SELECT MIN(id) FROM double_entry_lines WHERE account = 'cash')",
        )
        val stamps = lineStamps()
        sqlCapture.clear()

        val result = lineCheck.perform(fixer)

        assertResultMatches(oracle, result, expectedKotlinLog(oracleLog(oracle), stamps))
        assertDbMatches(oracle, ignoreLog = true)
        val lookups =
            sqlCapture.shapes.filter { it.table == DoubleEntryTables.LINES && it.orderBy == listOf("id DESC") && "id" in it.whereColumns }
        lookups.map { it.whereColumns } shouldContainExactly List(4) { listOf("account", "scope IS NULL", "id") }
        lookups.map { it.limit } shouldContainExactly List(4) { 1L }
    }

    // --- BR-092 account errors ----------------------------------------------------------------------------------

    @Test
    fun `a corrupted cached balance with AccountFixer is logged and corrected (linecheck_account_balance_error_with_fixer)`() {
        val oracle = OracleScenarios.load("linecheck_account_balance_error_with_fixer")
        transfer(100_00, bank, savings(), "deposit")
        sql("UPDATE double_entry_account_balances SET balance = balance + 1 WHERE id = (SELECT MIN(id) FROM double_entry_account_balances)")

        val result = lineCheck.perform(fixer)

        result!!.log!! shouldContain
            "Error on account #{Account account: bank scope:  currency: USD}: -99.99 (cached balance) != -100.00 (running balance)\n"
        assertResultMatches(oracle, result)
        cachedBalance(bank) shouldBe -10000L
        assertDbMatches(oracle)
    }

    @Test
    fun `inside a transaction LockMustBeOutermostTransaction is thrown (linecheck_inside_transaction)`() {
        val oracle = OracleScenarios.load("linecheck_inside_transaction")
        transfer(1_00, bank, savings(), "deposit")

        val error =
            shouldThrow<LockMustBeOutermostTransaction> {
                TransactionTemplate(transactionManager).execute { lineCheck.perform() }
            }

        ExceptionComparator.compare(oracle.exception!!, error).assertMatches(oracle.id)
        assertDbMatches(oracle)
    }

    @Test
    fun `line errors come first in id order, then account errors in first-seen order of the active accounts`() {
        transfer(10_00, bank, cash, "salary") // lines 1 bank, 2 cash
        transfer(3_00, cash, store, "purchase") // lines 3 cash, 4 store
        sql("UPDATE double_entry_lines SET balance = balance + 5 WHERE id = 4")
        sql("UPDATE double_entry_lines SET balance = balance + 1 WHERE id = 1")
        sql("UPDATE double_entry_account_balances SET balance = balance + 2 WHERE account = 'cash'")

        val log = lineCheck.perform()!!.log!!

        val headers = log.lines().filter { it.startsWith("Error on ") }
        headers shouldContainExactly
            listOf(
                "Error on line #1: balance:-9.99 != 0.00 + -10.00",
                "Error on line #4: balance:3.05 != 0.00 + 3.00",
                "Error on account #{Account account: bank scope:  currency: USD}: -10.00 (cached balance) != -9.99 (running balance)",
                "Error on account #{Account account: cash scope:  currency: USD}: 7.02 (cached balance) != 7.00 (running balance)",
                "Error on account #{Account account: store scope:  currency: USD}: 3.00 (cached balance) != 3.05 (running balance)",
            )
    }

    @Test
    fun `lines are read in pages of 1000 by id, and every page is checked`() {
        val count = 2345
        sql(
            "INSERT INTO double_entry_lines (account, scope, code, amount, balance, partner_account, created_at, updated_at) " +
                "SELECT 'cash', NULL, 'salary', 1, g, 'bank', now(), now() FROM generate_series(1, $count) g",
        )
        sql(
            "INSERT INTO double_entry_account_balances (account, scope, balance, created_at, updated_at) VALUES ('cash', NULL, $count, now(), now())",
        )
        sql("UPDATE double_entry_lines SET balance = balance + 1 WHERE id = 1500")
        sqlCapture.clear()

        val result = lineCheck.perform()!!

        result.lastLineId shouldBe count.toLong()
        result.errorsFound shouldBe true
        val headers = result.log!!.lines().filter { it.startsWith("Error on ") }
        headers shouldContainExactly
            listOf(
                "Error on line #1500: balance:15.01 != 14.99 + 0.01",
                "Error on line #1501: balance:15.01 != 15.01 + 0.01",
            )
        result.log!! shouldNotContain "Error on account"
        val pages = sqlCapture.shapes.filter { it.table == DoubleEntryTables.LINES && it.limit == LineCheckService.BATCH_SIZE.toLong() }
        pages.size shouldBe 3
    }

    // --- BR-096 AccountFixer ------------------------------------------------------------------------------------

    @Test
    fun `AccountFixer rewrites the running balances and the cache (accountfixer_standalone)`() {
        val oracle = OracleScenarios.load("accountfixer_standalone")
        transfer(10_00, bank, cash, "salary")
        transfer(5_00, bank, cash, "bonus")
        sql("UPDATE double_entry_lines SET balance = 0 WHERE account = 'cash'")
        sql("UPDATE double_entry_account_balances SET balance = 0 WHERE account = 'cash'")

        fixer.recalculateAccount(cash)

        MoneyComparator.compare(oracle.result!!["cash"], observed(calculator.calculate(cash))).assertMatches("cash")
        MoneyComparator.compare(oracle.result!!["cached"], observed(usd(cachedBalance(cash)))).assertMatches("cached")
        assertDbMatches(oracle)
    }

    @Test
    fun `AccountFixer on a positive-only account going negative throws and rolls back (accountfixer_positive_only_goes_negative)`() {
        val oracle = OracleScenarios.load("accountfixer_positive_only_goes_negative")
        transfer(5_00, bank, savings(), "deposit")
        transfer(5_00, savings(), acct("checking", 1), "withdraw")
        sql("UPDATE double_entry_lines SET amount = 400 WHERE id = (SELECT MIN(id) FROM double_entry_lines WHERE account = 'savings')")

        val error = shouldThrow<AccountWouldBeSentNegative> { fixer.recalculateAccount(savings()) }

        ExceptionComparator.compare(oracle.exception!!, error).assertMatches(oracle.id)
        error.message shouldBe "#{Account account: savings scope:  currency: USD}"
        assertDbMatches(oracle)
    }

    private fun insertCheck(
        lastLineId: Long,
        minutesBeforeT0: Long,
    ) {
        val at =
            OracleFixtureConfig.T0
                .minus(Duration.ofMinutes(minutesBeforeT0))
                .atOffset(ZoneOffset.UTC)
                .toLocalDateTime()
        jdbcTemplate.update(
            "INSERT INTO double_entry_line_checks (last_line_id, errors_found, log, created_at, updated_at) VALUES (?, false, '', ?, ?)",
            lastLineId,
            at,
            at,
        )
    }

    private companion object {
        val RUBY_LINE_DUMP = Regex("""#<DoubleEntry::Line (id: (\d+), [^\n]*?), created_at: "[^"]*", updated_at: "[^"]*">""")
    }
}
