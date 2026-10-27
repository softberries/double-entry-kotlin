package com.softberries.doubleentry.mixed

import com.softberries.doubleentry.DoubleEntry
import com.softberries.doubleentry.TestDoubleEntryApplication
import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.concurrency.DoubleEntryEventCounter
import com.softberries.doubleentry.config.DoubleEntryConfigurer
import com.softberries.doubleentry.config.DoubleEntryTables
import com.softberries.doubleentry.ledger.DetailRef
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.testing.DoubleEntryPostgres
import com.softberries.doubleentry.validation.LineCheckService
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeBetween
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The shared account set of `writer.rb` (`SCOPED`, `UNSCOPED`): three identity-scoped and two unscoped accounts, with a
 * `test` transfer between every ordered pair, as `script/jack_hammer:85-104` defines them.
 */
@TestConfiguration(proxyBeanMethods = false)
class MixedWritersConfiguration {
    @Bean
    fun mixedWritersDefinitions(): DoubleEntryConfigurer =
        DoubleEntryConfigurer { builder ->
            val identity: (Any) -> Any? = { it }
            builder.accounts {
                MixedWritersTest.SCOPED.forEach { define(it, scopeIdentifier = identity) }
                MixedWritersTest.UNSCOPED.forEach { define(it) }
            }
            builder.transfers {
                for (from in MixedWritersTest.ALL) {
                    for (to in MixedWritersTest.ALL) define(from, to, MixedWritersTest.CODE)
                }
            }
        }

    @Bean
    fun mixedWritersEventCounter(): DoubleEntryEventCounter = DoubleEntryEventCounter()
}

/**
 * D-017: during cut-over Ruby and Kotlin write the same tables concurrently. A container running the double_entry gem
 * (`ruby-writer/writer.rb`, modelled on `script/jack_hammer`) and Kotlin threads transfer between the same five accounts
 * in one `postgres:17` at the same time; the mixed ledger must then reconcile as `script/jack_hammer:159-200` checks it,
 * pass both LineChecks, and hold rows that do not reveal which language wrote them (`docs/50-data.md` §7).
 *
 * Verifies the cross-language protocol of BR-025 (write sequence, partner ids), BR-026 (running balances), BR-061
 * (ordered `FOR UPDATE` on the balance rows), BR-062 (missing rows recreated from the lines) and BR-069 (duplicate
 * creations ignored), with the X-03 index (`UNIQUE NULLS NOT DISTINCT`) that only the Kotlin migrations create.
 *
 * Out of the default `test` task (Ruby image build): run with `./gradlew :double-entry:mixedWritersTest`.
 */
@Tag("mixed")
@SpringBootTest(classes = [TestDoubleEntryApplication::class])
@Import(MixedWritersConfiguration::class)
@TestPropertySource(properties = ["spring.datasource.hikari.maximum-pool-size=12"])
class MixedWritersTest {
    @Autowired
    private lateinit var doubleEntry: DoubleEntry

    @Autowired
    private lateinit var lineCheckService: LineCheckService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var kotlinEvents: DoubleEntryEventCounter

    private val log = LoggerFactory.getLogger(javaClass)

    private val accounts: List<AccountInstance>
        get() = SCOPED.map { doubleEntry.account(it, scope = 1) } + UNSCOPED.map { doubleEntry.account(it) }

    @BeforeEach
    fun cleanDatabase() {
        jdbcTemplate.execute(
            // The polymorphic detail target of both writers (as the gem's spec/support/schema.rb `users`).
            """
            CREATE TABLE IF NOT EXISTS users (
                id bigserial PRIMARY KEY, username varchar NOT NULL,
                created_at timestamp(6) NOT NULL, updated_at timestamp(6) NOT NULL)
            """.trimIndent(),
        )
        jdbcTemplate.execute("TRUNCATE ${(DoubleEntryTables.ALL + "users").joinToString(", ")} RESTART IDENTITY")
        jdbcTemplate.update("INSERT INTO users (username, created_at, updated_at) VALUES ('mixed', now(), now())")
        kotlinEvents.reset()
    }

