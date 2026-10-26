package com.softberries.doubleentry.balance

import com.fasterxml.jackson.databind.node.ObjectNode
import com.softberries.doubleentry.config.DoubleEntryTables
import com.softberries.doubleentry.parity.OracleScenarios
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Seeds `double_entry_lines` (and `double_entry_account_balances`) with the `db_after` rows of an oracle scenario,
 * inserted directly with their ids and `created_at`, so balance tests do not depend on transfers (M-009 notes).
 * A balance query writes nothing, so `db_after` is the state the Ruby query saw.
 */
class LineFixtures(
    private val jdbcTemplate: JdbcTemplate,
) {
    /** Inserts the rows of [scenarioId]'s `db_after`; returns the number of lines. */
    fun load(scenarioId: String): Int {
        val dbAfter = OracleScenarios.load(scenarioId).dbAfter
        for (table in listOf(DoubleEntryTables.LINES, DoubleEntryTables.ACCOUNT_BALANCES)) {
            dbAfter[table].orEmpty().forEach { insert(table, it) }
            jdbcTemplate.execute("SELECT setval(pg_get_serial_sequence('$table', 'id'), coalesce(max(id), 0) + 1, false) FROM $table")
        }
        return dbAfter[DoubleEntryTables.LINES].orEmpty().size
    }

    /** Inserts one line of [account] (scope identity [scope]) with the given values; the id comes from the sequence. */
    fun line(
        account: String,
        scope: String?,
        code: String,
        amount: Long,
        balance: Long,
        createdAt: Instant,
    ) {
        val timestamp = LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC).toString().replace('T', ' ')
        jdbcTemplate.update(
            "INSERT INTO ${DoubleEntryTables.LINES} (account, scope, code, amount, balance, partner_account, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, 'bank', CAST(? AS timestamp), CAST(? AS timestamp))",
            account,
            scope,
            code,
            amount,
            balance,
            timestamp,
            timestamp,
        )
    }

    private fun insert(
        table: String,
        row: ObjectNode,
    ) {
        val columns = row.fieldNames().asSequence().toList()
        val placeholders =
            columns.map { column ->
                when (column) {
                    "created_at", "updated_at" -> "CAST(? AS timestamp)"
                    "metadata" -> "CAST(? AS jsonb)"
                    else -> "?"
                }
            }
        val values =
            columns.map { column ->
                val node = row[column]
                when {
                    node.isNull -> null
                    column == "created_at" || column == "updated_at" -> node.asText().removeSuffix("Z").replace('T', ' ')
                    column == "metadata" -> node.toString()
                    node.isIntegralNumber -> node.asLong()
                    else -> node.asText()
                }
            }
        jdbcTemplate.update(
            "INSERT INTO $table (${columns.joinToString()}) VALUES (${placeholders.joinToString()})",
            *values.toTypedArray(),
        )
    }
}
