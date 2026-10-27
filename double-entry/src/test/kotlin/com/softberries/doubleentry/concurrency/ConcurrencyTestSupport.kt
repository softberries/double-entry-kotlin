package com.softberries.doubleentry.concurrency

import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.config.DoubleEntryConfigurer
import com.softberries.doubleentry.instrumentation.DoubleEntryEvent
import com.softberries.doubleentry.instrumentation.DoubleEntryEventType
import com.softberries.doubleentry.transfer.TransferIntegrationTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestReporter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.EnumMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * The accounts of `script/jack_hammer:85-104`: `account-0` … `account-<n-1>`, identity-scoped, with a `test` transfer
 * between every ordered pair (self-transfers included, as Ruby defines them). Sized for the full run so one Spring
 * context serves both modes.
 */
@TestConfiguration(proxyBeanMethods = false)
class ConcurrencyTestConfiguration {
    @Bean
    fun jackHammerDefinitions(): DoubleEntryConfigurer =
        DoubleEntryConfigurer { builder ->
            val identity: (Any) -> Any? = { it }
            val identifiers = (0 until JackHammerSettings.ACCOUNTS).map { JackHammerSettings.accountIdentifier(it) }
            builder.accounts { identifiers.forEach { define(it, scopeIdentifier = identity) } }
            builder.transfers {
                for (from in identifiers) {
                    for (to in identifiers) define(from, to, JackHammerSettings.CODE)
                }
            }
        }

    @Bean
    fun doubleEntryEventCounter(): DoubleEntryEventCounter = DoubleEntryEventCounter()
}

/** Counts `deadlock_restart`, `deadlock_retry` and `duplicate_ignore` events (BR-067, BR-069) across all threads. */
class DoubleEntryEventCounter : ApplicationListener<DoubleEntryEvent> {
    private val counts = EnumMap<DoubleEntryEventType, AtomicLong>(DoubleEntryEventType::class.java)

    init {
        DoubleEntryEventType.entries.forEach { counts[it] = AtomicLong() }
    }

    override fun onApplicationEvent(event: DoubleEntryEvent) {
        counts.getValue(event.type).incrementAndGet()
    }

    operator fun get(type: DoubleEntryEventType): Long = counts.getValue(type).get()

    fun reset() = counts.values.forEach { it.set(0) }

    /** `deadlock_restart=0 deadlock_retry=0 duplicate_ignore=3` */
    fun summary(): String = DoubleEntryEventType.entries.joinToString(" ") { "${it.shortName}=${get(it)}" }
}

/**
 * Base class of the concurrency suite (M-015): the transfer fixtures, the jack hammer accounts, an event counter and a
 * connection pool large enough for every worker thread plus the test thread (issue note: pool ≥ threads + 2; set
 * here only, never in a host's `application.yml`).
 */
@Tag("concurrency")
@Import(ConcurrencyTestConfiguration::class)
@TestPropertySource(properties = ["spring.datasource.hikari.maximum-pool-size=${JackHammerSettings.POOL_SIZE}"])
abstract class ConcurrencyIntegrationTest : TransferIntegrationTestBase() {
    @Autowired
    protected lateinit var events: DoubleEntryEventCounter

    protected val log = LoggerFactory.getLogger(javaClass)

    private lateinit var executor: ExecutorService

    /** Set when the test ends (passed, failed or timed out); long-running workers poll it via [cancelled]. */
    @Volatile
    private var stopRequested = false

    /** True once the test is over: a worker loop must return so it cannot touch the next test's database. */
    protected val cancelled: Boolean get() = stopRequested

    @BeforeEach
    fun startExecutor() {
        events.reset()
        stopRequested = false
        executor = Executors.newFixedThreadPool(JackHammerSettings.MAX_WORKERS)
    }

    /**
     * Stops the workers before the next test's `@BeforeEach` truncates the tables: otherwise a worker that outlived a
     * failed or timed-out test keeps transferring, and the `TRUNCATE` deadlocks against it.
     */
    @AfterEach
    fun stopExecutor() {
        stopRequested = true
        executor.shutdownNow()
        check(executor.awaitTermination(60, TimeUnit.SECONDS)) { "concurrency workers did not stop within 60 s" }
    }

    /**
     * Runs [workers] tasks in parallel and waits for all of them. Any exception thrown by a worker (a deadlock that was
     * not restarted, a lock timeout, a broken invariant) fails the test with the worker's exception.
     */
    protected fun runInParallel(
        workers: Int,
        timeout: Duration = Duration.ofSeconds(80),
        task: (worker: Int) -> Unit,
    ) {
        val futures: List<Future<*>> = (0 until workers).map { worker -> executor.submit { task(worker) } }
        await withPollInterval Duration.ofMillis(20) atMost timeout until { futures.all { it.isDone } }
        val failures =
            futures.mapNotNull { future ->
                try {
                    future.get()
                    null
                } catch (e: ExecutionException) {
                    e.cause ?: e
                }
            }
        failures.forEach { log.error("worker failed", it) }
        failures.map { it.toString() }.shouldBeEmpty()
    }

    /** The jack hammer accounts (`script/jack_hammer:85-104`), all with scope identity 1. */
    protected val jackHammerAccounts: List<AccountInstance>
        get() = (0 until JackHammerSettings.ACCOUNTS).map { resolver.account(JackHammerSettings.accountIdentifier(it), scope = 1) }