    @Test
    fun `ruby and kotlin writers on the same accounts reconcile, encode rows alike and pass both line checks`(reporter: TestReporter) {
        val run = runMixed(flushAtLines = emptyList(), reporter = reporter)

        assertReconciled()
        run.ruby.flushes.shouldBeEmpty()
        assertWritersInterleaved()
        assertSameEncoding(run)
        assertLineChecksPass()
    }

    @Test
    fun `ruby balance flushes mid-run are recreated from the lines and the mixed ledger still reconciles`(reporter: TestReporter) {
        val flushAt = listOf(TOTAL_LINES / 4, TOTAL_LINES / 2, TOTAL_LINES * 3 / 4)
        val run = runMixed(flushAtLines = flushAt, reporter = reporter)

        assertReconciled()
        assertWritersInterleaved()
        run.ruby.flushes shouldHaveSize flushAt.size
        run.ruby.flushes.forEachIndexed { i, (atLines, deleted) ->
            withClue("flush $i: ${run.ruby.flushes}") {
                atLines.shouldBeBetween(flushAt[i].toLong(), TOTAL_LINES - 1L)
                deleted shouldBeGreaterThanOrEqual 1L
            }
        }
        // Every flush deleted all rows, so the surviving ones were recreated after the flushes (BR-062).
        jdbcTemplate.queryForObject("SELECT MIN(id) FROM double_entry_account_balances", Long::class.java)!! shouldBeGreaterThan
            ALL.size.toLong()
        assertLineChecksPass()
    }

    private class MixedRun(
        val ruby: RubySummary,
        val windowStart: LocalDateTime,
        val windowEnd: LocalDateTime,
    )

    /**
     * Starts the Ruby writer and the Kotlin threads together and waits for both. Both sides block on [GATE_LOCK] (Ruby
     * with `pg_advisory_lock_shared` in every thread, Kotlin on a latch) until this thread releases its exclusive lock.
     */
    private fun runMixed(
        flushAtLines: List<Int>,
        reporter: TestReporter,
    ): MixedRun {
        val dataSource = jdbcTemplate.dataSource!!
        val executor = Executors.newFixedThreadPool(KOTLIN_THREADS)
        val ruby =
            RubyWriter.container(
                network,
                DATABASE_URL_IN_NETWORK,
                mapOf(
                    "MODE" to "transfer",
                    "THREADS" to RUBY_THREADS.toString(),
                    "TRANSFERS" to TRANSFERS_PER_WRITER.toString(),
                    "GATE_LOCK" to GATE_LOCK.toString(),
                    "DETAIL_ID" to DETAIL_ID.toString(),
                    "FLUSH_AT_LINES" to flushAtLines.joinToString(","),
                ),
            )
        val gate = dataSource.connection
        try {
            gate.createStatement().use { it.execute("SELECT pg_advisory_lock($GATE_LOCK)") }
            ruby.start()
            val start = CountDownLatch(1)
            val accounts = accounts
            val futures =
                (0 until KOTLIN_THREADS).map { worker ->
                    executor.submit {
                        start.await()
                        kotlinWorker(worker, accounts)
                    }
                }
            val windowStart = utcNow().minusSeconds(CLOCK_TOLERANCE_SECONDS)
            start.countDown()
            gate.createStatement().use { it.execute("SELECT pg_advisory_unlock($GATE_LOCK)") }

            futures.forEach { it.get(5, TimeUnit.MINUTES) }
            val summary = RubyWriter.awaitSummary(ruby, Duration.ofMinutes(5))
            val windowEnd = utcNow().plusSeconds(CLOCK_TOLERANCE_SECONDS)
            reporter.publishEntry("ruby", summary.toString())
            reporter.publishEntry("kotlin", kotlinEvents.summary())
            log.info("mixed writers finished: ruby={} kotlin events: {}", summary, kotlinEvents.summary())
            summary.errors.shouldBeEmpty()
            return MixedRun(summary, windowStart, windowEnd)
        } finally {
            // Closing the session also drops the gate lock if the run failed before the unlock above.
            gate.close()
            executor.shutdownNow()
            ruby.stop()
        }
    }

