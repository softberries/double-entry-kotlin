package com.softberries.doubleentry.money

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * A currency of the money gem 7.1.1 table (`Money::Currency`), see [of].
 *
 * Identity is the [code]: the table key upper-cased, as `Money::Currency#to_s` (`'yen'` is `YEN`, its [isoCode] is
 * `JPY`, and it is a different currency from `JPY`, as in Ruby).
 */
class CurrencyUnit private constructor(
    val code: String,
    val isoCode: String,
    val name: String,
    val subunitToUnit: Long,
) {
    /** Digits after the decimal mark: `Math.log10(subunit_to_unit).round` (`Money::Currency#decimal_places`). */
    val decimalPlaces: Int = log10(subunitToUnit.toDouble()).roundToInt()

    override fun equals(other: Any?): Boolean = this === other || (other is CurrencyUnit && code == other.code)

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = code

    companion object {
        private const val RESOURCE = "/money/currencies.json"

        /**
         * Every currency the gem loads (`Money::Currency::Loader`): `currency_iso.json`, `currency_non_iso.json` and
         * `currency_backwards_compatible.json` of money 7.1.1, merged in that order into `money/currencies.json`.
         */
        private val registry: Map<String, CurrencyUnit> by lazy { load() }

        val USD: CurrencyUnit get() = of("USD")
        val EUR: CurrencyUnit get() = of("EUR")
        val JPY: CurrencyUnit get() = of("JPY")
        val BTC: CurrencyUnit get() = of("BTC")

        /**
         * Looks a currency up case-insensitively, like `Money::Currency.new(id)` (`id.to_s.downcase`). A leading `:`
         * is accepted, so a Ruby symbol literal such as `:btc` (`account_spec.rb` [1:3:2]) resolves too.
         *
         * @throws UnknownCurrencyException if the code is not in the table.
         */
        fun of(code: String): CurrencyUnit {
            val key = code.removePrefix(":").lowercase()
            return registry[key] ?: throw UnknownCurrencyException(key)
        }

        /** All registered currencies, keyed by [code]. */
        fun all(): Collection<CurrencyUnit> = registry.values

        private fun load(): Map<String, CurrencyUnit> {
            val stream =
                requireNotNull(CurrencyUnit::class.java.getResourceAsStream(RESOURCE)) { "missing resource $RESOURCE" }
            val table = stream.use { ObjectMapper().readTree(it) }
            return table
                .properties()
                .associate { (key, data) ->
                    key to
                        CurrencyUnit(
                            code = key.uppercase(),
                            isoCode = data.path("iso_code").asText(key.uppercase()),
                            name = data.path("name").asText(),
                            subunitToUnit = data.path("subunit_to_unit").asLong(),
                        )
                }
        }
    }
}
