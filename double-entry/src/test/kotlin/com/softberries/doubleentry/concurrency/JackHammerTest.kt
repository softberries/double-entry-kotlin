package com.softberries.doubleentry.concurrency

import com.softberries.doubleentry.account.AccountInstance
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.random.Random

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
    private val accounts: List<AccountInstance>
        get() = (0 until JackHammerSettings.ACCOUNTS).map { resolver.account(JackHammerSettings.accountIdentifier(it), scope = 1) }

    @Test
    @Timeout(value = 85, unit = TimeUnit.SECONDS)
    fun `jack hammer with threads and no flush reconciles (jack_hammer_t2000z)`(reporter: TestReporter) {
        val run = jackHammer(balanceFlushCount = 1, reporter = reporter)

        assertJackHammerReconciled(run)
    }

    @Test
    @Timeout(value = 85, unit = TimeUnit.SECONDS)
    fun `jack hammer with 4 balance flushes recreates the balance rows from the lines (jack_hammer_t2000f4)`(reporter: TestReporter) {
        val run = jackHammer(balanceFlushCount = 4, reporter = reporter)

        assertJackHammerReconciled(run)
        // Every flush deleted all rows, so the surviving ones were all inserted after the last flush (BR-062).
        run.rowIdsAfter.forEach { it shouldBeGreaterThan run.maxRowIdBeforeLastFlush }
        run.maxRowIdBeforeLastFlush shouldBeGreaterThan 0
    }

    private class Run(
        val transfers: Int,
        val maxRowIdBeforeLastFlush: Long,
        val rowIdsAfter: List<Long>,
    )

    /**
     * `run_tests` (`script/jack_hammer:113-140`): for each flush, delete every balance row, then let the workers run
     * their share. Ruby gives each worker `transfers / workers / flushes` (at least 1); the remainder is spread here so
     * exactly `transfers` are made and `lines = 2 × transfers` holds for any scale.
     */
    private fun jackHammer(
        balanceFlushCount: Int,
        reporter: TestReporter,
    ): Run {
        val workers = JackHammerSettings.workers
        val total = JackHammerSettings.transfers
        val seed = JackHammerSettings.seed
        val accounts = accounts
        log.info(
            "jack hammer: accounts={} workers={} transfers={} flushes={} seed={}",
            accounts.size,
            workers,
            total,
            balanceFlushCount,
            seed,
        )
        val started = System.nanoTime()
        var maxRowIdBeforeLastFlush = 0L
        var done = 0
        repeat(balanceFlushCount) { flush ->
            maxRowIdBeforeLastFlush = maxBalanceRowId()
            jdbcTemplate.update("DELETE FROM double_entry_account_balances")
            val batch = total / balanceFlushCount + if (flush < total % balanceFlushCount) 1 else 0
            runInParallel(workers) { worker ->
                val iterations = batch / workers + if (worker < batch % workers) 1 else 0
                // `srand` per process in Ruby; a fixed, logged seed per (flush, worker) here, so a failure can be replayed.
                val random = Random(seed + flush * 1_000L + worker)
                repeat(iterations) {
                    val from = accounts.random(random)
                    val to = (accounts - from).random(random)
                    transfers.transfer(usd(random.nextLong(1000) + 1), from = from, to = to, code = JackHammerSettings.CODE)
                }
            }
            done += batch
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val report = "transfers=$done flushes=$balanceFlushCount seed=$seed elapsedMs=$elapsedMs ${events.summary()}"
        log.info("jack hammer finished: {}", report)
        reporter.publishEntry("jackHammer", report)
        return Run(done, maxRowIdBeforeLastFlush, balanceRowIds())
    }

    private fun assertJackHammerReconciled(run: Run) {
        run.transfers shouldBe JackHammerSettings.transfers
        lineCount() shouldBe 2L * run.transfers
        sumOfAmounts() shouldBe 0
        assertReconciled()
        balanceRowsPerAccount() shouldBe accounts.associate { "${it.identifier}@1" to 1L }
        cachedBalances().map { it.first } shouldContainExactlyInAnyOrder accounts.map { "${it.identifier}@1" }
    }

    private fun maxBalanceRowId(): Long =
        jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM double_entry_account_balances", Long::class.java)!!

    private fun balanceRowIds(): List<Long> = jdbcTemplate.queryForList("SELECT id FROM double_entry_account_balances", Long::class.java)
}
