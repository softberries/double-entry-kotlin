package com.softberries.doubleentry.parity.balance

import com.softberries.doubleentry.DoubleEntry
import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.balance.BalanceOptions
import com.softberries.doubleentry.balance.cache.AccountBalanceRepository
import com.softberries.doubleentry.config.DoubleEntryTables
import com.softberries.doubleentry.errors.UnknownAccount
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.parity.Comparison
import com.softberries.doubleentry.parity.DbSnapshot
import com.softberries.doubleentry.parity.NotificationCapture
import com.softberries.doubleentry.parity.OracleFixtureConfig
import com.softberries.doubleentry.parity.OracleFixtureConfig.T0
import com.softberries.doubleentry.parity.OracleScenarios
import com.softberries.doubleentry.parity.ParityArea
import com.softberries.doubleentry.parity.ParityReport
import com.softberries.doubleentry.parity.ParityStatus
import com.softberries.doubleentry.parity.RowComparator
import com.softberries.doubleentry.parity.SqlCapture
import com.softberries.doubleentry.parity.SqlShape
import com.softberries.doubleentry.parity.config.ConfigAccountParityConfiguration
import com.softberries.doubleentry.parity.transfer.TransferLineOutcomes
import com.softberries.doubleentry.parity.transfer.TransferLineSerializer
import com.softberries.doubleentry.testing.PostgresIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.io.path.readText

/**
 * Parity driver for the balances area (M-020): replays every `balance_*` scenario of `oracle/api/json` against the
 * Kotlin API and compares result or exception (class and message exactly), `db_after` rows (frozen T0 timestamps
 * exactly), the double_entry SQL protocol (D-017) and notifications. It shares the Spring context of the M-018/M-019
 * drivers (standard configuration, SQL and notification capture); the oracle's `users` rows are
 * [OracleFixtureConfig.user]s and `Timecop.freeze` is the context's [clock].
 *
 * The fixture of most scenarios (`balance_fixture`, `tools/oracle.rb:321-327`) is seeded the way the oracle seeds it:
 * five transfers under frozen time, so `db_after` and the SQL protocol of the seeding are compared too. The area has no
 * deviations (`parity/balances.deviations.json` is empty). The report is `build/reports/parity/balances.md`.
 */
