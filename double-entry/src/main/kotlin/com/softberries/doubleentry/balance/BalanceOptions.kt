package com.softberries.doubleentry.balance

import java.time.Instant
import java.time.OffsetDateTime

/**
 * Filters of a balance query (`BalanceCalculator::Options`, `balance_calculator.rb:51-77`). All fields are optional;
 * without any of them the query answers the current balance (BR-040).
 *
 * Times are [Instant]s, so a value given with an offset compares as the same UTC instant (BR-046); see [of] for
 * [OffsetDateTime] input.
 */
data class BalanceOptions(
    /** Balance as of this time, inclusive (BR-042). Beats [from]/[to] (BR-044). */
    val at: Instant? = null,
    /** Start of a sum window, inclusive; only used together with [to] (BR-043). */
    val from: Instant? = null,
    /** End of a sum window, inclusive; only used together with [from] (BR-043). */
    val to: Instant? = null,
    val code: String? = null,
    val codes: List<String>? = null,
) {
    /** `codes.to_a << code` without nulls (BR-045). */
    val effectiveCodes: List<String> get() = codes.orEmpty() + listOfNotNull(code)

    /** `at?`. */
    val hasAt: Boolean get() = at != null

    /** `between?`: both ends present and no [at] (BR-043, BR-044). */
    val isBetween: Boolean get() = from != null && to != null && at == null

    /** `code?`: a non-empty code list (BR-045). */
    val hasCodes: Boolean get() = effectiveCodes.isNotEmpty()

    /** True when the answer is a `SUM(amount)` rather than the running balance of the latest line. */
    val isSum: Boolean get() = isBetween || hasCodes

    companion object {
        /** [BalanceOptions] from offset date-times; they are compared in UTC (BR-046). */
        fun of(
            at: OffsetDateTime? = null,
            from: OffsetDateTime? = null,
            to: OffsetDateTime? = null,
            code: String? = null,
            codes: List<String>? = null,
        ): BalanceOptions = BalanceOptions(at?.toInstant(), from?.toInstant(), to?.toInstant(), code, codes)
    }
}
