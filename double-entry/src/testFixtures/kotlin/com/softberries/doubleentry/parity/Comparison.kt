package com.softberries.doubleentry.parity

/** Outcome of a parity comparator: empty [differences] means the Kotlin side matches the oracle. */
data class Comparison(
    val differences: List<String>,
) {
    val matches: Boolean get() = differences.isEmpty()

    operator fun plus(other: Comparison): Comparison = Comparison(differences + other.differences)

    /** Throws an [AssertionError] listing every difference unless the comparison [matches]. */
    fun assertMatches(subject: String = "oracle comparison") {
        if (!matches) throw AssertionError("$subject: ${differences.size} difference(s)\n" + differences.joinToString("\n") { "  - $it" })
    }

    override fun toString(): String = if (matches) "match" else differences.joinToString("; ")

    companion object {
        val MATCH = Comparison(emptyList())

        fun of(vararg differences: String?): Comparison = Comparison(differences.filterNotNull())
    }
}
