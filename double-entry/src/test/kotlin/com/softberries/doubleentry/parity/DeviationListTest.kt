package com.softberries.doubleentry.parity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class DeviationListTest {
    @Test
    fun `known X-refs are the decisions register plus planner additions`() {
        DeviationList.KNOWN_X_REFS shouldContainAll (1..10).map { "X-%02d".format(it) } + "X-11"
    }

    @Test
    fun `a valid entry is loaded`() {
        val list =
            DeviationList.parse(
                ParityArea.LOCKING,
                """{"lock_wait_timeout_pg": {"xRef": "X-04", "kotlinExpectation": "library LockWaitTimeout"}}""",
            )

        list.deviationFor("lock_wait_timeout_pg") shouldBe Deviation("X-04", "library LockWaitTimeout")
        list.deviationFor("lock_empty") shouldBe null
    }

    @Test
    fun `an unknown X-ref fails`() {
        shouldThrow<IllegalStateException> {
            DeviationList.parse(ParityArea.LOCKING, """{"lock_empty": {"xRef": "X-99", "kotlinExpectation": "?"}}""")
        }.message shouldContain "lock_empty: unknown X-ref 'X-99'"
    }

    @Test
    fun `unknown scenarios, scenarios of another area and blank expectations fail`() {
        val message =
            shouldThrow<IllegalStateException> {
                DeviationList.parse(
                    ParityArea.LOCKING,
                    """
                    {"lock_nope": {"xRef": "X-03", "kotlinExpectation": "x"},
                     "balance_current": {"xRef": "X-03", "kotlinExpectation": "x"},
                     "lock_empty": {"xRef": "X-03", "kotlinExpectation": " "}}
                    """.trimIndent(),
                )
            }.message!!

        message shouldContain "lock_nope: no such oracle scenario"
        message shouldContain "balance_current: belongs to another parity area than locking"
        message shouldContain "lock_empty: kotlinExpectation is blank"
    }

    /** Fails the build when a parity unit commits an invalid `parity/<area>.deviations.json`. */
    @ParameterizedTest
    @EnumSource(ParityArea::class)
    fun `every committed deviation file is valid`(area: ParityArea) {
        DeviationList.load(area).area shouldBe area
    }
}