    /** One Kotlin thread: its share of [TRANSFERS_PER_WRITER] random transfers, as `run_process` (`script/jack_hammer:143-156`). */
    private fun kotlinWorker(
        worker: Int,
        accounts: List<AccountInstance>,
    ) {
        val random = Random(SEED + worker)
        val iterations = TRANSFERS_PER_WRITER / KOTLIN_THREADS + if (worker < TRANSFERS_PER_WRITER % KOTLIN_THREADS) 1 else 0
        repeat(iterations) { seq ->
            val from = accounts.random(random)
            val to = (accounts - from).random(random)
            doubleEntry.transfer(
                Money.ofMinor(random.nextLong(1000) + 1, CurrencyUnit.of("USD")),
                from = from,
                to = to,
                code = CODE,
                detail = if (seq % 2 == 0) DetailRef("User", DETAIL_ID) else null,
                metadata = mapOf("writer" to "kotlin", "worker" to worker, "seq" to seq, "tags" to listOf("mixed", "d-017")),
            )
        }
    }

    /**
     * `reconcile` (`script/jack_hammer:159-200`): all lines written, amounts sum to zero, and per account the running
     * balances hold (BR-026), the latest line balance equals the sum of amounts and the cached balance; plus X-03:
     * exactly one balance row per account, the unscoped ones included.
     */
    private fun assertReconciled() {
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM double_entry_lines", Long::class.java) shouldBe TOTAL_LINES.toLong()
        jdbcTemplate.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM double_entry_lines", Long::class.java) shouldBe 0L

        val running = mutableMapOf<String, Long>()
        jdbcTemplate.query(
            "SELECT id, account, scope, amount, balance FROM double_entry_lines ORDER BY id",
            RowCallbackHandler { rs ->
                val key = key(rs.getString("account"), rs.getString("scope"))
                val expected = running.getOrDefault(key, 0L) + rs.getLong("amount")
                withClue("BR-026 line ${rs.getLong("id")} of $key") { rs.getLong("balance") shouldBe expected }
                running[key] = expected
            },
        )
        running.keys shouldBe EXPECTED_KEYS

        val cached =
            jdbcTemplate
                .query(
                    "SELECT account, scope, COUNT(*) AS n, MIN(balance) AS balance FROM double_entry_account_balances GROUP BY account, scope",
                ) {
                    rs,
                    _,
                    ->
                    key(rs.getString("account"), rs.getString("scope")) to (rs.getLong("n") to rs.getLong("balance"))
                }.toMap()
        cached.keys shouldBe EXPECTED_KEYS
        for ((key, total) in running) {
            withClue("cache of $key") { cached.getValue(key) shouldBe (1L to total) }
        }
    }

    /** The writers really ran at the same time: each one's line ids lie inside the other's range. */
    private fun assertWritersInterleaved() {
        val ranges =
            jdbcTemplate
                .query(
                    "SELECT metadata->>'writer' AS w, MIN(id) AS lo, MAX(id) AS hi, COUNT(*) AS n FROM double_entry_lines GROUP BY 1",
                ) { rs, _ ->
                    rs.getString("w") to Triple(rs.getLong("lo"), rs.getLong("hi"), rs.getLong("n"))
                }.toMap()
        ranges.keys shouldBe setOf("ruby", "kotlin")
        val (rubyLo, rubyHi, rubyLines) = ranges.getValue("ruby")
        val (kotlinLo, kotlinHi, kotlinLines) = ranges.getValue("kotlin")
        rubyLines shouldBe 2L * TRANSFERS_PER_WRITER
        kotlinLines shouldBe 2L * TRANSFERS_PER_WRITER
        withClue("ruby ids $rubyLo..$rubyHi, kotlin ids $kotlinLo..$kotlinHi") {
            rubyLo shouldBeLessThan kotlinHi
            kotlinLo shouldBeLessThan rubyHi
        }
    }

