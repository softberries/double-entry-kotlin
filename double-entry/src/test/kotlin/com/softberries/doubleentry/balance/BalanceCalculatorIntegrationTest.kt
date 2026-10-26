package com.softberries.doubleentry.balance

import com.fasterxml.jackson.databind.JsonNode
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.parity.MoneyComparator
import com.softberries.doubleentry.parity.ObservedMoney
import com.softberries.doubleentry.parity.OracleScenarios
import com.softberries.doubleentry.parity.SqlCapture
import com.softberries.doubleentry.parity.SqlShape
import com.softberries.doubleentry.testing.MutableClock.Companion.ORACLE_T0
import com.softberries.doubleentry.testing.OracleSample
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.TimeZone

/**
 * Acceptance criteria of M-009 (BR-040..048) on a real database. Each test seeds the `db_after` rows of its oracle
 * scenario (fixture: `balance_fixture_lines.json`, T0 = 2026-01-15T10:00Z), compares the Money with the scenario's
 * `result` and the SQL shape of the query with the scenario's last ActiveRecord statement(s).
 */
class BalanceCalculatorIntegrationTest : BalanceIntegrationTest() {
    private fun daysBeforeT0(days: Long): Instant = ORACLE_T0.minus(Duration.ofDays(days))

    private fun balance(
        identifier: String,
        options: BalanceOptions = BalanceOptions(),
        scopeIdentity: String? = null,
    ): Money = account(identifier, scopeIdentity).balance(calculator, options)

    private fun Money.shouldMatchOracle(expected: JsonNode) =
        MoneyComparator.compare(expected, ObservedMoney(minorUnits, currency.code, toString())).assertMatches()

    /** Shapes of the statements this test sent to `double_entry_lines`. */
    private fun capturedLineQueries(): List<SqlShape> = sqlCapture.shapes.filter { it.table == "double_entry_lines" }

    /** Shapes of the last [count] balance queries of the oracle scenario (the setup statements come before). */
    private fun oracleQueries(
        scenarioId: String,
        count: Int = 1,
    ): List<SqlShape> =
        SqlCapture
            .fromRubyLog(OracleScenarios.load(scenarioId).sql)
            // Balance queries only: running balance (LIMIT 1) or SUM (no ORDER BY); not the oracle's own row dumps.
            .filter { it.table == "double_entry_lines" && it.verb == "SELECT" && (it.limit == 1L || it.orderBy.isEmpty()) }
            .takeLast(count)

    /** Seeds [scenarioId], runs [query] and checks its Money against `result` and its SQL against the oracle's. */
    private fun assertScenario(
        scenarioId: String,
        query: () -> Money,
    ): Money {
        fixtures.load(scenarioId)
        sqlCapture.clear()
        val money = query()
        money.shouldMatchOracle(OracleSample.result(scenarioId))
        capturedLineQueries() shouldContainExactly oracleQueries(scenarioId)
        return money
    }

    // --- BR-040 / BR-041 ---------------------------------------------------------------------------------------

    @Test
    fun `BR-040 current balance is the balance of the latest line by id (balance_current)`() {
        fixtures.load("balance_current") shouldBe 10
        sqlCapture.clear()
        val expected = OracleSample.result("balance_current")

        balance("cash").shouldMatchOracle(expected["cash"])
        balance("store").shouldMatchOracle(expected["store"])
        balance("bank").shouldMatchOracle(expected["bank"])

        balance("cash") shouldBe Money(72500, CurrencyUnit.USD)
        balance("store").toString() shouldBe "325.00"
        val shapes = capturedLineQueries()
        shapes.take(3) shouldContainExactly oracleQueries("balance_current", 3)
        shapes.first().whereColumns shouldContainExactly listOf("account")
        shapes.first().orderBy shouldContainExactly listOf("id DESC")
        shapes.first().limit shouldBe 1L
    }

    @Test
    fun `BR-040 a scoped account adds the scope predicate (balance_symbol_with_scope)`() {
        fixtures.load("balance_symbol_with_scope")
        sqlCapture.clear()

        balance("checking", scopeIdentity = "1").shouldMatchOracle(OracleSample.result("balance_symbol_with_scope"))

        capturedLineQueries() shouldContainExactly oracleQueries("balance_symbol_with_scope")
        capturedLineQueries().single().whereColumns shouldContainExactly listOf("account", "scope")
    }

