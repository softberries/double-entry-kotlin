package com.softberries.doubleentry.money

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.softberries.doubleentry.money.CurrencyUnit.Companion.BTC
import com.softberries.doubleentry.money.CurrencyUnit.Companion.EUR
import com.softberries.doubleentry.money.CurrencyUnit.Companion.JPY
import com.softberries.doubleentry.money.CurrencyUnit.Companion.USD
import com.softberries.doubleentry.testing.MigrationSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

/**
 * BR-032 (amount storage and rounding) and `Money#to_s` (BR-095, BR-012). No Ruby spec covers BR-032
 * (`docs/80-tests.md` §3.1); the values come from the oracle and from `line_check_spec.rb` [1:2:2:2:2:1].
 */
class MoneyTest {
    private val mapper = ObjectMapper()

    private fun oracle(name: String): JsonNode = mapper.readTree(MigrationSpec.read("oracle/api/json/$name.json"))

    /** Amount of the destination (`cash`) line in a transfer oracle sample. */
    private fun cashLineAmount(sample: String): Long =
        oracle(sample)["db_after"]["double_entry_lines"].single { it["account"].asText() == "cash" }["amount"].asLong()

    @ParameterizedTest
    @CsvSource(
        "10.5, 10",
        "11.5, 12",
        "-10.5, -10",
        "-11.5, -12",
        "10.4999, 10",
        "10.5001, 11",
        "12, 12",
    )
    fun `a fractional minor amount is rounded half-even`(
        fractional: String,
        expected: Long,
    ) {
        Money.ofMinorRounded(BigDecimal(fractional), USD) shouldBe Money(expected, USD)
    }

    @Test
    fun `rounding matches the oracle transfers of 10_5 and 11_5 cents`() {
        Money.ofMinorRounded(BigDecimal("10.5"), USD).minorUnits shouldBe cashLineAmount("transfer_fractional_cents")
        Money.ofMinorRounded(BigDecimal("11.5"), USD).minorUnits shouldBe cashLineAmount("transfer_fractional_cents_11_5")
    }

    @Test
    fun `a rounded amount beyond the bigint range throws ArithmeticException`() {
        shouldThrow<ArithmeticException> { Money.ofMinorRounded(BigDecimal("9223372036854775807.5"), USD) }
    }

    @ParameterizedTest
    @CsvSource(
        "-9999, USD, -99.99",
        "0, USD, 0.00",
        "-10000, USD, -100.00",
        "100000, USD, 1000.00",
        "123456789, USD, 1234567.89",
        "5, USD, 0.05",
        "-5, USD, -0.05",
        "-10000, BTC, -0.00010000",
        "-9999, BTC, -0.00009999",
        "10000, BTC, 0.00010000",
        "0, JPY, 0",
        "-1500, JPY, -1500",
        "1234, KWD, 1.234",
        "3, MRU, 0.6",
        "-9223372036854775807, USD, -92233720368547758.07",
        "-9223372036854775808, USD, -92233720368547758.08",
    )
    fun `toString reproduces Money#to_s`(
        minorUnits: Long,
        currency: String,
        expected: String,
    ) {
        Money(minorUnits, CurrencyUnit.of(currency)).toString() shouldBe expected
    }

    @Test
    fun `toString matches the line check log texts`() {
        val log = oracle("linecheck_line_error_with_fixer")["result"]["attributes"]["log"].asText()
        val line = "Error on line #1: balance:${Money(-9999, USD)} != ${Money.zero(USD)} + ${Money(-10000, USD)}"

        line shouldBe "Error on line #1: balance:-99.99 != 0.00 + -100.00"
        log shouldContain line
        log shouldContain "${Money(-10000, USD)} (cached balance) != ${Money(-9999, USD)} (running balance)"
    }

    @Test
    fun `zero is formatted per currency as in balance_no_lines`() {
        val result = oracle("balance_no_lines")["result"]
        for ((key, currency) in listOf("cash" to USD, "btc" to BTC, "jpy" to JPY)) {
            val money = result[key]["money"]
            val zero = Money.zero(currency)

            money["currency"].asText() shouldBe currency.code
            zero.minorUnits shouldBe money["fractional"].asLong()
            zero.toString() shouldBe money["to_s"].asText()
        }
        Money.zero(BTC).toString() shouldBe "0.00000000"
    }

    @Test
    fun `arithmetic within one currency`() {
        (Money(150, USD) + Money(-50, USD)) shouldBe Money(100, USD)
        (Money(150, USD) - Money(200, USD)) shouldBe Money(-50, USD)
        -Money(150, USD) shouldBe Money(-150, USD)
        Money(1, USD) shouldBeLessThan Money(2, USD)
    }

    @Test
    fun `arithmetic across currencies throws MoneyCurrencyMismatchException`() {
        shouldThrow<MoneyCurrencyMismatchException> { Money(1, USD) + Money(1, EUR) }
        shouldThrow<MoneyCurrencyMismatchException> { Money(1, USD) - Money(1, EUR) }
        shouldThrow<MoneyCurrencyMismatchException> { Money(0, USD) + Money(0, BTC) }
        val error = shouldThrow<MoneyCurrencyMismatchException> { Money(1, USD).compareTo(Money(1, EUR)) }
        error.left shouldBe USD
        error.right shouldBe EUR
    }

    @Test
    fun `overflow throws ArithmeticException instead of wrapping`() {
        shouldThrow<ArithmeticException> { Money(Long.MAX_VALUE, USD) + Money(1, USD) }
        shouldThrow<ArithmeticException> { Money(Long.MIN_VALUE, USD) - Money(1, USD) }
        shouldThrow<ArithmeticException> { -Money(Long.MIN_VALUE, USD) }
    }

    @Test
    fun `the bigint extremes of transfer_bigint_max are representable`() {
        val lines = oracle("transfer_bigint_max")["db_after"]["double_entry_lines"]
        val amounts = lines.map { it["amount"].asLong() }

        amounts shouldBe listOf(-9223372036854775807L, Long.MAX_VALUE)
        val debit = Money(-9223372036854775807L, USD)
        debit.minorUnits shouldBe amounts[0]
        (-debit).minorUnits shouldBe amounts[1]
        (debit + Money(Long.MAX_VALUE, USD)) shouldBe Money.zero(USD)
    }

    @Test
    fun `equality compares minor units and currency`() {
        Money(100, USD) shouldBe Money(100, USD)
        Money(100, USD).hashCode() shouldBe Money(100, USD).hashCode()
        Money(100, USD) shouldNotBe Money(101, USD)
        Money(100, USD) shouldNotBe Money(100, EUR)
        Money.zero(USD) shouldNotBe Money.zero(EUR)
    }

    @Test
    fun `sign predicates`() {
        Money(-1, USD).isNegative.shouldBeTrue()
        Money(-1, USD).isZero.shouldBeFalse()
        Money(-1, USD).isPositive.shouldBeFalse()
        Money.zero(USD).isZero.shouldBeTrue()
        Money.zero(USD).isNegative.shouldBeFalse()
        Money.zero(USD).isPositive.shouldBeFalse()
        Money(1, USD).isPositive.shouldBeTrue()
    }

    @Test
    fun `MoneyConverter round-trips bigint columns with the entity's currency`() {
        for (value in listOf(0L, -10000L, 9999L, Long.MAX_VALUE, -9223372036854775807L)) {
            val money = MoneyConverter.fromColumn(value, BTC)

            money shouldBe Money(value, BTC)
            MoneyConverter.toColumn(money) shouldBe value
        }
    }
}