    /**
     * `docs/50-data.md` §7 on 10 sampled lines per writer: every row passes the same per-row checks, both samples have
     * the same metadata key/type shape, and every detail is `User#1` or absent.
     */
    private fun assertSameEncoding(run: MixedRun) {
        val samples =
            listOf("ruby", "kotlin").associateWith { writer ->
                sample(writer).also { rows ->
                    rows shouldHaveSize 10
                    rows.forEach { assertEncoding(writer, it, run) }
                }
            }
        withClue("samples by writer: $samples") {
            samples.getValue("ruby").map { it["metadata_shape"] }.toSet() shouldBe
                samples.getValue("kotlin").map { it["metadata_shape"] }.toSet()
            samples.values.flatten().forEach { DETAIL_FORMS shouldContain "${it["detail_type"]}#${it["detail_id"]}" }
        }
    }

    private fun assertEncoding(
        writer: String,
        row: Map<String, Any?>,
        run: MixedRun,
    ) {
        withClue("$writer line $row") {
            val account = row["account"] as String
            val partnerAccount = row["partner_account"] as String
            ALL shouldContain account
            ALL shouldContain partnerAccount
            row["code"] shouldBe CODE
            // Scope identity string "1" for scoped accounts, NULL for unscoped ones (both columns).
            row["scope"] shouldBe if (account in SCOPED) "1" else null
            row["partner_scope"] shouldBe if (partnerAccount in SCOPED) "1" else null
            // bigint minor units (USD cents) of a 1..1000 cent transfer.
            val amount = row["amount"] as Long
            kotlin.math.abs(amount).shouldBeBetween(1L, 1000L)
            row["balance"]!!::class shouldBe Long::class
            // UTC wall clock with microseconds: the writers run in a non-UTC zone, so local time would be hours off.
            val createdAt = row["created_at"] as LocalDateTime
            val updatedAt = row["updated_at"] as LocalDateTime
            createdAt shouldBeGreaterThan run.windowStart
            createdAt shouldBeLessThan run.windowEnd
            (updatedAt >= createdAt) shouldBe true
            (createdAt.nano % 1000) shouldBe 0
            (updatedAt.nano % 1000) shouldBe 0
            // Partner ids both ways (BR-025): the partner line points back and mirrors account, scope and amount.
            row["p_id"] shouldBe row["partner_id"]
            row["p_partner_id"] shouldBe row["id"]
            row["p_account"] shouldBe partnerAccount
            row["p_scope"] shouldBe row["partner_scope"]
            row["p_partner_account"] shouldBe account
            row["p_partner_scope"] shouldBe row["scope"]
            row["p_amount"] shouldBe -amount
            row["p_code"] shouldBe CODE
            row["same_metadata"] shouldBe true
            row["same_detail"] shouldBe true
            // jsonb object with string keys; the writer is recorded in it.
            row["metadata_type"] shouldBe "object"
            row["metadata_shape"] shouldBe METADATA_SHAPE
            row["writer"] shouldBe writer
            row["tags"] shouldBe """["mixed", "d-017"]"""
        }
    }

    private fun sample(writer: String): List<Map<String, Any?>> =
        jdbcTemplate
            .queryForList(
                """
                SELECT l.id, l.account, l.scope, l.code, l.amount, l.balance, l.partner_id, l.partner_account, l.partner_scope,
                       l.detail_type, l.detail_id,
                       to_char(l.created_at, 'YYYY-MM-DD"T"HH24:MI:SS.US') AS created_at,
                       to_char(l.updated_at, 'YYYY-MM-DD"T"HH24:MI:SS.US') AS updated_at,
                       jsonb_typeof(l.metadata) AS metadata_type, l.metadata->>'writer' AS writer, (l.metadata->'tags')::text AS tags,
                       (SELECT string_agg(k || ':' || jsonb_typeof(v), ',' ORDER BY k) FROM jsonb_each(l.metadata) AS e(k, v)) AS metadata_shape,
                       p.id AS p_id, p.partner_id AS p_partner_id, p.account AS p_account, p.scope AS p_scope,
                       p.partner_account AS p_partner_account, p.partner_scope AS p_partner_scope, p.amount AS p_amount, p.code AS p_code,
                       p.metadata = l.metadata AS same_metadata,
                       (p.detail_type IS NOT DISTINCT FROM l.detail_type AND p.detail_id IS NOT DISTINCT FROM l.detail_id) AS same_detail
                  FROM double_entry_lines l
                  LEFT JOIN double_entry_lines p ON p.id = l.partner_id
                 WHERE l.metadata->>'writer' = ?
                 ORDER BY md5(l.id::text)
                 LIMIT 10
                """.trimIndent(),
                writer,
            ).map { row -> row + mapOf("created_at" to parseUtc(row["created_at"]), "updated_at" to parseUtc(row["updated_at"])) }

