package com.softberries.doubleentry.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An amount in integer minor units of one currency (D-006); the port of the money gem's `Money` as the ledger uses it.
 *
 * Amounts and balances are stored as bigint minor units (`Money#fractional`), so the range is that of [Long]. Arithmetic
 * is exact: overflow throws [ArithmeticException] instead of writing a wrapped value (BR-032, D-013). Arithmetic and
 * comparison across currencies throw [MoneyCurrencyMismatchException]; there is no exchange (X-07).
 */
class Money(
    val minorUnits: Long,
    val currency: CurrencyUnit,
) : Comparable<Money> {
    val isNegative: Boolean get() = minorUnits < 0

    val isZero: Boolean get() = minorUnits == 0L

    val isPositive: Boolean get() = minorUnits > 0

    operator fun plus(other: Money): Money = Money(Math.addExact(minorUnits, sameCurrency(other).minorUnits), currency)

    operator fun minus(other: Money): Money = Money(Math.subtractExact(minorUnits, sameCurrency(other).minorUnits), currency)

    operator fun unaryMinus(): Money = Money(Math.negateExact(minorUnits), currency)

    override fun compareTo(other: Money): Int = minorUnits.compareTo(sameCurrency(other).minorUnits)

    /**
     * Strict equality: same minor units and same currency. Unlike money 7.1.1 with `strict_eql_compare = false`,
     * zero in one currency is not equal to zero in another (the gem deprecates that behaviour).
     */
    override fun equals(other: Any?): Boolean =
        this === other || (other is Money && minorUnits == other.minorUnits && currency == other.currency)

    override fun hashCode(): Int = 31 * minorUnits.hashCode() + currency.hashCode()

    /**
     * `Money#to_s` as it appears in the gem's messages (BR-095, BR-012): no symbol, `.` as decimal mark, no thousands
     * separator, [CurrencyUnit.decimalPlaces] digits, e.g. `-99.99`, `1000.00`, `-0.00010000` (BTC), `0` (JPY).
     */
    override fun toString(): String {
        // Formatter#extract_whole_and_decimal_parts: |fractional| / subunit_to_unit as BigDecimal#to_s("F").
        // Every subunit_to_unit in the table is 2^a * 5^b, so the division is exact.
        val units = BigDecimal.valueOf(minorUnits).abs().divide(BigDecimal.valueOf(currency.subunitToUnit))
        val parts = units.stripTrailingZeros().toPlainString().split('.')
        val whole = parts[0]
        val sign = if (isNegative) "-" else ""
        if (currency.decimalPlaces == 0) return sign + whole
        // Formatter#format_decimal_part pads on the right and never truncates.
        val decimals = parts.getOrElse(1) { "0" }.padEnd(currency.decimalPlaces, '0')
        return "$sign$whole.$decimals"
    }

    private fun sameCurrency(other: Money): Money {
        if (currency != other.currency) throw MoneyCurrencyMismatchException(currency, other.currency)
        return other
    }

    companion object {
        /** `Money.zero(currency)`, e.g. the balance of an account without lines (BR-041). */
        fun zero(currency: CurrencyUnit): Money = Money(0, currency)

        /** `Money.new(fractional, currency)` with integer minor units, e.g. a bigint column value. */
        fun ofMinor(
            minorUnits: Long,
            currency: CurrencyUnit,
        ): Money = Money(minorUnits, currency)

        /**
         * `Money.new(fractional, currency)` with a fractional minor amount: rounded HALF_EVEN to whole minor units
         * (BR-032, D-006), so `10.5` → `10` and `11.5` → `12`.
         *
         * @throws ArithmeticException if the rounded amount does not fit a bigint.
         */
        fun ofMinorRounded(
            minorUnits: BigDecimal,
            currency: CurrencyUnit,
        ): Money = Money(minorUnits.setScale(0, RoundingMode.HALF_EVEN).longValueExact(), currency)
    }
}
