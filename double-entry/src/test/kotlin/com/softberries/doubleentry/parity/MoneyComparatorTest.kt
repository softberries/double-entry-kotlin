package com.softberries.doubleentry.parity

import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MoneyComparatorTest {
    private fun observed(money: Money) = ObservedMoney(money.minorUnits, money.currency.code, money.toString())

    private val expected = OracleScenarios.load("balance_between_inclusive").result!!

    @Test
    fun `balance_between_inclusive result matches Money(-25000, USD)`() {
        expected["money"]["fractional"].longValue() shouldBe -25000
        expected["money"]["to_s"].asText() shouldBe "-250.00"

        MoneyComparator.compare(expected, observed(Money(-25000, CurrencyUnit.of("USD")))).matches shouldBe true
        MoneyComparator.compare(expected["money"], observed(Money(-25000, CurrencyUnit.of("USD")))).matches shouldBe true
    }

    @Test
    fun `different minor units, currency or to_s are mismatches`() {
        MoneyComparator.compare(expected, observed(Money(-25001, CurrencyUnit.of("USD")))).differences shouldHaveSize 2
        MoneyComparator.compare(expected, ObservedMoney(-25000, "AUD", "-250.00")).differences.single() shouldBe
            "currency: expected <USD> but was <AUD>"
        MoneyComparator.compare(expected, ObservedMoney(-25000, "USD", "-250")).differences.single() shouldBe
            "to_s: expected <-250.00> but was <-250>"
    }

    @Test
    fun `a non-money oracle value is a mismatch`() {
        MoneyComparator.compare(OracleScenarios.load("transfer_scoped_basic").result!!, ObservedMoney(0, "USD", "0.00")).matches shouldBe
            false
    }
}
