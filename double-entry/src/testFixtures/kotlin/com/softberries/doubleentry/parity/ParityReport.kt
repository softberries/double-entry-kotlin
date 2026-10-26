package com.softberries.doubleentry.parity

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

enum class ParityStatus { MATCH, DEVIATION, MISMATCH, NOT_RUN }

data class ParityOutcome(
    val scenario: String,
    val status: ParityStatus,
    val comparison: Comparison,
    val deviation: Deviation? = null,
) {
    /** `match`, `deviation (X-03)`, `mismatch` or `not run`. */
    val label: String
        get() =
            when (status) {
                ParityStatus.DEVIATION -> "deviation (${deviation?.xRef})"
                ParityStatus.NOT_RUN -> "not run"
                else -> status.name.lowercase()
            }
}

/**
 * Collects the outcome of every scenario of an [area] and writes `build/reports/parity/<area>.md`. A scenario listed
 * in the area's [DeviationList] is reported as `deviation (X-nn)` whatever the comparison says: it is still run and
 * shown with its diff, never skipped. [assertParity] fails on a mismatch and on a scenario of the area that was not run.
 */
class ParityReport(
    val area: ParityArea,
    val deviations: DeviationList = DeviationList.load(area),
) {
    private val outcomes = linkedMapOf<String, ParityOutcome>()

    fun record(
        scenarioId: String,
        comparison: Comparison,
    ): ParityOutcome {
        require(area.owns(scenarioId)) { "$scenarioId is not a scenario of ${area.key}" }
        val deviation = deviations.deviationFor(scenarioId)
        val status =
            when {
                deviation != null -> ParityStatus.DEVIATION
                comparison.matches -> ParityStatus.MATCH
                else -> ParityStatus.MISMATCH
            }
        return ParityOutcome(scenarioId, status, comparison, deviation).also { outcomes[scenarioId] = it }
    }

    /** One outcome per scenario of the area, in id order; scenarios without a recorded outcome are `NOT_RUN`. */
    fun outcomes(): List<ParityOutcome> =
        area.scenarioIds.map { outcomes[it] ?: ParityOutcome(it, ParityStatus.NOT_RUN, Comparison.MATCH, deviations.deviationFor(it)) }

    fun render(): String =
        buildString {
            val all = outcomes()
            appendLine("# Parity report: ${area.key}")
            appendLine()
            appendLine(ParityStatus.entries.joinToString(" · ") { s -> "${s.name.lowercase()}: ${all.count { it.status == s }}" })
            appendLine()
            appendLine("| Scenario | Status | Diff |")
            appendLine("|---|---|---|")
            all.forEach { o ->
                val diff =
                    listOfNotNull(
                        o.deviation?.let { "Kotlin expectation: ${it.kotlinExpectation}" },
                        o.comparison
                            .takeUnless { it.matches }
                            ?.differences
                            ?.joinToString("<br>"),
                    ).joinToString("<br>").replace("|", "\\|").replace("\n", "<br>")
                appendLine("| ${o.scenario} | ${o.label} | $diff |")
            }
        }

    fun write(dir: Path = reportDir()): Path {
        dir.createDirectories()
        return dir.resolve("${area.key}.md").also { it.writeText(render()) }
    }

    /** Writes the report, then fails if any scenario is a mismatch or was not run. */
    fun assertParity(dir: Path = reportDir()) {
        val path = write(dir)
        val failing = outcomes().filter { it.status == ParityStatus.MISMATCH || it.status == ParityStatus.NOT_RUN }
        if (failing.isNotEmpty()) {
            throw AssertionError(
                "${area.key}: ${failing.size} scenario(s) without parity (report: $path)\n" +
                    failing.joinToString("\n") { "  - ${it.scenario}: ${it.label} ${it.comparison}" },
            )
        }
    }

    companion object {
        /** `build/reports/parity` of the module (Gradle runs tests in the project directory). */
        fun reportDir(): Path = Paths.get(System.getProperty("doubleentry.parityReportDir") ?: "build/reports/parity")
    }
}