    @Test
    fun `BR-041 no lines gives zero in the account's currency (balance_no_lines)`() {
        val expected = OracleSample.result("balance_no_lines")

        balance("cash").shouldMatchOracle(expected["cash"])
        balance("btc_store").shouldMatchOracle(expected["btc"])
        balance("jpy_bank").shouldMatchOracle(expected["jpy"])

        balance("cash").toString() shouldBe "0.00"
        balance("btc_store").toString() shouldBe "0.00000000"
        balance("jpy_bank").toString() shouldBe "0"
    }

    // --- BR-042 ------------------------------------------------------------------------------------------------

    @Test
    fun `BR-042 at exactly the timestamp of a line includes it (balance_at_exact_timestamp)`() {
        assertScenario("balance_at_exact_timestamp") { balance("cash", BalanceOptions(at = daysBeforeT0(14))) }
            .toString() shouldBe "700.00"
    }

    @Test
    fun `BR-042 at between two lines (balance_at_between)`() {
        assertScenario("balance_at_between") { balance("cash", BalanceOptions(at = daysBeforeT0(10))) }
            .toString() shouldBe "700.00"
    }

    @Test
    fun `BR-041 BR-042 at before the first line is zero (balance_at_before_first)`() {
        assertScenario("balance_at_before_first") { balance("cash", BalanceOptions(at = daysBeforeT0(30))) }
            .toString() shouldBe "0.00"
    }

    @Test
    fun `BR-042 the latest line is chosen by id, not by created_at (balance_order_by_id_not_time)`() {
        fixtures.load("balance_order_by_id_not_time")
        sqlCapture.clear()
        val expected = OracleSample.result("balance_order_by_id_not_time")

        balance("cash").shouldMatchOracle(expected["current"])
        balance("cash", BalanceOptions(at = ORACLE_T0)).shouldMatchOracle(expected["at_T0"])
        balance("cash", BalanceOptions(at = daysBeforeT0(1))).shouldMatchOracle(expected["at_T0_minus_1d"])

        balance("cash", BalanceOptions(at = daysBeforeT0(1))) shouldBe Money(1100, CurrencyUnit.USD)
        capturedLineQueries().take(3) shouldContainExactly oracleQueries("balance_order_by_id_not_time", 3)
    }

    // --- BR-043 / BR-044 ---------------------------------------------------------------------------------------

    @Test
    fun `BR-043 from and to sum the amounts with both ends inclusive (balance_between_inclusive)`() {
        assertScenario("balance_between_inclusive") {
            balance("cash", BalanceOptions(from = daysBeforeT0(14), to = daysBeforeT0(7)))
        } shouldBe Money(-25000, CurrencyUnit.USD)
    }

    @Test
    fun `BR-043 a window without lines sums to zero (balance_between_empty)`() {
        assertScenario("balance_between_empty") {
            balance("cash", BalanceOptions(from = daysBeforeT0(40), to = daysBeforeT0(30)))
        }.toString() shouldBe "0.00"
    }

    @Test
    fun `BR-043 only from is ignored (balance_from_only)`() {
        assertScenario("balance_from_only") { balance("cash", BalanceOptions(from = daysBeforeT0(14))) }
            .toString() shouldBe "725.00"
    }

    @Test
    fun `BR-043 only to is ignored (balance_to_only)`() {
        assertScenario("balance_to_only") { balance("cash", BalanceOptions(to = daysBeforeT0(7))) }
            .toString() shouldBe "725.00"
    }

    @Test
    fun `BR-043 from after to is an empty BETWEEN and sums to zero (balance_inverted_range)`() {
        assertScenario("balance_inverted_range") {
            balance("cash", BalanceOptions(from = ORACLE_T0, to = daysBeforeT0(21)))
        }.toString() shouldBe "0.00"
    }