    /**
     * `run_tests` (`script/jack_hammer:113-140`): for each flush, delete every balance row, then let the workers run
     * their share. Ruby gives each worker `transfers / workers / flushes` (at least 1); the remainder is spread here so
     * exactly `transfers` are made and `lines = 2 × transfers` holds for any scale. Shared by [JackHammerTest] (M-015)
     * and the locking parity driver (M-021), which replays the oracle runs' shape.
     */
    protected fun jackHammer(
        balanceFlushCount: Int,
        reporter: TestReporter,
        workers: Int = JackHammerSettings.workers,
        total: Int = JackHammerSettings.transfers,
    ): JackHammerRun {
        val seed = JackHammerSettings.seed
        val accounts = jackHammerAccounts
        log.info(
            "jack hammer: accounts={} workers={} transfers={} flushes={} seed={}",
            accounts.size,
            workers,
            total,
            balanceFlushCount,
            seed,
        )
        val started = System.nanoTime()
        // One deadline for the whole run (no fixed @Timeout): it scales with `-Pjackhammer.full`.
        val deadline = started + JackHammerSettings.timeout.toNanos()
        var maxRowIdBeforeLastFlush = 0L
        var done = 0
        repeat(balanceFlushCount) { flush ->
            maxRowIdBeforeLastFlush = maxBalanceRowId()
            jdbcTemplate.update("DELETE FROM double_entry_account_balances")
            val batch = total / balanceFlushCount + if (flush < total % balanceFlushCount) 1 else 0
            runInParallel(workers, timeout = Duration.ofNanos(maxOf(deadline - System.nanoTime(), 1_000_000))) { worker ->
                val iterations = batch / workers + if (worker < batch % workers) 1 else 0
                // `srand` per process in Ruby; a fixed, logged seed per (flush, worker) here, so a failure can be replayed.
                val random = Random(seed + flush * 1_000L + worker)
                repeat(iterations) {
                    if (cancelled) return@runInParallel
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
        return JackHammerRun(done, maxRowIdBeforeLastFlush, balanceRowIds())
    }

    private fun maxBalanceRowId(): Long =
        jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM double_entry_account_balances", Long::class.java)!!

    private fun balanceRowIds(): List<Long> = jdbcTemplate.queryForList("SELECT id FROM double_entry_account_balances", Long::class.java)

    /** Sum of every line amount: zero in a double-entry ledger. */
    protected fun sumOfAmounts(): Long =
        jdbcTemplate.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM double_entry_lines", Long::class.java)!!

    /** Number of balance rows per `account@scope` (X-03: exactly one each, including the NULL scope). */
    protected fun balanceRowsPerAccount(): Map<String, Long> =
        jdbcTemplate
            .query("SELECT account, scope, COUNT(*) AS n FROM double_entry_account_balances GROUP BY account, scope") { rs, _ ->
                (rs.getString("account") + (rs.getString("scope")?.let { "@$it" } ?: "")) to rs.getLong("n")
            }.toMap()

    /**
     * `reconciled?` of `script/jack_hammer:150-157` for every account that has lines: the latest line balance equals
     * the sum of the account's amounts and the cached balance; plus BR-026 on every line ([assertRunningBalances]).
     */
    protected fun assertReconciled() {
        assertRunningBalances()
        val perAccount =
            jdbcTemplate.queryForList(
                """
                SELECT l.account, l.scope, s.total, l.balance AS final_balance, b.balance AS cached_balance
                  FROM (SELECT account, scope, SUM(amount) AS total, MAX(id) AS last_id
                          FROM double_entry_lines GROUP BY account, scope) s
                  JOIN double_entry_lines l ON l.id = s.last_id
                  LEFT JOIN double_entry_account_balances b
                         ON b.account = l.account AND b.scope IS NOT DISTINCT FROM l.scope
                """.trimIndent(),
            )
        for (row in perAccount) {
            val label = "${row["account"]}@${row["scope"]}"
            val total = (row["total"] as Number).toLong()
            (label to row["final_balance"]) shouldBe (label to total)
            (label to row["cached_balance"]) shouldBe (label to total)
        }
    }
}

/** Outcome of [ConcurrencyIntegrationTest.jackHammer]. */
class JackHammerRun(
    val transfers: Int,
    val maxRowIdBeforeLastFlush: Long,
    val rowIdsAfter: List<Long>,
)

/**
 * Scale of the jack hammer run. Ruby defaults (`script/jack_hammer:46-50`) are 5 accounts, 20 workers and 20000
 * transfers; the default here is scaled down to 8 threads and 2000 transfers so the suite stays well under 90 s on CI.
 * `-Pjackhammer.full` restores 20 workers / 20000 transfers; `-Pjackhammer.seed=<n>` replays a run.
 */
object JackHammerSettings {
    const val CODE = "test"
    const val ACCOUNTS = 5
    const val MAX_WORKERS = 20

    // Every worker holds one connection; + test thread + headroom.
    const val POOL_SIZE = MAX_WORKERS + 4

    val full: Boolean = System.getProperty("jackhammer.full")?.toBoolean() ?: false
    val workers: Int = if (full) 20 else 8
    val transfers: Int = if (full) 20_000 else 2_000
    val seed: Long = System.getProperty("jackhammer.seed")?.toLongOrNull() ?: 20_141_013L

    /** Budget for a whole jack hammer run (all flushes): 80 s keeps the default suite under 90 s; the full run gets 20 min. */
    val timeout: Duration = if (full) Duration.ofMinutes(20) else Duration.ofSeconds(80)

    fun accountIdentifier(index: Int) = "account-$index"
}
