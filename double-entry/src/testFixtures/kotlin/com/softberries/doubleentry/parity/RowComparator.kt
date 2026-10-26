package com.softberries.doubleentry.parity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant

/**
 * Compares an oracle `db_after` with the rows of a Kotlin run ([DbSnapshot]).
 *
 * - Rows are matched by table and `id`; missing and unexpected rows are differences.
 * - Every non-timestamp column (`account`, `scope`, `code`, `amount`, `balance`, `partner_*`, `detail_*`,
 *   `errors_found`, `last_line_id`, `log`, …) must be equal. `metadata` (jsonb) is compared semantically (the oracle holds the raw jsonb text, the
 *   snapshot parsed JSON; both are parsed): key order
 *   and integer width do not matter.
 * - **Timestamps** (`created_at`, `updated_at`) are wall-clock in most scenarios, so exact comparison would be flaky.
 *   An expected value within [FROZEN_WINDOW] of the frozen time T0 (`2026-01-15T10:00:00Z`, `tools/oracle.rb`) was
 *   written under `Timecop.freeze` (the oracle freezes between `T0 - 21.days` and `T0 + 1.hour`,
 *   `tools/oracle.rb:323-439`) and is compared exactly. Wall-clock values are compared by pattern only:
 *   within a row the relation of `updated_at` to `created_at` (equal or later) must be the same, and across all
 *   wall-clock values of the snapshot the order must not be inverted (expected a < b ⇒ actual a ≤ b).
 */
object RowComparator {
    val T0: Instant = Instant.parse("2026-01-15T10:00:00Z")

    /** Covers every `Timecop.freeze` of `tools/oracle.rb` (earliest `T0 - 21.days`); wall-clock values are months later. */
    val FROZEN_WINDOW: Duration = Duration.ofDays(30)
    val TIMESTAMP_COLUMNS = setOf("created_at", "updated_at")
    val JSON_COLUMNS = setOf("metadata")
    private const val MAX_ORDER_DIFFERENCES = 10

    private data class Stamp(
        val where: String,
        val expected: Instant,
        val actual: Instant,
    )

    fun compare(
        expected: Map<String, List<ObjectNode>>,
        actual: Map<String, List<ObjectNode>>,
    ): Comparison {
        val differences = mutableListOf<String>()
        val wallClock = mutableListOf<Stamp>()
        for (table in (expected.keys + actual.keys).sorted()) {
            val expectedRows = expected[table]
            val actualRows = actual[table]
            if (expectedRows == null || actualRows == null) {
                differences += "$table: table ${if (expectedRows == null) "not in oracle" else "missing in Kotlin snapshot"}"
                continue
            }
            val actualById = actualRows.associateBy { it["id"].asLong() }
            val expectedById = expectedRows.associateBy { it["id"].asLong() }
            expectedById.keys.minus(actualById.keys).forEach { differences += "$table id=$it: row missing" }
            actualById.keys.minus(expectedById.keys).forEach { differences += "$table id=$it: unexpected row" }
            for ((id, row) in expectedById) {
                val other = actualById[id] ?: continue
                differences += compareRow(table, id, row, other, wallClock)
            }
        }
        differences += orderDifferences(wallClock)
        return Comparison(differences)
    }

    private fun compareRow(
        table: String,
        id: Long,
        expected: ObjectNode,
        actual: ObjectNode,
        wallClock: MutableList<Stamp>,
    ): List<String> {
        val differences = mutableListOf<String>()
        val columns = (expected.fieldNames().asSequence() + actual.fieldNames().asSequence()).toSortedSet()
        val stamps = mutableMapOf<String, Stamp>()
        for (column in columns) {
            val e = expected[column]
            val a = actual[column]
            val where = "$table id=$id $column"
            when {
                e == null || a == null -> differences += "$where: column ${if (e == null) "not in oracle" else "missing"}"
                column in TIMESTAMP_COLUMNS && !e.isNull && !a.isNull -> {
                    val stamp = Stamp(where, Instant.parse(e.asText()), Instant.parse(a.asText()))
                    if (Duration.between(T0, stamp.expected).abs() <= FROZEN_WINDOW) {
                        if (stamp.expected != stamp.actual) differences += "$where: expected <${e.asText()}> but was <${a.asText()}>"
                    } else {
                        stamps[column] = stamp
                        wallClock += stamp
                    }
                }
                column in JSON_COLUMNS && semanticEquals(json(e), json(a)) -> Unit
                !semanticEquals(e, a) -> differences += "$where: expected <$e> but was <$a>"
            }
        }
        val created = stamps["created_at"]
        val updated = stamps["updated_at"]
        if (created != null && updated != null) {
            val expectedRelation = updated.expected.compareTo(created.expected).coerceIn(-1, 1)
            val actualRelation = updated.actual.compareTo(created.actual).coerceIn(-1, 1)
            if (expectedRelation != actualRelation) {
                differences +=
                    "$table id=$id updated_at vs created_at: expected ${relation(expectedRelation)} but was ${relation(actualRelation)}"
            }
        }
        return differences
    }

    private fun orderDifferences(stamps: List<Stamp>): List<String> =
        stamps
            .flatMap { a -> stamps.filter { b -> a.expected < b.expected && a.actual > b.actual }.map { b -> a to b } }
            .take(MAX_ORDER_DIFFERENCES)
            .map { (a, b) -> "timestamp order inverted: ${a.where} is before ${b.where} in the oracle but after it in Kotlin" }

    private fun relation(sign: Int) =
        when (sign) {
            0 -> "equal"
            1 -> "later"
            else -> "earlier"
        }

    private fun json(node: JsonNode): JsonNode = if (node.isTextual) OracleScenarios.mapper.readTree(node.asText()) else node

    /** JSON equality that ignores object key order (Jackson default) and numeric node types (1 == 1L == 1.0). */
    fun semanticEquals(
        expected: JsonNode,
        actual: JsonNode,
    ): Boolean =
        expected.equals(
            { a, b ->
                if (a.isNumber && b.isNumber && a.decimalValue().compareTo(b.decimalValue()) == 0) {
                    0
                } else if (a == b) {
                    0
                } else {
                    1
                }
            },
            actual,
        )
}
