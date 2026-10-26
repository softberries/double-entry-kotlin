package com.softberries.doubleentry.parity

/**
 * The five parity areas (backlog M-018..M-022). Every json oracle scenario belongs to exactly one area, selected by
 * its id prefix; each area has its own deviation file and report, so parity units never edit a shared file.
 */
enum class ParityArea(
    /** File stem of `parity/<key>.deviations.json` and `build/reports/parity/<key>.md`. */
    val key: String,
    private val prefixes: List<String>,
) {
    CONFIG_AND_ACCOUNTS("config-and-accounts", listOf("config_", "account_")),
    TRANSFERS_AND_LINES("transfers-and-lines", listOf("transfer_", "line_")),
    BALANCES("balances", listOf("balance_")),
    LOCKING("locking", listOf("lock_", "compat_")),
    VALIDATION("validation", listOf("linecheck_", "accountfixer_")),
    ;

    fun owns(scenarioId: String): Boolean = prefixes.any { scenarioId.startsWith(it) }

    /** The ids of the json scenarios of this area, sorted. */
    val scenarioIds: List<String> get() = OracleScenarios.ids.filter(::owns)

    companion object {
        fun of(scenarioId: String): ParityArea =
            entries.singleOrNull { it.owns(scenarioId) } ?: error("scenario $scenarioId belongs to no single parity area")
    }
}