    /** Timestamps are read as text, so no JDBC time-zone conversion can hide a writer that stored local time. */
    private fun parseUtc(value: Any?): LocalDateTime = LocalDateTime.parse(value as String)

    /**
     * Kotlin `LineCheckService.perform()` and then Ruby `LineCheck.perform!` over the whole mixed ledger: no errors
     * from either, and each checked up to the last line. The Kotlin check row is removed before the Ruby run, which
     * would otherwise start after it and visit no line.
     */
    private fun assertLineChecksPass() {
        val lastLineId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM double_entry_lines", Long::class.java)!!

        val kotlinCheck = lineCheckService.perform().shouldNotBeNull()
        withClue("Kotlin LineCheck log: ${kotlinCheck.log}") {
            kotlinCheck.errorsFound shouldBe false
            kotlinCheck.lastLineId shouldBe lastLineId
        }

        jdbcTemplate.update("DELETE FROM double_entry_line_checks")
        val container = RubyWriter.container(network, DATABASE_URL_IN_NETWORK, mapOf("MODE" to "check"))
        val rubyCheck =
            try {
                container.start()
                RubyWriter.awaitSummary(container, Duration.ofMinutes(3)).lineCheck.shouldNotBeNull()
            } finally {
                container.stop()
            }
        withClue("Ruby LineCheck: $rubyCheck") {
            rubyCheck["errors_found"].asBoolean() shouldBe false
            rubyCheck["last_line_id"].asLong() shouldBe lastLineId
        }
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM double_entry_line_checks WHERE errors_found", Long::class.java) shouldBe 0L
    }

    private fun utcNow(): LocalDateTime = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)

    private fun key(
        account: String,
        scope: String?,
    ) = if (scope == null) account else "$account@$scope"

    companion object {
        val SCOPED = listOf("account-0", "account-1", "account-2")
        val UNSCOPED = listOf("account-3", "account-4")
        val ALL = SCOPED + UNSCOPED
        const val CODE = "test"

        private const val RUBY_THREADS = 4
        private const val KOTLIN_THREADS = 4
        private const val TRANSFERS_PER_WRITER = 500
        private const val TOTAL_LINES = 2 * 2 * TRANSFERS_PER_WRITER
        private const val SEED = 20_141_013L
        private const val DETAIL_ID = 1L
        private const val GATE_LOCK = 1_017L
        private const val CLOCK_TOLERANCE_SECONDS = 60L
        private const val METADATA_SHAPE = "seq:number,tags:array,worker:number,writer:string"
        private val DETAIL_FORMS = setOf("User#1", "null#null")
        private val EXPECTED_KEYS = (SCOPED.map { "$it@1" } + UNSCOPED).toSet()

        private const val POSTGRES_ALIAS = "postgres"

        private val network: Network by lazy { Network.newNetwork() }

        /** A container of its own (not [DoubleEntryPostgres.container]): it must sit on [network] for the Ruby writer. */
        private val postgres: PostgreSQLContainer<*> by lazy {
            DoubleEntryPostgres
                .newContainer()
                .withNetwork(network)
                .withNetworkAliases(POSTGRES_ALIAS)
                .also { it.start() }
        }

        private val DATABASE_URL_IN_NETWORK: String
            get() = "postgres://${postgres.username}:${postgres.password}@$POSTGRES_ALIAS:5432/${postgres.databaseName}"

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }
}
