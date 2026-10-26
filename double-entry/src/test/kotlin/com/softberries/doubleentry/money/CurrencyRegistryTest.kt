package com.softberries.doubleentry.money

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** The currency table ported from money gem 7.1.1 (D-006). No Ruby spec covers it (`docs/80-tests.md` §3.1). */
class CurrencyRegistryTest {
    @ParameterizedTest
    @CsvSource(
        "USD, 100, 2",
        "EUR, 100, 2",
        "GBP, 100, 2",
        "JPY, 1, 0",
        "KWD, 1000, 3",
        "BTC, 100000000, 8",
        "MRU, 5, 1",
    )
    fun `subunit_to_unit and decimal places match the gem table`(
        code: String,
        subunitToUnit: Long,
        decimalPlaces: Int,
    ) {
        val currency = CurrencyUnit.of(code)

        currency.code shouldBe code
        currency.subunitToUnit shouldBe subunitToUnit
        currency.decimalPlaces shouldBe decimalPlaces
    }

    @ParameterizedTest
    @ValueSource(strings = ["BTC", "btc", "Btc", ":btc", ":BTC"])
    fun `lookup is case-insensitive and accepts a Ruby symbol literal`(code: String) {
        CurrencyUnit.of(code) shouldBe CurrencyUnit.BTC
        CurrencyUnit.of(code).toString() shouldBe "BTC"
    }

    @ParameterizedTest
    @ValueSource(strings = ["XYZ", "", ":", "US D"])
    fun `an unknown code throws UnknownCurrencyException`(code: String) {
        val error = shouldThrow<UnknownCurrencyException> { CurrencyUnit.of(code) }
        error.message shouldBe "Unknown currency '${code.removePrefix(":").lowercase()}'"
    }

    @Test
    fun `the gem message names the down-cased code`() {
        shouldThrow<UnknownCurrencyException> { CurrencyUnit.of("FOO") }.message shouldBe "Unknown currency 'foo'"
    }

    @Test
    fun `the registry holds every currency the gem loads (iso, non-iso, backwards compatible)`() {
        // 173 ISO + 9 non-ISO + 14 backwards-compatible entries in money 7.1.1, no key collisions.
        CurrencyUnit.all() shouldHaveSize 196
        CurrencyUnit.of("USDC").subunitToUnit shouldBe 1_000_000
        CurrencyUnit.of("EEK").subunitToUnit shouldBe 100
    }

    @Test
    fun `a backwards-compatible alias is its own currency, as in Ruby`() {
        val yen = CurrencyUnit.of("yen")

        yen.code shouldBe "YEN"
        yen.isoCode shouldBe "JPY"
        yen shouldNotBe CurrencyUnit.JPY
    }

    @Test
    fun `equality and hash code follow the code`() {
        CurrencyUnit.of("usd") shouldBe CurrencyUnit.of("USD")
        CurrencyUnit.of("usd").hashCode() shouldBe CurrencyUnit.USD.hashCode()
        CurrencyUnit.USD shouldNotBe CurrencyUnit.EUR
    }
}
