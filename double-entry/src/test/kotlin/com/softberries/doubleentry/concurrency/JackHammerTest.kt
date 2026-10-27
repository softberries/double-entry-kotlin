package com.softberries.doubleentry.concurrency

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter

/**
 * Port of `script/jack_hammer` (threads mode, `-z`) on PostgreSQL 17: workers do random allowed transfers among the
 * jack hammer accounts, optionally after flushing `double_entry_account_balances`, then the ledger is reconciled as
 * `reconcile` does (`script/jack_hammer:159-200`). Oracle: `oracle/concurrency/jack_hammer_t2000z.txt` (threads, all
 * reconciled) and `jack_hammer_t2000f4.txt` (4 balance flushes, all reconciled); only the reconciliation result is
 * compared, the run itself is random.
 *
 * Exercises BR-026 (running balances), BR-061/BR-062 (ordered locks, missing rows recreated from the lines), BR-069
 * (create ignoring duplicates), BR-070 / X-03 (one row per account) and BR-071 (thread-local locks) under load. A
 * deadlock (`40P01`) must be restarted by the library (BR-067): any exception from a worker fails the test, and
 * restart/retry events are counted and reported, not asserted to be zero.
 */
class JackHammerTest : ConcurrencyIntegrationTest() {
    @Test
    fun `jack hammer with threads and no flush reconciles (jack_hammer_t2000z)`(reporter: TestReporter) {
        val run = jackHammer(balanceFlushCount = 1, reporter = reporter)

        assertJackHammerReconciled(run)
    }

    @Test
    fun `jack hammer with 4 balance flushes recreates the balance rows from the lines (jack_hammer_t2000f4)`(reporter: TestReporter) {
        val run = jackHammer(balanceFlushCount = 4, reporter = reporter)

        assertJackHammerReconciled(run)
        // Every flush deleted all rows, so the surviving ones were all inserted after the last flush (BR-062).
        run.rowIdsAfter.forEach { it shouldBeGreaterThan run.maxRowIdBeforeLastFlush }
        run.maxRowIdBeforeLastFlush shouldBeGreaterThan 0
    }

    private fun assertJackHammerReconciled(run: JackHammerRun) {
        run.transfers shouldBe JackHammerSettings.transfers
        lineCount() shouldBe 2L * run.transfers
        sumOfAmounts() shouldBe 0
        assertReconciled()
        balanceRowsPerAccount() shouldBe jackHammerAccounts.associate { "${it.identifier}@1" to 1L }
        cachedBalances().map { it.first } shouldContainExactlyInAnyOrder jackHammerAccounts.map { "${it.identifier}@1" }
    }
}
