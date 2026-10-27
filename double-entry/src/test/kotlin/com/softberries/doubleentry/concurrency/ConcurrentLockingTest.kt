package com.softberries.doubleentry.concurrency

import com.softberries.doubleentry.locking.Locking
import com.softberries.doubleentry.testing.OracleSample
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * Concurrent use of `lockAccounts` and `transfer` on PostgreSQL 17.
 *
 * - `lock_concurrent_threads_same_accounts.json`: 8 threads × 25 transfers bank→cash on an empty database. Lines and
 *   balances match the oracle; the balance-row count follows X-03 (D-005): exactly one row per account, where Ruby on
 *   the generator index kept 16 NULL-scope rows.
 * - `spec/double_entry/locking_spec.rb` [1:13:1] and [1:13:2] (BR-061, BR-062, BR-071): threads holding locks on
 *   disjoint accounts run at the same time. Ruby showed this with `sleep`s; here both lock blocks must meet at a
 *   barrier, which only succeeds if both locks are held simultaneously.
 */
class ConcurrentLockingTest : ConcurrencyIntegrationTest() {
    @Autowired
    private lateinit var locking: Locking

    private val bank get() = acct("bank")
    private val cash get() = acct("cash")
    private val accountA get() = resolver.account("account_a", scope = "1")
    private val accountB get() = resolver.account("account_b", scope = "2")
    private val accountC get() = resolver.account("account_c", scope = "3")
    private val accountD get() = resolver.account("account_d", scope = "4")

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `concurrent first use - 8 threads x 25 transfers bank to cash (lock_concurrent_threads_same_accounts, X-03)`(
        reporter: TestReporter,
    ) {
        val oracle = OracleSample.result("lock_concurrent_threads_same_accounts")
        val threads = 8
        val perThread = 25
        // All threads start transferring together, so the first transfers race to create the missing balance rows.
        val start = CyclicBarrier(threads)

        runInParallel(threads) {
            start.await(30, TimeUnit.SECONDS)
            repeat(perThread) { transfers.transfer(usd(100), from = bank, to = cash, code = "salary") }
        }

        // Losers of the creation race show up as duplicate_ignore events (BR-069): tolerated and reported, not asserted.
        val report = events.summary()
        log.info("concurrent first use: {}", report)
        reporter.publishEntry("concurrentFirstUse", report)

        lineCount() shouldBe oracle["lines"].asLong()
        val cashBalance = calculator.calculate(cash)
        val bankBalance = calculator.calculate(bank)
        cashBalance.minorUnits shouldBe oracle["cash"]["money"]["fractional"].asLong()
        cashBalance.toString() shouldBe oracle["cash"]["money"]["to_s"].asText()
        bankBalance.minorUnits shouldBe oracle["bank"]["money"]["fractional"].asLong()
        bankBalance.toString() shouldBe oracle["bank"]["money"]["to_s"].asText()
        val cachedCash = cachedBalances().single { it.first == "cash" }.second
        cachedCash shouldBe oracle["cash_cached"]["money"]["fractional"].asLong()
        // Oracle `running_balances_consistent: true`: BR-026 on every line, checked by assertReconciled().
        assertReconciled()
        sumOfAmounts() shouldBe 0

        // X-03: Ruby kept 16 rows (`db_after` of the oracle); UNIQUE NULLS NOT DISTINCT keeps exactly one per account.
        OracleSample.json("lock_concurrent_threads_same_accounts")["db_after"]["double_entry_account_balances"].size() shouldBe 16
        balanceRowsPerAccount() shouldBe mapOf("bank" to 1L, "cash" to 1L)
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `LK 1-13-1 multiple threads lock disjoint accounts at the same time`() {
        // Balance rows already exist (an earlier transfer each), so both threads go straight to SELECT … FOR UPDATE.
        transfers.transfer(usd(1), from = accountA, to = accountB, code = "test")
        transfers.transfer(usd(1), from = accountC, to = accountD, code = "test")
        val bothLocked = CyclicBarrier(2)

        runInParallel(2) { worker ->
            val (from, to) = if (worker == 0) accountA to accountB else accountC to accountD
            locking.lockAccounts(from, to) {
                bothLocked.await(10, TimeUnit.SECONDS)
                transfers.transfer(usd(10_00), from = from, to = to, code = "test")
            }
        }

        cachedBalances().toMap() shouldBe
            mapOf("account_a@1" to -1001L, "account_b@2" to 1001L, "account_c@3" to -1001L, "account_d@4" to 1001L)
        assertReconciled()
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `LK 1-13-2 multiple threads lock accounts without balances at the same time`() {
        balanceRowCount() shouldBe 0
        val bothLocked = CyclicBarrier(2)

        runInParallel(2) { worker ->
            val (first, second) = if (worker == 0) accountA to accountB else accountC to accountD
            locking.lockAccounts(first, second) { bothLocked.await(10, TimeUnit.SECONDS) }
        }

        cachedBalances().toMap() shouldBe mapOf("account_a@1" to 0L, "account_b@2" to 0L, "account_c@3" to 0L, "account_d@4" to 0L)
    }
}
