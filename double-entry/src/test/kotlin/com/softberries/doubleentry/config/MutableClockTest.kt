package com.softberries.doubleentry.config

import com.softberries.doubleentry.testing.MutableClock
import com.softberries.doubleentry.testing.PostgresIntegrationTest
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClockTest : PostgresIntegrationTest() {
    @Autowired
    @Qualifier(ConfigConfiguration.DOUBLE_ENTRY_CLOCK)
    private lateinit var doubleEntryClock: Clock

    @Test
    fun `doubleEntryClock is the MutableClock fixture`() {
        doubleEntryClock shouldBeSameInstanceAs clock
        doubleEntryClock.zone shouldBe ZoneOffset.UTC
    }

    @Test
    fun `setting the oracle T0 freezes doubleEntryClock at it`() {
        clock.set(Instant.parse("2026-01-15T10:00:00Z"))

        doubleEntryClock.instant() shouldBe MutableClock.ORACLE_T0
        doubleEntryClock.instant() shouldBe Instant.parse("2026-01-15T10:00:00Z")
    }

    @Test
    fun `advance moves frozen time and reset follows the system clock again`() {
        clock.set(MutableClock.ORACLE_T0)
        clock.advance(Duration.ofDays(1))
        doubleEntryClock.instant() shouldBe Instant.parse("2026-01-16T10:00:00Z")

        clock.reset()
        Duration.between(doubleEntryClock.instant(), Instant.now()).abs().toSeconds() shouldBeLessThan 5
    }

    @Test
    fun `withZone returns a view that follows later changes of the clock`() {
        clock.set(MutableClock.ORACLE_T0)
        val warsaw = doubleEntryClock.withZone(ZoneId.of("Europe/Warsaw"))
        warsaw.zone shouldBe ZoneId.of("Europe/Warsaw")
        warsaw.instant() shouldBe MutableClock.ORACLE_T0

        clock.advance(Duration.ofHours(1))
        warsaw.instant() shouldBe Instant.parse("2026-01-15T11:00:00Z")

        clock.reset()
        Duration.between(warsaw.instant(), Instant.now()).abs().toSeconds() shouldBeLessThan 5
    }
}
