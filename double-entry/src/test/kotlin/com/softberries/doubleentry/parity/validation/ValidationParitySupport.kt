package com.softberries.doubleentry.parity.validation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.softberries.doubleentry.config.DoubleEntryTables
import com.softberries.doubleentry.parity.Comparison
import com.softberries.doubleentry.parity.OracleScenarios
import com.softberries.doubleentry.parity.RowComparator
import com.softberries.doubleentry.validation.LineCheck
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** The oracle `ser` of an ActiveRecord LineCheck (`tools/oracle.rb:96`): record name and raw attributes. */
fun LineCheck.toOracleShape(): Map<String, Any?> =
    linkedMapOf(
        "record" to "DoubleEntry::Validation::LineCheck",
        "attributes" to
            linkedMapOf(
                "id" to id,
                "last_line_id" to lastLineId,
                "errors_found" to errorsFound,
                "log" to log,
                "created_at" to createdAt,
                "updated_at" to updatedAt,
            ),
    )

/** One record dump of a LineCheck log line: field name → value, in the order written. */
data class RecordDump(
    val fields: List<Pair<String, JsonNode>>,
) {
    operator fun get(name: String): JsonNode? = fields.firstOrNull { it.first == name }?.second
}

/** A dump line of the log: `[]`/`[record]` (the previous line) or a bare record (the checked line). */
data class DumpLine(
    val isList: Boolean,
    val records: List<RecordDump>,
)

/**
 * Compares a LineCheck `log` written by Ruby with one written by Kotlin (BR-095, X-06).
 *
 * - **Header lines** (the 33-`*` rules, `Error on line …`, `Error on account …`, blank separators) must be
 *   byte-identical, line by line.
 * - **Record dumps** (lines indented by two spaces) differ by design: Ruby writes `ActiveRecord#inspect`
 *   (`#<DoubleEntry::Line id: 1, account: "bank", scope: nil, …>`), Kotlin the documented `Line(id: 1, …)`
 *   rendering. Both are parsed and compared **field by field**: same fields in the same order, equal `id`,
 *   `account`, `scope`, `code`, `amount`, `balance`, `partner_*`, `detail_*` and `metadata`. Timestamps written under
 *   frozen time are compared exactly; wall-clock ones (as [RowComparator] does) by pattern: the Kotlin `created_at` must
 *   be the stored `created_at` of that line, and `updated_at` must relate to `created_at` (equal or later) as in Ruby.
 */
object LineCheckLogComparator {
    private val nodes = JsonNodeFactory.instance
    private val RUBY_RECORD = Regex("^#<DoubleEntry::Line (.*)>$")
    private val KOTLIN_RECORD = Regex("^Line\\((.*)\\)$")
    private val RUBY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS Z")
    private const val INDENT = "  "

    fun compare(
        where: String,
        expected: String?,
        actual: String?,
        kotlinLines: Map<Long, ObjectNode>,
    ): Comparison {
        if (expected == null || actual == null) {
            return Comparison.of("$where: expected <$expected> but was <$actual>".takeUnless { expected == actual })
        }
        val want = expected.split("\n")
        val got = actual.split("\n")
        if (want.size != got.size) return Comparison.of("$where: expected ${want.size} log lines but was ${got.size}")
        val differences = mutableListOf<String>()
        want.zip(got).forEachIndexed { i, (e, a) ->
            val at = "$where line ${i + 1}"
            if (e.startsWith(INDENT)) {
                differences += compareDumps(at, parse(e, ruby = true), parse(a, ruby = false), kotlinLines)
            } else if (e != a) {
                differences += "$at: expected <$e> but was <$a>"
            }
        }
        return Comparison(differences)
    }

    /** Parses one dump line (two-space indent included); throws on a line in neither format. */
    fun parse(
        line: String,
        ruby: Boolean,
    ): DumpLine {
        require(line.startsWith(INDENT)) { "not a dump line: <$line>" }
        val body = line.removePrefix(INDENT)
        val isList = body.startsWith("[") && body.endsWith("]")
        val inner = if (isList) body.substring(1, body.length - 1) else body
        val records = if (isList && inner.isEmpty()) emptyList() else listOf(parseRecord(inner, ruby))
        return DumpLine(isList, records)
    }

    private fun parseRecord(
        text: String,
        ruby: Boolean,
    ): RecordDump {
        val pattern = if (ruby) RUBY_RECORD else KOTLIN_RECORD
        val body = requireNotNull(pattern.find(text)) { "not a ${if (ruby) "Ruby" else "Kotlin"} record dump: <$text>" }.groupValues[1]
        val fields = mutableListOf<Pair<String, JsonNode>>()
        var i = 0
        while (i < body.length) {
            val colon = body.indexOf(": ", i)
            require(colon > i) { "malformed record dump at $i: <$body>" }
            val name = body.substring(i, colon)
            val (raw, end) = token(body, colon + 2)
            fields += name to value(raw, ruby)
            i = if (body.startsWith(", ", end)) end + 2 else end
        }
        return RecordDump(fields)
    }

