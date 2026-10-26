package com.softberries.doubleentry.transfer

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

/**
 * X-02 (D-004, BR-029): the legacy `double_entry_line_metadata` table (`DoubleEntry::LineMetadata`,
 * `lib/double_entry/line_metadata.rb`, `json_metadata = false`) is not ported. The schema has no such table and no
 * production code path refers to it; only comments may mention it.
 */
class X02LineMetadataAbsenceTest : TransferIntegrationTestBase() {
    private val reference = Regex("line_metadata|LineMetadata|json_metadata|jsonMetadata", RegexOption.IGNORE_CASE)
    private val commentLine = Regex("^\\s*(--|//|\\*|/\\*)")

    @Test
    fun `the double_entry_line_metadata table does not exist, even after a transfer with metadata`() {
        transfers.transfer(usd(100), from = acct("bank"), to = acct("cash"), code = "salary", metadata = mapOf("tax" to listOf("GST")))

        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'double_entry_line_metadata'",
            Long::class.java,
        ) shouldBe 0
        sqlCapture.shapes.filter { it.table == "double_entry_line_metadata" }.shouldBeEmpty()
    }

    @Test
    fun `no production source refers to the metadata table or the json_metadata flag outside comments`() {
        val main = Path.of("src/main")
        Files.isDirectory(main) shouldBe true
        val offending =
            Files.walk(main).use { paths ->
                paths
                    .filter {
                        it.isRegularFile() &&
                            it.fileName.toString().substringAfterLast('.') in setOf("kt", "sql", "json", "properties", "yml")
                    }.toList()
                    .flatMap { file ->
                        file
                            .readLines()
                            .withIndex()
                            .filter { (_, line) ->
                                reference.containsMatchIn(line) &&
                                    !commentLine.containsMatchIn(line)
                            }.map { (index, line) -> "$file:${index + 1}: $line" }
                    }
            }

        offending.shouldBeEmpty()
    }
}
