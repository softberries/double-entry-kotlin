package com.softberries.doubleentry.parity

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.softberries.doubleentry.testing.MigrationSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class RowComparatorTest {
    private fun copy(id: String): Map<String, List<ObjectNode>> =
        OracleScenarios.load(id).dbAfter.mapValues { (_, rows) -> rows.map { it.deepCopy() } }

    private fun Map<String, List<ObjectNode>>.row(
        table: String,
        id: Long,
    ): ObjectNode = getValue(table).single { it["id"].asLong() == id }

    private fun shiftWallClock(
        snapshot: Map<String, List<ObjectNode>>,
        by: Duration,
    ) = snapshot.values.flatten().forEach { row ->
        RowComparator.TIMESTAMP_COLUMNS.forEach { column ->
            row.put(column, DbSnapshot.formatInstant(Instant.parse(row[column].asText()).plus(by)))
        }
    }

    @Test
    fun `transfer_scoped_basic db_after matches itself`() {
        val expected = OracleScenarios.load("transfer_scoped_basic").dbAfter

        RowComparator.compare(expected, copy("transfer_scoped_basic")).matches shouldBe true
    }

    @Test
    fun `every oracle db_after matches itself`() {
        OracleScenarios.all().forEach { scenario ->
            RowComparator.compare(scenario.dbAfter, copy(scenario.id)).assertMatches(scenario.id)
        }
    }

    @Test
    fun `a changed balance is a mismatch naming table, id and column`() {
        val actual = copy("transfer_scoped_basic")
        actual.row("double_entry_lines", 2).put("balance", 999)

        val result = RowComparator.compare(OracleScenarios.load("transfer_scoped_basic").dbAfter, actual)

        result.matches shouldBe false
        result.differences shouldHaveSize 1
        result.differences.single() shouldBe "double_entry_lines id=2 balance: expected <1000> but was <999>"
    }

    @Test
    fun `missing and unexpected rows are mismatches`() {
        val actual = copy("transfer_scoped_basic").toMutableMap()
        actual["double_entry_lines"] = actual.getValue("double_entry_lines").take(1)
        actual["double_entry_line_checks"] = listOf(copy("linecheck_consistent").getValue("double_entry_line_checks").single())

        val result = RowComparator.compare(OracleScenarios.load("transfer_scoped_basic").dbAfter, actual)

        result.differences shouldContain "double_entry_lines id=2: row missing"
        result.differences shouldContain "double_entry_line_checks id=1: unexpected row"
    }

    @Test
    fun `metadata is compared as JSON, ignoring key order and number width`() {
        val actual = copy("transfer_with_metadata")
        val reordered =
            JsonNodeFactory.instance.objectNode().apply {
                put("country", "AU")
                set<ObjectNode>("nested", JsonNodeFactory.instance.objectNode().put("a", 1L))
                putArray("tax").add("GST").add("VAT")
                put("n", 1L)
            }
        actual.row("double_entry_lines", 1).set<ObjectNode>("metadata", reordered)
        val expected = OracleScenarios.load("transfer_with_metadata").dbAfter

        RowComparator.compare(expected, actual).matches shouldBe true

        // The oracle holds the raw jsonb text; array order is significant.
        actual.row("double_entry_lines", 2).put("metadata", """{"n": 1, "tax": ["VAT", "GST"], "nested": {"a": 1}, "country": "AU"}""")
        RowComparator.compare(expected, actual).differences.single() shouldContain "double_entry_lines id=2 metadata"
    }

    @Test
    fun `line check columns are compared`() {
        val actual = copy("linecheck_line_error_with_fixer")
        actual.row("double_entry_line_checks", 1).put("errors_found", false).put("log", "")

        val differences = RowComparator.compare(OracleScenarios.load("linecheck_line_error_with_fixer").dbAfter, actual).differences

        differences shouldHaveSize 2
        differences[0] shouldContain "double_entry_line_checks id=1 errors_found"
        differences[1] shouldContain "double_entry_line_checks id=1 log"
    }

    @Test
    fun `wall-clock timestamps are compared by pattern, so a later run matches`() {
        val actual = copy("transfer_scoped_basic")
        shiftWallClock(actual, Duration.ofDays(400).plusMillis(123))

        RowComparator.compare(OracleScenarios.load("transfer_scoped_basic").dbAfter, actual).matches shouldBe true
    }

    @Test
    fun `wall-clock rows keep the relation of updated_at to created_at`() {
        val actual = copy("transfer_scoped_basic")
        val line1 = actual.row("double_entry_lines", 1)
        line1.put("updated_at", line1["created_at"].asText())

        RowComparator.compare(OracleScenarios.load("transfer_scoped_basic").dbAfter, actual).differences shouldContain
            "double_entry_lines id=1 updated_at vs created_at: expected later but was equal"
    }

    @Test
    fun `an inverted wall-clock order across rows is a mismatch, equal values are allowed`() {
        val expected = OracleScenarios.load("transfer_scoped_basic").dbAfter
        val sameInstant = copy("transfer_scoped_basic")
        // Both balance rows created at the same instant: allowed (expected a < b, actual a == b).
        sameInstant
            .row(
                "double_entry_account_balances",
                2,
            ).put("created_at", sameInstant.row("double_entry_account_balances", 1)["created_at"].asText())
        RowComparator.compare(expected, sameInstant).matches shouldBe true

        val inverted = copy("transfer_scoped_basic")
        inverted.row("double_entry_account_balances", 1).put("created_at", "2026-10-26T11:39:21.930000Z")
        RowComparator.compare(expected, inverted).differences.first() shouldContain
            "timestamp order inverted: double_entry_account_balances id=1 created_at is before double_entry_account_balances id=2 created_at"
    }

    @Test
    fun `frozen-time timestamps are compared exactly`() {
        val expected = OracleScenarios.load("linecheck_incremental").dbAfter
        val actual = copy("linecheck_incremental")
        actual.row("double_entry_line_checks", 2).put("created_at", "2026-01-15T11:00:00.000001Z")

        RowComparator.compare(expected, actual).differences.single() shouldBe
            "double_entry_line_checks id=2 created_at: expected <2026-01-15T11:00:00.000000Z> but was <2026-01-15T11:00:00.000001Z>"
    }

    @Test
    fun `timestamps frozen weeks before T0 are compared exactly`() {
        val expected = OracleScenarios.load("balance_at_between").dbAfter
        val actual = copy("balance_at_between")
        val line1 = actual.row("double_entry_lines", 1)
        line1["created_at"].asText() shouldBe "2025-12-25T10:00:00.000000Z"
        line1.put("created_at", "2025-12-26T10:00:00.000000Z").put("updated_at", "2025-12-26T10:00:00.000000Z")

        RowComparator.compare(expected, actual).differences shouldContain
            "double_entry_lines id=1 created_at: expected <2025-12-25T10:00:00.000000Z> but was <2025-12-26T10:00:00.000000Z>"
    }

    @Test
    fun `every oracle timestamp is either frozen near T0 or wall-clock on the capture date`() {
        val captureDate =
            LocalDate.parse(
                Regex("^date: (\\d{4}-\\d{2}-\\d{2})", RegexOption.MULTILINE)
                    .find(MigrationSpec.read("oracle/environment.txt"))!!
                    .groupValues[1],
            )
        val unclassified =
            OracleScenarios.all().flatMap { scenario ->
                scenario.dbAfter.values.flatten().flatMap { row ->
                    RowComparator.TIMESTAMP_COLUMNS
                        .mapNotNull { row[it]?.takeUnless { node -> node.isNull }?.asText() }
                        .filterNot { text ->
                            val instant = Instant.parse(text)
                            Duration.between(RowComparator.T0, instant).abs() <= RowComparator.FROZEN_WINDOW ||
                                instant.atZone(ZoneOffset.UTC).toLocalDate() == captureDate
                        }.map { "${scenario.id}: $it" }
                }
            }

        withClue("timestamps neither frozen nor wall-clock") { unclassified.shouldBeEmpty() }
    }
}