@Import(ConfigAccountParityConfiguration::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BalanceParityTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var doubleEntry: DoubleEntry

    @Autowired
    private lateinit var accountBalances: AccountBalanceRepository

    @Autowired
    private lateinit var resolver: AccountResolver

    @Autowired
    private lateinit var sqlCapture: SqlCapture

    @Autowired
    private lateinit var notifications: NotificationCapture

    @BeforeEach
    fun clearCaptures() {
        sqlCapture.clear()
        notifications.clear()
    }

    private fun acct(
        identifier: String,
        user: Int? = null,
    ): AccountInstance =
        if (user == null) doubleEntry.account(identifier) else doubleEntry.account(identifier, scope = OracleFixtureConfig.user(user))

    /** `Money.new(cents, from.currency)` of `xfer` (`tools/oracle.rb:144`). */
    private fun xfer(
        minor: Long,
        from: AccountInstance,
        to: AccountInstance,
        code: String,
    ) = doubleEntry.transfer(Money(minor, from.currency), from, to, code)

    private fun usd(minor: Long) = Money(minor, CurrencyUnit.USD)

    private fun days(n: Long): Duration = Duration.ofDays(n)

    /** `Timecop.freeze(at) { block }`. */
    private fun <T> frozen(
        at: Instant,
        block: () -> T,
    ): T {
        clock.set(at)
        try {
            return block()
        } finally {
            clock.reset()
        }
    }

    /** The oracle `balance_fixture` (`tools/oracle.rb:321-327`): bank, cash and store with lines at T0-21d, -14d, -7d, T0. */
    private fun balanceFixture(): Fixture {
        val (b, c, s) = listOf(acct("bank"), acct("cash"), acct("store"))
        frozen(T0 - days(21)) { xfer(1_000_00, b, c, "salary") }
        frozen(T0 - days(14)) {
            xfer(200_00, c, s, "purchase")
            xfer(100_00, c, s, "layby")
        }
        frozen(T0 - days(7)) { xfer(50_00, b, c, "bonus") }
        frozen(T0) { xfer(25_00, c, s, "purchase") }
        return Fixture(b, c, s)
    }

    private data class Fixture(
        val bank: AccountInstance,
        val cash: AccountInstance,
        val store: AccountInstance,
    )

    private fun balance(
        account: AccountInstance,
        options: BalanceOptions = BalanceOptions(),
    ): Money = doubleEntry.balance(account, options)

    /** One scenario: the Kotlin port of its `tools/oracle.rb` block. */
    private class Case(
        val run: BalanceParityTest.() -> Any?,
    )

    @Order(1)
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarioIds")
    fun `replays the oracle scenario`(id: String) {
        val oracle = OracleScenarios.load(id)
        val case = CASES[id]
        if (case == null) {
            report.record(id, Comparison.of("scenario is not driven by BalanceParityTest"))
            throw AssertionError("$id: no Kotlin driver case")
        }
        val outcome = TransferLineSerializer.outcome { case.run(this) }
        // Before the snapshot: its SELECTs go through the captured data source too.
        val actualSql = SqlCapture.withoutReadOnlyTransactions(sqlCapture.protocolSteps)
        val actualDb = DbSnapshot.read(jdbcTemplate.dataSource!!)
        val expectedSql = SqlCapture.withoutReadOnlyTransactions(SqlCapture.protocol(SqlCapture.fromRubyLog(oracle.sql)))
        val vsOracle =
            TransferLineOutcomes.compare(oracle.result, oracle.exception, outcome) +
                Comparison.of("sql: expected $expectedSql but was $actualSql".takeUnless { expectedSql == actualSql }) +
                RowComparator.compare(oracle.dbAfter, actualDb) +
                NotificationCapture.compare(oracle.notifications, notifications.notifications)
        report.record(id, vsOracle)
        // The area has no deviations: every scenario must match the oracle.
        report.deviations.entries.shouldBeEmpty()
        vsOracle.assertMatches(id)
    }

    @Test
    fun `spot values of the acceptance criteria`() {
        fun run(id: String): Any? {
            cleanDatabase()
            return CASES.getValue(id).run(this)
        }

        @Suppress("UNCHECKED_CAST")
        fun moneys(id: String) = run(id) as Map<String, Any?>

        val current = moneys("balance_current")
        current["cash"] shouldBe usd(725_00)
        current["store"] shouldBe usd(325_00)
        current["bank"] shouldBe usd(-1_050_00)
        (run("balance_between_inclusive") as Money).toString() shouldBe "-250.00"
        (run("balance_codes") as Money).toString() shouldBe "325.00"
        val byId = moneys("balance_order_by_id_not_time")
        listOf("current", "at_T0", "at_T0_minus_1d").map { (byId[it] as Money).toString() } shouldBe List(3) { "11.00" }
        val noLines = moneys("balance_no_lines")
        (noLines["btc"] as Money).toString() shouldBe "0.00000000"
        (noLines["btc"] as Money).currency shouldBe CurrencyUnit.of("BTC")
        (noLines["jpy"] as Money).toString() shouldBe "0"

        val scopes = moneys("balance_scopes_with_minimum_balance").mapValues { (_, v) -> (v as List<*>).toSet() }
        scopes shouldBe
            linkedMapOf(
                "min_100" to setOf("1"),
                "min_99.99" to setOf("1", "2"),
                "min_100_BTC_money" to setOf("1"),
                "unscoped_bank_min_-1000000" to setOf(null),
                "string_identifier" to setOf("1", "2", "3"),
            )
    }

    @Test
    fun `balance by identifier through the facade (BR-049)`() {
        (CASES.getValue("balance_symbol_with_scope").run(this) as Money).toString() shouldBe "3.00"
        cleanDatabase()
        sqlCapture.clear()
        val error = shouldThrow<UnknownAccount> { doubleEntry.balance("checking") }
        error.message shouldBe "account: checking scoped?: false"
        // The account is resolved before any query runs.
        sqlCapture.shapes shouldBe emptyList()
    }

    @Test
    fun `balance_current reads the latest line by id with no scope IS NULL predicate (BR-040)`() {
        fun balanceReads(shapes: List<SqlShape>) = shapes.filter { it.verb == "SELECT" && it.table == DoubleEntryTables.LINES }.takeLast(3)

        CASES.getValue("balance_current").run(this)
        val kotlin = balanceReads(sqlCapture.shapes)
        val ruby = balanceReads(SqlCapture.fromRubyLog(OracleScenarios.load("balance_current").sql))

        kotlin shouldHaveSize 3
        kotlin.forEach { shape ->
            shape.orderBy shouldContainExactly listOf("id DESC")
            shape.limit shouldBe 1L
            shape.whereColumns shouldContainExactly listOf("account")
            shape.whereColumns shouldNotContain "scope IS NULL"
            shape.lock shouldBe null
        }
        kotlin.map { listOf(it.whereColumns, it.orderBy, it.limit, it.lock) } shouldBe
            ruby.map { listOf(it.whereColumns, it.orderBy, it.limit, it.lock) }
    }

    @Test
    fun `fixture rows carry the frozen timestamps exactly`() {
        balanceFixture()
        val stamps =
            DbSnapshot.read(jdbcTemplate.dataSource!!).getValue(DoubleEntryTables.LINES).map { it["created_at"].asText() }
        val expected =
            OracleScenarios
                .load(
                    "balance_fixture_lines",
                ).dbAfter
                .getValue(DoubleEntryTables.LINES)
                .map { it["created_at"].asText() }
        stamps.map(Instant::parse) shouldBe expected.map(Instant::parse)
        expected.map(Instant::parse).distinct() shouldBe listOf(T0 - days(21), T0 - days(14), T0 - days(7), T0)
    }

    @Order(2)
    @Test
    fun `every scenario of the area is driven and the report has no mismatches or deviations`() {
        CASES.keys shouldContainExactlyInAnyOrder ParityArea.BALANCES.scenarioIds
        report.assertParity()
        val written = ParityReport.reportDir().resolve("${ParityArea.BALANCES.key}.md").readText()
        ParityArea.BALANCES.scenarioIds.forEach { id ->
            written.lines().single { it.startsWith("| $id |") }.contains("| match |") shouldBe true
        }
        report.outcomes().count { it.status != ParityStatus.MATCH } shouldBe 0
    }

    companion object {
        private val report = ParityReport(ParityArea.BALANCES)

        @JvmStatic
        fun scenarioIds(): List<String> = ParityArea.BALANCES.scenarioIds

        /** `T0.getlocal('+10:00')`: the same instant with a +10:00 offset (BR-046). */
        private val T0_PLUS_10: OffsetDateTime = T0.atOffset(ZoneOffset.ofHours(10))

        /** The scenario blocks of `tools/oracle.rb:329-370`, in the same order. */
        private val CASES: Map<String, Case> =
            mapOf(
                // `DoubleEntry::Line.count`: no library API counts lines, so the test reads the table itself.
                "balance_fixture_lines" to
                    Case {
                        balanceFixture()
                        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM double_entry_lines", Long::class.java)
                    },
                "balance_current" to
                    Case {
                        val (_, c, s) = balanceFixture()
                        linkedMapOf("cash" to balance(c), "store" to balance(s), "bank" to doubleEntry.balance("bank"))
                    },
                "balance_no_lines" to
                    Case {
                        linkedMapOf(
                            "cash" to balance(acct("cash")),
                            "btc" to balance(acct("btc_store")),
                            "jpy" to balance(acct("jpy_bank")),
                        )
                    },
                "balance_symbol_with_scope" to
                    Case {
                        xfer(3_00, acct("bank"), acct("checking", 1), "deposit")
                        doubleEntry.balance("checking", scope = OracleFixtureConfig.user(1))
                    },
                "balance_scoped_without_scope" to Case { doubleEntry.balance("checking") },
                "balance_at_exact_timestamp" to Case { balance(balanceFixture().cash, BalanceOptions(at = T0 - days(14))) },
                "balance_at_before_first" to Case { balance(balanceFixture().cash, BalanceOptions(at = T0 - days(30))) },
                "balance_at_between" to Case { balance(balanceFixture().cash, BalanceOptions(at = T0 - days(10))) },
                "balance_between_inclusive" to
                    Case { balance(balanceFixture().cash, BalanceOptions(from = T0 - days(14), to = T0 - days(7))) },
                "balance_between_empty" to
                    Case { balance(balanceFixture().cash, BalanceOptions(from = T0 - days(40), to = T0 - days(30))) },
                "balance_from_only" to Case { balance(balanceFixture().cash, BalanceOptions(from = T0 - days(8))) },
                "balance_to_only" to Case { balance(balanceFixture().cash, BalanceOptions(to = T0 - days(20))) },
                "balance_at_overrides_range" to
                    Case {
                        balance(balanceFixture().cash, BalanceOptions(at = T0 - days(10), from = T0 - days(40), to = T0 - days(30)))
                    },
                "balance_inverted_range" to Case { balance(balanceFixture().cash, BalanceOptions(from = T0, to = T0 - days(21))) },
                "balance_code" to Case { balance(balanceFixture().store, BalanceOptions(code = "purchase")) },
                "balance_codes" to Case { balance(balanceFixture().store, BalanceOptions(codes = listOf("purchase", "layby"))) },
                "balance_code_and_codes" to
                    Case { balance(balanceFixture().store, BalanceOptions(code = "layby", codes = listOf("purchase"))) },
                "balance_code_unknown" to Case { balance(balanceFixture().store, BalanceOptions(code = "nope")) },
                "balance_code_and_at" to Case { balance(balanceFixture().cash, BalanceOptions(code = "salary", at = T0 - days(20))) },
                "balance_code_and_range" to
                    Case {
                        balance(balanceFixture().store, BalanceOptions(code = "purchase", from = T0 - days(15), to = T0 - days(13)))
                    },
                "balance_codes_empty_array" to Case { balance(balanceFixture().cash, BalanceOptions(codes = emptyList())) },
                "balance_order_by_id_not_time" to
                    Case {
                        val (b, c) = listOf(acct("bank"), acct("cash"))
                        frozen(T0) { xfer(10_00, b, c, "salary") }
                        frozen(T0 - days(5)) { xfer(1_00, b, c, "bonus") }
                        linkedMapOf(
                            "current" to balance(c),
                            "at_T0" to balance(c, BalanceOptions(at = T0)),
                            "at_T0_minus_1d" to balance(c, BalanceOptions(at = T0 - days(1))),
                            // `Line.where(account: 'cash').order(:id).map { [id, created_at.utc.iso8601, balance] }`
                            "lines" to
                                jdbcTemplate.query(
                                    "SELECT * FROM double_entry_lines WHERE account = ? ORDER BY id ASC",
                                    { rs, _ ->
                                        listOf(
                                            rs.getLong("id"),
                                            rs
                                                .getObject("created_at", java.time.LocalDateTime::class.java)
                                                .toInstant(ZoneOffset.UTC)
                                                .truncatedTo(ChronoUnit.SECONDS)
                                                .toString(),
                                            rs.getLong("balance"),
                                        )
                                    },
                                    "cash",
                                ),
                        )
                    },
                "balance_time_with_offset" to Case { balance(balanceFixture().cash, BalanceOptions.of(at = T0_PLUS_10)) },
                "balance_reads_lines_not_cache" to
                    Case {
                        val (b, c) = listOf(acct("bank"), acct("cash"))
                        xfer(10_00, b, c, "salary")
                        // `AccountBalance.where(account: 'cash').update_all(balance: 999)`: tamper with the cache (BR-048).
                        jdbcTemplate.update("UPDATE double_entry_account_balances SET balance = ? WHERE account = ?", 999L, "cash")
                        linkedMapOf("balance" to balance(c), "cached" to checkNotNull(accountBalances.findByAccount(c)).balance(resolver))
                    },
                "balance_scopes_with_minimum_balance" to
                    Case {
                        xfer(100_00, acct("bank"), acct("checking", 1), "deposit")
                        xfer(99_99, acct("bank"), acct("checking", 2), "deposit")
                        xfer(1_00, acct("bank"), acct("checking", 3), "deposit")

                        fun scopes(
                            minimum: Money,
                            account: String,
                        ) = accountBalances.scopesWithMinimumBalanceForAccount(minimum, account)
                        linkedMapOf(
                            "min_100" to scopes(usd(100_00), "checking"),
                            "min_99.99" to scopes(usd(99_99), "checking").sortedWith(nullsFirst()),
                            "min_100_BTC_money" to scopes(Money(100_00, CurrencyUnit.of("BTC")), "checking"),
                            "unscoped_bank_min_-1000000" to scopes(usd(-1_000_000_00), "bank"),
                            // Ruby passes the String 'checking' instead of :checking; Kotlin identifiers are Strings.
                            "string_identifier" to scopes(usd(0), "checking").sortedWith(nullsFirst()),
                        )
                    },
            )
    }
}