    /** The raw value starting at [start]: a quoted string, a `{…}` object, or everything up to the next `, `. */
    private fun token(
        s: String,
        start: Int,
    ): Pair<String, Int> {
        when (s[start]) {
            '"' -> {
                var i = start + 1
                while (s[i] != '"') i += if (s[i] == '\\') 2 else 1
                return s.substring(start, i + 1) to i + 1
            }
            '{' -> {
                var depth = 0
                var i = start
                var inString = false
                while (true) {
                    val c = s[i]
                    when {
                        inString && c == '\\' -> i++
                        c == '"' -> inString = !inString
                        !inString && c == '{' -> depth++
                        !inString && c == '}' -> if (--depth == 0) return s.substring(start, i + 1) to i + 1
                    }
                    i++
                }
            }
            else -> {
                val end = s.indexOf(", ", start).let { if (it < 0) s.length else it }
                return s.substring(start, end) to end
            }
        }
    }

    private fun value(
        raw: String,
        ruby: Boolean,
    ): JsonNode =
        when {
            raw == (if (ruby) "nil" else "null") -> nodes.nullNode()
            raw.startsWith("\"") -> OracleScenarios.mapper.readTree(raw)
            // Ruby renders a jsonb Hash as `{"k"=>"v"}`, Kotlin as compact JSON.
            raw.startsWith("{") -> OracleScenarios.mapper.readTree(if (ruby) raw.replace("=>", ":") else raw)
            raw.toLongOrNull() != null -> nodes.numberNode(raw.toLong())
            else -> nodes.textNode(raw)
        }

    private fun instant(node: JsonNode): Instant {
        val text = node.asText()
        return if (text.contains('T')) Instant.parse(text) else OffsetDateTime.parse(text, RUBY_TIME).toInstant()
    }

    private fun compareDumps(
        at: String,
        expected: DumpLine,
        actual: DumpLine,
        kotlinLines: Map<Long, ObjectNode>,
    ): List<String> {
        if (expected.isList != actual.isList || expected.records.size != actual.records.size) {
            return listOf("$at: expected ${shape(expected)} but was ${shape(actual)}")
        }
        return expected.records.zip(actual.records).flatMap { (e, a) -> compareRecord(at, e, a, kotlinLines) }
    }

    private fun shape(dump: DumpLine) = if (dump.isList) "a list of ${dump.records.size} record(s)" else "a bare record"

    private fun compareRecord(
        at: String,
        expected: RecordDump,
        actual: RecordDump,
        kotlinLines: Map<Long, ObjectNode>,
    ): List<String> {
        val names = expected.fields.map { it.first }
        if (names != actual.fields.map { it.first }) return listOf("$at: expected fields $names but was ${actual.fields.map { it.first }}")
        val differences = mutableListOf<String>()
        for (name in names.filterNot { it in RowComparator.TIMESTAMP_COLUMNS }) {
            val e = expected[name]!!
            val a = actual[name]!!
            if (!RowComparator.semanticEquals(e, a)) differences += "$at $name: expected <$e> but was <$a>"
        }
        val eCreated = instant(expected["created_at"]!!)
        val eUpdated = instant(expected["updated_at"]!!)
        val aCreated = instant(actual["created_at"]!!)
        val aUpdated = instant(actual["updated_at"]!!)
        if (Duration.between(RowComparator.T0, eCreated).abs() <= RowComparator.FROZEN_WINDOW) {
            if (eCreated != aCreated || eUpdated != aUpdated) {
                differences += "$at timestamps: expected <$eCreated, $eUpdated> but was <$aCreated, $aUpdated>"
            }
        } else {
            val stored = kotlinLines[actual["id"]!!.asLong()]?.get("created_at")?.asText()?.let(Instant::parse)
            if (stored != aCreated) differences += "$at created_at: <$aCreated> is not the stored created_at <$stored> of the line"
            if (eUpdated.compareTo(eCreated).coerceIn(-1, 1) != aUpdated.compareTo(aCreated).coerceIn(-1, 1)) {
                differences += "$at updated_at vs created_at: Ruby <$eCreated, $eUpdated>, Kotlin <$aCreated, $aUpdated>"
            }
        }
        return differences
    }

    /** The Kotlin snapshot's `double_entry_lines` rows by id. */
    fun linesById(snapshot: Map<String, List<ObjectNode>>): Map<Long, ObjectNode> =
        snapshot[DoubleEntryTables.LINES].orEmpty().associateBy { it["id"].asLong() }
}
