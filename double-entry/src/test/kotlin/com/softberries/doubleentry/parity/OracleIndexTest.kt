package com.softberries.doubleentry.parity

import com.softberries.doubleentry.testing.MigrationSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** The json-mode oracle loads completely (`oracle/capture_log.txt`: "json: 121 scenarios (42 raising)"). */
class OracleIndexTest {
    private val captureLog = Regex("json: (\\d+) scenarios \\((\\d+) raising\\)").find(MigrationSpec.read("oracle/capture_log.txt"))!!

    @Test
    fun `all 121 json scenarios load and 42 of them raise`() {
        val scenarios = OracleScenarios.all()

        scenarios shouldHaveSize 121
        scenarios.map { it.id }.toSet() shouldHaveSize 121
        scenarios.count { it.raises } shouldBe 42
        captureLog.groupValues[1].toInt() shouldBe scenarios.size
        captureLog.groupValues[2].toInt() shouldBe scenarios.count { it.raises }
    }

    @Test
    fun `the index lists the same scenarios with matching outcomes`() {
        OracleScenarios.index.map { it.id }.sorted() shouldContainExactly OracleScenarios.ids
        OracleScenarios.index.forEach { entry ->
            val scenario = OracleScenarios.load(entry.id)
            entry.outcome shouldBe (scenario.exception?.let { "raises ${it.`class`}" } ?: "returns")
            entry.file shouldBe "oracle/api/json/${entry.id}.json"
        }
    }

    @Test
    fun `every scenario has the three json-mode tables and no legacy metadata table`() {
        OracleScenarios.all().forEach { scenario ->
            scenario.dbAfter.keys shouldBe DbSnapshot.TABLES.toSet()
        }
    }

    @Test
    fun `fields are mapped`() {
        val scenario = OracleScenarios.load("transfer_scoped_basic")

        scenario.rules shouldContainExactly listOf("BR-010", "BR-011", "BR-012")
        scenario.result shouldNotBe null
        scenario.dbAfter.getValue("double_entry_lines") shouldHaveSize 2
        scenario.sql.first() shouldBe "TRANSACTION (0.8ms)  BEGIN"
        scenario.sqlTruncated shouldBe false
        OracleScenarios.load("transfer_not_allowed_code").exception shouldBe
            OracleException(
                "DoubleEntry::TransferNotAllowed",
                "[:bank, :cash, :nope]",
                listOf("DoubleEntry::TransferNotAllowed", "DoubleEntry::DoubleEntryError", "RuntimeError", "StandardError"),
            )
        OracleScenarios
            .load("lock_create_ignoring_duplicates_scoped")
            .notifications
            .single()
            .event shouldBe
            "duplicate_ignore.double_entry"
    }

    @Test
    fun `every scenario belongs to exactly one parity area`() {
        OracleScenarios.ids.forEach { ParityArea.of(it) }
        ParityArea.entries.sumOf { it.scenarioIds.size } shouldBe 121
        ParityArea.of("linecheck_consistent") shouldBe ParityArea.VALIDATION
        ParityArea.of("line_scopes") shouldBe ParityArea.TRANSFERS_AND_LINES
        ParityArea.of("compat_nulls_not_distinct_index") shouldBe ParityArea.LOCKING
    }
}
