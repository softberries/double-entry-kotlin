package com.softberries.doubleentry.balance

import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.parity.SqlShape
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Port of `spec/double_entry/balance_calculator_spec.rb` (`#calculate`, scoped account `:test`) as real-DB tests: the
 * Ruby spec stubs `Line.where` and checks the relation calls (`docs/80-tests.md` §3.3); here the captured SQL shape
 * shows the predicates and whether the query is a SUM (no ORDER BY/LIMIT) or the running balance (ORDER BY id DESC
 * LIMIT 1), and the seeded lines show that the filter really applies.
 */
class BalanceCalculatorSpecTest : BalanceIntegrationTest() {
    private val user = "7"

    private fun time(text: String): Instant = OffsetDateTime.parse(text).toInstant()

    // Ruby `Time.parse('2014-06-19 15:09:18 +1000')` etc.
    private val at = time("2014-06-19T15:09:18+10:00")
    private val from = time("2014-06-19T10:09:18+10:00")
    private val to = time("2014-06-19T20:09:18+10:00")

    @BeforeEach
    fun seed() {
        // One line before `from`, one inside the window before `at`, one inside the window after `at`, one after `to`.
        fixtures.line("test", user, "code1", 100, 100, time("2014-06-19T09:00:00+10:00"))
        fixtures.line("test", user, "code2", 20, 120, time("2014-06-19T12:00:00+10:00"))
        fixtures.line("test", user, "code1", 3, 123, time("2014-06-19T18:00:00+10:00"))
        fixtures.line("test", user, "code3", 4000, 4123, time("2014-06-19T21:00:00+10:00"))
        // Same identifier, other scope: never counted.
        fixtures.line("test", "8", "code1", 50000, 50000, time("2014-06-19T12:00:00+10:00"))
        sqlCapture.clear()
    }

    private fun calculate(options: BalanceOptions): Money = calculator.calculate(account("test", user), options)

    private fun query(): SqlShape = sqlCapture.shapes.single { it.table == "double_entry_lines" }

    private val SqlShape.isSum: Boolean get() = orderBy.isEmpty() && limit == null

    private fun usd(minor: Long) = Money(minor, CurrencyUnit.USD)

    @Test
    fun `1_1_1_1_1 at - scopes the lines summed to times before (or at) the given time`() {
        calculate(BalanceOptions(at = at)) shouldBe usd(120)

        query().whereColumns shouldContainExactly listOf("account", "created_at", "scope")
        query().isSum shouldBe false
    }

    @Test
    fun `1_1_1_1_2_1 at with a time range - ignores the time range when summing the lines`() {
        calculate(BalanceOptions(at = at, from = from, to = to)) shouldBe usd(120)

        query().whereColumns shouldContainExactly listOf("account", "created_at", "scope")
        query().isSum shouldBe false
        query().orderBy shouldContainExactly listOf("id DESC")
        query().limit shouldBe 1L
    }

    @Test
    fun `1_1_1_2_1 between - scopes the lines summed to times within the given range`() {
        calculate(BalanceOptions(from = from, to = to)) shouldBe usd(23)

        query().whereColumns shouldContainExactly listOf("account", "created_at", "scope")
        query().isSum shouldBe true
    }

    @Test
    fun `1_1_2_1 a single code - scopes the lines summed by the given code`() {
        calculate(BalanceOptions(code = "code1")) shouldBe usd(103)

        query().whereColumns shouldContainExactly listOf("account", "code", "scope")
        query().isSum shouldBe true
    }

    @Test
    fun `1_1_3_1 a list of codes - scopes the lines summed by the given codes`() {
        calculate(BalanceOptions(codes = listOf("code1", "code2"))) shouldBe usd(123)

        query().whereColumns shouldContainExactly listOf("account", "code", "scope")
        query().isSum shouldBe true
    }

    @Test
    fun `1_1_4_1 no codes - does not scope the lines summed by any code`() {
        calculate(BalanceOptions()) shouldBe usd(4123)

        query().whereColumns shouldContainExactly listOf("account", "scope")
        query().isSum shouldBe false
        query().limit shouldBe 1L
    }

    @Test
    fun `codes are codes plus code without nulls`() {
        BalanceOptions(code = "a", codes = listOf("b", "c")).effectiveCodes shouldContainExactly listOf("b", "c", "a")
        BalanceOptions(codes = emptyList()).effectiveCodes shouldContainExactly emptyList()
        BalanceOptions().hasCodes shouldBe false
        BalanceOptions(from = from).isBetween shouldBe false
        BalanceOptions(at = at, from = from, to = to).isSum shouldBe false
        BalanceOptions.of().at.shouldBeNull()
    }
}
