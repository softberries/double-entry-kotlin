package com.softberries.doubleentry.parity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.sql.DataSource

/**
 * Reads the double_entry tables after a Kotlin run in the oracle's `db_after` format (`tools/oracle.rb` `rows`):
 * rows ordered by id, timestamps as UTC ISO-8601 with 6 fractional digits, jsonb as JSON, integers as numbers.
 */
object DbSnapshot {
    /** The tables of every json-mode `db_after` (the legacy `double_entry_line_metadata` is X-02). */
    val TABLES = listOf("double_entry_lines", "double_entry_account_balances", "double_entry_line_checks")

    private val nodes = JsonNodeFactory.instance
    private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

    fun read(
        dataSource: DataSource,
        tables: List<String> = TABLES,
    ): Map<String, List<ObjectNode>> = dataSource.connection.use { read(it, tables) }

    fun read(
        connection: Connection,
        tables: List<String> = TABLES,
    ): Map<String, List<ObjectNode>> =
        tables.associateWith { table ->
            require(table.matches(Regex("[a-z_]+"))) { "invalid table name $table" }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM $table ORDER BY id").use { rows ->
                    generateSequence { if (rows.next()) row(rows) else null }.toList()
                }
            }
        }

    fun formatInstant(instant: Instant): String = iso.format(instant)

    private fun row(rs: ResultSet): ObjectNode {
        val meta = rs.metaData
        val row = nodes.objectNode()
        for (i in 1..meta.columnCount) {
            row.set<JsonNode>(meta.getColumnLabel(i), value(rs, i, meta.getColumnTypeName(i).lowercase()))
        }
        return row
    }

    private fun value(
        rs: ResultSet,
        i: Int,
        type: String,
    ): JsonNode {
        if (rs.getObject(i) == null) return nodes.nullNode()
        return when (type) {
            "json", "jsonb" -> OracleScenarios.mapper.readTree(rs.getString(i))
            "timestamp" -> nodes.textNode(formatInstant(rs.getObject(i, LocalDateTime::class.java).toInstant(ZoneOffset.UTC)))
            "timestamptz" -> nodes.textNode(formatInstant(rs.getObject(i, OffsetDateTime::class.java).toInstant()))
            "int2", "int4", "int8", "serial", "bigserial" -> nodes.numberNode(rs.getLong(i))
            "bool" -> nodes.booleanNode(rs.getBoolean(i))
            else -> nodes.textNode(rs.getString(i))
        }
    }
}
