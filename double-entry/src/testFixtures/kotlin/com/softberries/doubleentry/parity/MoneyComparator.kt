package com.softberries.doubleentry.parity

import com.fasterxml.jackson.databind.JsonNode

/**
 * The parts of a Kotlin `Money` the oracle records: minor units, ISO currency code and `toString()`. The parity
 * driver builds it from the library type (`ObservedMoney(m.minorUnits, m.currency.code, m.toString())`), so this
 * harness does not depend on the money package.
 */
data class ObservedMoney(
    val fractional: Long,
    val currency: String,
    val toS: String,
)

/**
 * Compares an oracle `{"money": {"fractional", "currency", "to_s", "format"}}` (or the inner object) with an
 * [ObservedMoney]. `format` (`$-250.00`, money gem `Money#format`) is not part of the library API and is ignored.
 */
object MoneyComparator {
    fun compare(
        expected: JsonNode,
        actual: ObservedMoney,
    ): Comparison {
        val money = if (expected.has("money")) expected["money"] else expected
        if (!money.has("fractional") || !money.has("currency") || !money.has("to_s")) {
            return Comparison.of("not an oracle money value: $expected")
        }
        val fractional = money["fractional"]
        return Comparison.of(
            "fractional: expected <${fractional.asText()}> but was <${actual.fractional}>"
                .takeUnless { fractional.isIntegralNumber && fractional.longValue() == actual.fractional },
            "currency: expected <${money["currency"].asText()}> but was <${actual.currency}>"
                .takeUnless { money["currency"].asText() == actual.currency },
            "to_s: expected <${money["to_s"].asText()}> but was <${actual.toS}>"
                .takeUnless { money["to_s"].asText() == actual.toS },
        )
    }
}
