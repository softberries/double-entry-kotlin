package com.softberries.doubleentry.parity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText

class ParityReportTest {
    private val deviations =
        DeviationList.parse(
            ParityArea.LOCKING,
            """{"lock_concurrent_threads_same_accounts": {"xRef": "X-03", "kotlinExpectation": "one balance row per account"}}""",
        )

    @Test
    fun `a listed scenario is reported as a deviation, not skipped`(
        @TempDir dir: Path,
    ) {
        val report = ParityReport(ParityArea.LOCKING, deviations)

        val outcome =
            report.record(
                "lock_concurrent_threads_same_accounts",
                Comparison.of("double_entry_account_balances id=3: unexpected row"),
            )

        outcome.status shouldBe ParityStatus.DEVIATION
        outcome.label shouldBe "deviation (X-03)"
        val markdown = report.write(dir).readText()
        markdown shouldContain
            "| lock_concurrent_threads_same_accounts | deviation (X-03) | Kotlin expectation: one balance row per account<br>double_entry_account_balances id=3: unexpected row |"
    }

    @Test
    fun `a listed scenario that was not run is still reported and fails`(
        @TempDir dir: Path,
    ) {
        val report = ParityReport(ParityArea.LOCKING, deviations)
        ParityArea.LOCKING.scenarioIds.forEach { report.record(it, Comparison.MATCH) }
        report.assertParity(dir)

        val partial = ParityReport(ParityArea.LOCKING, deviations)
        partial.record("lock_empty", Comparison.MATCH)

        partial.outcomes().single { it.scenario == "lock_concurrent_threads_same_accounts" }.label shouldBe "not run"
        shouldThrow<AssertionError> { partial.assertParity(dir) }.message shouldContain "lock_concurrent_threads_same_accounts: not run"
    }

    @Test
    fun `a mismatch fails and is written with its diff`(
        @TempDir dir: Path,
    ) {
        val report = ParityReport(ParityArea.LOCKING, deviations)
        ParityArea.LOCKING.scenarioIds.forEach { report.record(it, Comparison.MATCH) }
        report.record("lock_empty", Comparison.of("double_entry_lines id=1 balance: expected <1> but was <2>")).label shouldBe "mismatch"

        shouldThrow<AssertionError> { report.assertParity(dir) }.message shouldContain "lock_empty: mismatch"
        dir.resolve("locking.md").readText() shouldContain
            "| lock_empty | mismatch | double_entry_lines id=1 balance: expected <1> but was <2> |"
    }

    @Test
    fun `scenarios of another area are rejected`() {
        shouldThrow<IllegalArgumentException> { ParityReport(ParityArea.LOCKING, deviations).record("balance_current", Comparison.MATCH) }
    }
}
