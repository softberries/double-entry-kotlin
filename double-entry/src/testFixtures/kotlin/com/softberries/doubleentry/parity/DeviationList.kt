package com.softberries.doubleentry.parity

import com.fasterxml.jackson.module.kotlin.readValue
import com.softberries.doubleentry.testing.MigrationSpec

/** Why a scenario's Kotlin expectation differs from the Ruby oracle: an X-ref of the deviation register. */
data class Deviation(
    val xRef: String,
    val kotlinExpectation: String,
)

/**
 * The deviations of one [ParityArea], read from the test resource `parity/<area>.deviations.json`
 * (`{"<scenario>": {"xRef": "X-03", "kotlinExpectation": "…"}}`). One file per area, so parity units never edit a
 * shared file; a missing file means "no deviations". Loading fails on an unknown X-ref, an unknown scenario or a
 * scenario of another area, so a bad entry fails the build instead of hiding a scenario.
 */
class DeviationList private constructor(
    val area: ParityArea,
    val entries: Map<String, Deviation>,
) {
    fun deviationFor(scenarioId: String): Deviation? = entries[scenarioId]

    companion object {
        /**
         * X-refs the planner added after the investigation (backlog.md "X-11 Symbol vs String collapse"); to be recorded
         * in docs/decisions.md under D-013.
         */
        val PLANNED_X_REFS = setOf("X-11")

        /** The register in `docs/decisions.md` ("Deviation register") plus [PLANNED_X_REFS]. */
        val KNOWN_X_REFS: Set<String> by lazy {
            Regex("^\\| (X-\\d+) \\|", RegexOption.MULTILINE)
                .findAll(MigrationSpec.read("docs/decisions.md"))
                .map { it.groupValues[1] }
                .toSet() + PLANNED_X_REFS
        }

        fun resourcePath(area: ParityArea): String = "parity/${area.key}.deviations.json"

        fun load(area: ParityArea): DeviationList {
            val resource = DeviationList::class.java.classLoader.getResource(resourcePath(area))
            return parse(area, resource?.readText() ?: "{}")
        }

        fun parse(
            area: ParityArea,
            json: String,
        ): DeviationList {
            val entries: Map<String, Deviation> = OracleScenarios.mapperCamelCase.readValue(json)
            val problems =
                entries.flatMap { (scenario, deviation) ->
                    listOfNotNull(
                        "$scenario: unknown X-ref '${deviation.xRef}' (known: ${KNOWN_X_REFS.sorted()})"
                            .takeUnless { deviation.xRef in KNOWN_X_REFS },
                        "$scenario: no such oracle scenario".takeUnless { scenario in OracleScenarios.ids },
                        "$scenario: belongs to another parity area than ${area.key}"
                            .takeUnless { scenario !in OracleScenarios.ids || area.owns(scenario) },
                        "$scenario: kotlinExpectation is blank".takeUnless { deviation.kotlinExpectation.isNotBlank() },
                    )
                }
            check(problems.isEmpty()) { "Invalid ${resourcePath(area)}:\n" + problems.joinToString("\n") { "  - $it" } }
            return DeviationList(area, entries)
        }
    }
}
