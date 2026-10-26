package com.softberries.doubleentry.concurrency

import com.softberries.doubleentry.testing.TestUser
import com.softberries.doubleentry.transfer.TransferIntegrationTestBase
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.slf4j.LoggerFactory

/**
 * Port of `spec/performance/double_entry_performance_spec.rb` [1:1:1] and [1:1:3]: 100 transfers `test` → `savings`
 * (`bonus`) for one user, without and with 8 metadata keys. Ruby only profiled them (no assertions); this smoke test
 * logs and reports the duration and checks the lines were written. [1:1:2] (separate metadata table) is not ported
 * (X-02, D-004).
 */
@Tag("concurrency")
class TransferPerformanceTest : TransferIntegrationTestBase() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val user = TestUser(1)
    private val test get() = resolver.account("test", scope = user)
    private val savings get() = resolver.account("savings", scope = user)

    private fun profileTransfers(
        name: String,
        metadata: Map<String, Any?>?,
        reporter: TestReporter,
    ) {
        val started = System.nanoTime()
        repeat(100) { transfers.transfer(usd(10_00), from = test, to = savings, code = "bonus", metadata = metadata) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        log.info("{}: 100 transfers in {} ms", name, elapsedMs)
        reporter.publishEntry(name, "100 transfers in $elapsedMs ms")
        lineCount() shouldBe 200
        calculator.calculate(savings) shouldBe usd(100 * 10_00)
    }

    @Test
    fun `PERF 1-1-1 creates a lot of transfers quickly without metadata`(reporter: TestReporter) {
        profileTransfers("transfer", null, reporter)
    }

    @Test
    fun `PERF 1-1-3 creates a lot of transfers quickly with metadata in the lines column`(reporter: TestReporter) {
        val bigMetadata = (0 until 8).associate { "key$it" to "value$it" }

        profileTransfers("transfer-with-metadata-column", bigMetadata, reporter)

        val stored =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM double_entry_lines WHERE metadata ->> 'key7' = 'value7'",
                Long::class.java,
            )
        stored shouldBe 200
    }
}