    @Test
    fun `BR-044 at beats the range (balance_at_overrides_range)`() {
        assertScenario("balance_at_overrides_range") {
            balance("cash", BalanceOptions(at = daysBeforeT0(10), from = daysBeforeT0(21), to = ORACLE_T0))
        }.toString() shouldBe "700.00"
    }

    // --- BR-045 ------------------------------------------------------------------------------------------------

    @Test
    fun `BR-045 a single code sums the lines with that code (balance_code)`() {
        assertScenario("balance_code") { balance("store", BalanceOptions(code = "purchase")) }
            .toString() shouldBe "225.00"
    }

    @Test
    fun `BR-045 a list of codes uses IN (balance_codes)`() {
        assertScenario("balance_codes") { balance("store", BalanceOptions(codes = listOf("purchase", "layby"))) }
            .toString() shouldBe "325.00"
    }

    @Test
    fun `BR-045 code and codes are combined (balance_code_and_codes)`() {
        assertScenario("balance_code_and_codes") {
            balance("store", BalanceOptions(code = "layby", codes = listOf("purchase")))
        }.toString() shouldBe "325.00"
    }

    @Test
    fun `BR-045 an unknown code sums to zero (balance_code_unknown)`() {
        assertScenario("balance_code_unknown") { balance("store", BalanceOptions(code = "nope")) }
            .toString() shouldBe "0.00"
    }

    @Test
    fun `BR-045 an empty codes list gives the running balance (balance_codes_empty_array)`() {
        assertScenario("balance_codes_empty_array") { balance("cash", BalanceOptions(codes = emptyList())) }
            .toString() shouldBe "725.00"
    }

    @Test
    fun `BR-045 code with at sums up to at (balance_code_and_at)`() {
        assertScenario("balance_code_and_at") { balance("cash", BalanceOptions(at = daysBeforeT0(20), code = "salary")) }
            .toString() shouldBe "1000.00"
    }

    @Test
    fun `BR-045 code with a range sums inside the window (balance_code_and_range)`() {
        assertScenario("balance_code_and_range") {
            balance("store", BalanceOptions(from = daysBeforeT0(15), to = daysBeforeT0(13), code = "purchase"))
        }.toString() shouldBe "200.00"
    }

    // --- BR-046 / BR-048 (gaps without a Ruby spec) ------------------------------------------------------------

    @Test
    fun `BR-046 an at with a +10 00 offset equal to T0 compares in UTC (balance_time_with_offset)`() {
        val t0InSydney = OffsetDateTime.parse("2026-01-15T20:00:00+10:00")

        assertScenario("balance_time_with_offset") { balance("cash", BalanceOptions.of(at = t0InSydney)) }
            .toString() shouldBe "725.00"
        balance("cash", BalanceOptions.of(at = t0InSydney.minusSeconds(1))) shouldBe Money(75000, CurrencyUnit.USD)
    }

    @Test
    fun `BR-046 time filters do not depend on the JVM default time zone`() {
        fixtures.load("balance_fixture_lines")
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            balance("cash", BalanceOptions(at = daysBeforeT0(14))).toString() shouldBe "700.00"
            balance("cash", BalanceOptions(at = daysBeforeT0(14).minusNanos(1000))).toString() shouldBe "1000.00"
            balance("cash", BalanceOptions(from = daysBeforeT0(14), to = daysBeforeT0(7))).toString() shouldBe "-250.00"
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `BR-048 balances read the lines, never the cached balance (balance_reads_lines_not_cache)`() {
        fixtures.load("balance_reads_lines_not_cache")
        val expected = OracleSample.result("balance_reads_lines_not_cache")
        val cached =
            jdbcTemplate.queryForObject(
                "SELECT balance FROM double_entry_account_balances WHERE account = 'cash'",
                Long::class.java,
            )!!
        sqlCapture.clear()
        Money(cached, CurrencyUnit.USD).shouldMatchOracle(expected["cached"])

        balance("cash").shouldMatchOracle(expected["balance"])

        balance("cash") shouldBe Money(1000, CurrencyUnit.USD)
        sqlCapture.shapes.none { it.table == "double_entry_account_balances" } shouldBe true
    }
}
