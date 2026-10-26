package com.softberries.doubleentry.config

/**
 * Fixed table names (BR-102). The Ruby gem derives them from `DoubleEntry.table_name_prefix`
 * (`lib/double_entry.rb:168-170`); the prefix is not configurable in the Kotlin port.
 * Entities use these constants in their `@Table` annotations.
 */
object DoubleEntryTables {
    const val PREFIX = "double_entry_"
    const val LINES = "double_entry_lines"
    const val ACCOUNT_BALANCES = "double_entry_account_balances"
    const val LINE_CHECKS = "double_entry_line_checks"

    /** In the order of `oracle/api/json/config_defaults.json` (`tables`). */
    val ALL: List<String> = listOf(LINES, ACCOUNT_BALANCES, LINE_CHECKS)
}
