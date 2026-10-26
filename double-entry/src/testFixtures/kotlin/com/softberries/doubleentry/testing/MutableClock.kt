package com.softberries.doubleentry.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Settable UTC clock for tests; the Kotlin replacement of Timecop (`Timecop.freeze`/`travel`).
 * Unfrozen it follows the system clock. [PostgresIntegrationTest] resets it before each test.
 */
class MutableClock : Clock() {
    @Volatile
    private var frozen: Instant? = null

    /** Freezes time at [instant], e.g. the oracle T0. */
    fun set(instant: Instant) {
        frozen = instant
    }

    /** Moves a frozen clock forward (or freezes "now + [duration]"). */
    fun advance(duration: Duration) {
        frozen = instant().plus(duration)
    }

    fun reset() {
        frozen = null
    }

    override fun instant(): Instant = frozen ?: Instant.now()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = if (zone == ZoneOffset.UTC) this else fixed(instant(), zone)

    companion object {
        /** T0 of every frozen-time oracle scenario (`oracle/README.md`). */
        val ORACLE_T0: Instant = Instant.parse("2026-01-15T10:00:00Z")
    }
}
