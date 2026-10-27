package com.softberries.doubleentry.parity.validation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.softberries.doubleentry.DoubleEntry
import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.balance.cache.AccountBalanceRepository
import com.softberries.doubleentry.config.DoubleEntryTables
import com.softberries.doubleentry.errors.AccountWouldBeSentNegative
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
import com.softberries.doubleentry.parity.SqlProtocolStep
import com.softberries.doubleentry.parity.config.ConfigAccountParityConfiguration
import com.softberries.doubleentry.parity.config.KotlinOutcome
import com.softberries.doubleentry.parity.transfer.TransferLineOutcomes
import com.softberries.doubleentry.parity.transfer.TransferLineSerializer
import com.softberries.doubleentry.testing.PostgresIntegrationTest
import com.softberries.doubleentry.validation.AccountFixer
import com.softberries.doubleentry.validation.LineCheck
import com.softberries.doubleentry.validation.LineCheckService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.readText

/**
 * Parity driver for the validation area (M-022): replays every `linecheck_*` and `accountfixer_*` scenario of
 * `oracle/api/json` against [LineCheckService] and [AccountFixer] and compares result or exception (class and message
 * exactly), `db_after` rows, the double_entry SQL protocol (D-017) and notifications. It shares the Spring context of
 * the M-018..M-021 drivers (standard configuration, SQL and notification capture); `Timecop.freeze` is [clock].
 *
 * The three scenarios whose log holds record dumps (`linecheck_line_error_no_fixer`, `linecheck_line_error_with_fixer`,
 * `linecheck_unscoped_accounts`) are X-06 deviations (`parity/validation.deviations.json`): Ruby writes
 * `ActiveRecord#inspect`, Kotlin its documented rendering (D-011, BR-095). They are still run: the textual comparison
 * must differ, and the Kotlin expectation (header lines byte-identical, dumps equal field by field,
 * [LineCheckLogComparator]) plus every other comparison must match. The report is `build/reports/parity/validation.md`.
 */
@Import(ConfigAccountParityConfiguration::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ValidationParityTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var doubleEntry: DoubleEntry

    @Autowired
    private lateinit var lineCheck: LineCheckService

    @Autowired
    private lateinit var fixer: AccountFixer

    @Autowired
    private lateinit var accountBalances: AccountBalanceRepository

    @Autowired
    private lateinit var resolver: AccountResolver

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

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

    /** `xfer(100_00, acct(:bank), acct(:savings, u), :deposit)`, the fixture of most scenarios. */
    private fun deposit(minor: Long = 100_00) = xfer(minor, acct("bank"), acct("savings", 1), "deposit")

    /** `update_all` on raw columns: no timestamps are touched, as in the Ruby scenarios. */
    private fun updateAll(statement: String) = jdbcTemplate.update(statement)

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

    /**
     * `LineCheck.create!(last_line_id:, errors_found: false, log: '')` under frozen time: the library writes check
     * rows only from [LineCheckService.perform], so the test inserts the row itself, in its own transaction as
     * `create!` does.
     */
    private fun createCheck(lastLineId: Long) {
        val now = clock.instant().atOffset(ZoneOffset.UTC).toLocalDateTime()
        TransactionTemplate(transactionManager).executeWithoutResult {
            jdbcTemplate.update(
                "INSERT INTO double_entry_line_checks (last_line_id, errors_found, log, created_at, updated_at) VALUES (?, false, '', ?, ?)",
                lastLineId,
                now,
                now,
            )
        }
    }

    private fun perform(withFixer: Boolean = false): Map<String, Any?>? = lineCheck.perform(fixer.takeIf { withFixer })?.toOracleShape()

    /** One scenario: the Kotlin port of its `tools/oracle.rb:394-441` block. */
    private class Case(
        val run: ValidationParityTest.() -> Any?,
    )

    @Order(1)
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarioIds")
    fun `replays the oracle scenario`(id: String) {
        val oracle = OracleScenarios.load(id)
        val case = CASES[id]
        if (case == null) {
            report.record(id, Comparison.of("scenario is not driven by ValidationParityTest"))
            throw AssertionError("$id: no Kotlin driver case")
        }
        val outcome = TransferLineSerializer.outcome { case.run(this) }
        // Before the snapshot: its SELECTs go through the captured data source too.
        val actualSql = SqlCapture.withoutReadOnlyTransactions(sqlCapture.protocolSteps)
        val actualDb = DbSnapshot.read(jdbcTemplate.dataSource!!)
        val expectedSql = SqlCapture.withoutReadOnlyTransactions(SqlCapture.protocol(SqlCapture.fromRubyLog(oracle.sql)))
        // Every scenario issues double_entry SQL, so an empty protocol would make the comparison vacuous.
        expectedSql.shouldNotBeEmpty()
        val sideEffects =
            sqlComparison(expectedSql, actualSql) + NotificationCapture.compare(oracle.notifications, notifications.notifications)
        val vsOracle =
            TransferLineOutcomes.compare(oracle.result, oracle.exception, outcome) +
                RowComparator.compare(oracle.dbAfter, actualDb) +
                sideEffects
        val recorded = report.record(id, vsOracle)

        val deviation = report.deviations.deviationFor(id)
        if (deviation == null) {
            vsOracle.assertMatches(id)
        } else {
            deviation.xRef shouldBe "X-06"
            (x06Comparison(oracle.result, outcome, oracle.dbAfter, actualDb) + sideEffects)
                .assertMatches("$id Kotlin expectation (${deviation.xRef})")
            // A listed deviation that no longer differs from Ruby must be removed from the deviation file.
            vsOracle.matches shouldBe false
            recorded.status shouldBe ParityStatus.DEVIATION
        }
    }

    private fun sqlComparison(
        expected: List<SqlProtocolStep>,
        actual: List<SqlProtocolStep>,
    ) = Comparison.of("sql: expected $expected but was $actual".takeUnless { expected == actual })

    /**
     * The X-06 Kotlin expectation: everything as in the oracle except the `log` texts, which are compared with
     * [LineCheckLogComparator] (header lines byte-identical, record dumps field by field).
     */
    private fun x06Comparison(
        expectedResult: JsonNode?,
        outcome: KotlinOutcome,
        expectedDb: Map<String, List<ObjectNode>>,
        actualDb: Map<String, List<ObjectNode>>,
    ): Comparison {
        val lines = LineCheckLogComparator.linesById(actualDb)
        val resultLog =
            LineCheckLogComparator.compare("result log", logOf(expectedResult), logOf(outcome.result), lines)
        val rowLogs =
            expectedDb[DoubleEntryTables.LINE_CHECKS].orEmpty().map { row ->
                val id = row["id"].asLong()
                val other = actualDb[DoubleEntryTables.LINE_CHECKS].orEmpty().firstOrNull { it["id"].asLong() == id }
                LineCheckLogComparator.compare("line_checks id=$id log", row["log"]?.textValue(), other?.get("log")?.textValue(), lines)
            }
        return TransferLineOutcomes.compare(
            withoutLog(expectedResult),
            null,
            KotlinOutcome(withoutLog(outcome.result), outcome.exception),
        ) +
            resultLog +
            RowComparator.compare(withoutLogColumn(expectedDb), withoutLogColumn(actualDb)) +
            rowLogs.fold(Comparison.MATCH, Comparison::plus)
    }

    private fun logOf(result: JsonNode?): String? = result?.get("attributes")?.get("log")?.textValue()

    private fun withoutLog(result: JsonNode?): JsonNode? =
        result?.deepCopy<JsonNode>()?.also { (it.get("attributes") as? ObjectNode)?.remove("log") }

    private fun withoutLogColumn(db: Map<String, List<ObjectNode>>): Map<String, List<ObjectNode>> =
        db.mapValues { (table, rows) ->
            if (table == DoubleEntryTables.LINE_CHECKS) rows.map { row -> row.deepCopy().also { it.remove("log") } } else rows
        }

    private fun run(id: String): Any? {
        cleanDatabase()
        return CASES.getValue(id).run(this)
    }

    @Suppress("UNCHECKED_CAST")
    private fun attributes(result: Any?): Map<String, Any?> = (result as Map<String, Any?>)["attributes"] as Map<String, Any?>

    @Test
    fun `linecheck result rows match on last_line_id and errors_found, and log header lines are byte-identical`() {
        val rule = "*".repeat(33)
        for (id in CASES.keys.filter { it.startsWith("linecheck_") && OracleScenarios.load(it).result?.has("attributes") == true }) {
            val expected = OracleScenarios.load(id).result!!["attributes"]
            val actual = attributes(run(id))
            actual["last_line_id"] shouldBe expected["last_line_id"].asLong()
            actual["errors_found"] shouldBe expected["errors_found"].asBoolean()
            val headers = { log: String -> log.split("\n").filterNot { it.startsWith("  ") } }
            headers(actual["log"] as String) shouldBe headers(expected["log"].asText())
        }
        val log = attributes(run("linecheck_line_error_no_fixer"))["log"] as String
        log shouldStartWith "$rule\nError on line #1: balance:-99.99 != 0.00 + -100.00\n$rule\n"
        rule.length shouldBe 33
    }

    @Test
    fun `record dumps are compared field by field, not textually (X-06)`() {
        for (id in listOf("linecheck_line_error_no_fixer", "linecheck_line_error_with_fixer", "linecheck_unscoped_accounts")) {
            val expectedLog = OracleScenarios.load(id).result!!["attributes"]["log"].asText()
            val actualLog = attributes(run(id))["log"] as String
            val lines = LineCheckLogComparator.linesById(DbSnapshot.read(jdbcTemplate.dataSource!!))
            // Textually different (Ruby inspect vs the Kotlin rendering) ...
            (actualLog == expectedLog) shouldBe false
            // ... but equal field by field.
            LineCheckLogComparator.compare(id, expectedLog, actualLog, lines).assertMatches(id)
            val rubyDumps = expectedLog.split("\n").filter { it.startsWith("  #") }.map { LineCheckLogComparator.parse(it, ruby = true) }
            val kotlinDumps =
                actualLog
                    .split(
                        "\n",
                    ).filter { it.startsWith("  Line(") }
                    .map { LineCheckLogComparator.parse(it, ruby = false) }
            kotlinDumps shouldHaveSize rubyDumps.size
            kotlinDumps.flatMap { it.records }.forEach { record ->
                record.fields.map { it.first } shouldContainExactly
                    listOf(
                        "id",
                        "account",
                        "scope",
                        "code",
                        "amount",
                        "balance",
                        "partner_id",
                        "partner_account",
                        "partner_scope",
                        "detail_type",
                        "detail_id",
                        "metadata",
                        "created_at",
                        "updated_at",
                    )
            }
        }
    }

    @Test
    fun `the field-by-field comparison catches a wrong field value`() {
        val id = "linecheck_line_error_no_fixer"
        val expectedLog = OracleScenarios.load(id).result!!["attributes"]["log"].asText()
        val actualLog = attributes(run(id))["log"] as String
        val lines = LineCheckLogComparator.linesById(DbSnapshot.read(jdbcTemplate.dataSource!!))
        val tampered = actualLog.replace("balance: -9999,", "balance: -9998,")
        LineCheckLogComparator.compare(id, expectedLog, tampered, lines).differences shouldBe
            listOf("$id line 5 balance: expected <-9999> but was <-9998>")
        val badHeader = actualLog.replaceFirst("Error on line #1", "Error on line  #1")
        LineCheckLogComparator.compare(id, expectedLog, badHeader, lines).matches shouldBe false
    }

    @Test
    fun `last_line_id_checked gives 0 then 300, and runs without new lines give null and write no row`() {
        run("linecheck_last_line_id_checked") shouldBe linkedMapOf("none" to 0L, "after" to 300L)
        run("linecheck_no_lines").shouldBeNull()
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM double_entry_line_checks", Long::class.java) shouldBe 0L
        run("linecheck_second_run_nothing_new").shouldBeNull()
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM double_entry_line_checks", Long::class.java) shouldBe 1L
    }

    @Test
    fun `linecheck_incremental writes its checks at T0 and T0+1h and the second covers lines 3 and 4 only`() {
        attributes(run("linecheck_incremental"))["created_at"] shouldBe T0 + Duration.ofHours(1)
        jdbcTemplate.queryForList("SELECT last_line_id FROM double_entry_line_checks ORDER BY id", Long::class.java) shouldContainExactly
            listOf(2L, 4L)
    }

    @Test
    fun `accountfixer_standalone gives cash 15_00 and cache 15_00`() {
        @Suppress("UNCHECKED_CAST")
        val result = run("accountfixer_standalone") as Map<String, Money>
        result.getValue("cash").toString() shouldBe "15.00"
        result.getValue("cached").toString() shouldBe "15.00"
    }

    @Test
    fun `accountfixer_positive_only_goes_negative throws the oracle message and leaves the rows unchanged`() {
        val oracle = OracleScenarios.load("accountfixer_positive_only_goes_negative")
        val error = shouldThrow<AccountWouldBeSentNegative> { run("accountfixer_positive_only_goes_negative") }
        error.message shouldBe oracle.exception.shouldNotBeNull().message
        error.message shouldBe "#{Account account: savings scope:  currency: USD}"
        RowComparator.compare(oracle.dbAfter, DbSnapshot.read(jdbcTemplate.dataSource!!)).assertMatches(oracle.id)
        jdbcTemplate.queryForList("SELECT balance FROM double_entry_lines ORDER BY id", Long::class.java) shouldContainExactly
            listOf(-500L, 500L, 0L, 500L)
    }

    @Order(2)
    @Test
    fun `every scenario of the area is driven and the report has no mismatches`() {
        CASES.keys shouldContainExactlyInAnyOrder ParityArea.VALIDATION.scenarioIds
        report.deviations.entries.keys shouldContainExactlyInAnyOrder
            listOf("linecheck_line_error_no_fixer", "linecheck_line_error_with_fixer", "linecheck_unscoped_accounts")
        report.assertParity()
        val written = ParityReport.reportDir().resolve("${ParityArea.VALIDATION.key}.md").readText()
        ParityArea.VALIDATION.scenarioIds.forEach { id ->
            val row = written.lines().single { it.startsWith("| $id |") }
            row.contains(if (id in report.deviations.entries) "| deviation (X-06) |" else "| match |") shouldBe true
        }
        report.outcomes().count { it.status == ParityStatus.MISMATCH || it.status == ParityStatus.NOT_RUN } shouldBe 0
    }

    companion object {
        private val report = ParityReport(ParityArea.VALIDATION)

        @JvmStatic
        fun scenarioIds(): List<String> = ParityArea.VALIDATION.scenarioIds

        /** `UPDATE … WHERE (id) IN (SELECT id … ORDER BY id ASC LIMIT 1)`: the SQL of `order(:id).limit(1).update_all`. */
        private fun firstRow(
            table: String,
            set: String,
            where: String = "",
        ) = "UPDATE $table SET $set WHERE ($table.id) IN (SELECT $table.id FROM $table $where ORDER BY $table.id ASC LIMIT 1)"

        /** The scenario blocks of `tools/oracle.rb:394-441`, in the same order. */
        private val CASES: Map<String, Case> =
            mapOf(
                "linecheck_no_lines" to Case { perform() },
                "linecheck_consistent" to
                    Case {
                        deposit()
                        perform()
                    },
                "linecheck_second_run_nothing_new" to
                    Case {
                        deposit()
                        perform()
                        perform()
                    },
                // BR-090: the second run starts after the newest check by created_at.
                "linecheck_incremental" to
                    Case {
                        frozen(T0) {
                            deposit()
                            perform()
                        }
                        frozen(T0 + Duration.ofHours(1)) {
                            deposit(1_00)
                            perform()
                        }
                    },
                "linecheck_line_error_no_fixer" to
                    Case {
                        deposit()
                        updateAll(firstRow(DoubleEntryTables.LINES, "balance = balance + 1"))
                        perform()
                    },
                "linecheck_line_error_with_fixer" to
                    Case {
                        deposit()
                        updateAll(firstRow(DoubleEntryTables.LINES, "balance = balance + 1"))
                        perform(withFixer = true)
                    },
                "linecheck_account_balance_error_with_fixer" to
                    Case {
                        deposit()
                        updateAll(firstRow(DoubleEntryTables.ACCOUNT_BALANCES, "balance = balance + 1"))
                        perform(withFixer = true)
                    },
                "linecheck_unscoped_accounts" to
                    Case {
                        xfer(10_00, acct("bank"), acct("cash"), "salary")
                        xfer(3_00, acct("cash"), acct("store"), "purchase")
                        updateAll(
                            firstRow(DoubleEntryTables.LINES, "balance = balance + 7", "WHERE ${DoubleEntryTables.LINES}.account = 'cash'"),
                        )
                        perform(withFixer = true)
                    },
                "linecheck_inside_transaction" to
                    Case {
                        deposit(1_00)
                        TransactionTemplate(transactionManager).execute { perform() }
                    },
                "accountfixer_positive_only_goes_negative" to
                    Case {
                        deposit(5_00)
                        xfer(5_00, acct("savings", 1), acct("checking", 1), "withdraw")
                        // `Line.where(account: 'savings').order(:id).first.tap { |l| Line.where(id: l.id).update_all(amount: 4_00) }`
                        val id =
                            jdbcTemplate.queryForObject(
                                "SELECT * FROM double_entry_lines WHERE account = 'savings' ORDER BY id ASC LIMIT 1",
                            ) { rs, _ -> rs.getLong("id") }
                        jdbcTemplate.update("UPDATE double_entry_lines SET amount = 400 WHERE id = ?", id)
                        fixer.recalculateAccount(acct("savings", 1))
                    },
                "accountfixer_standalone" to
                    Case {
                        val (b, c) = listOf(acct("bank"), acct("cash"))
                        xfer(10_00, b, c, "salary")
                        xfer(5_00, b, c, "bonus")
                        updateAll("UPDATE double_entry_lines SET balance = 0 WHERE account = 'cash'")
                        updateAll("UPDATE double_entry_account_balances SET balance = 0 WHERE account = 'cash'")
                        fixer.recalculateAccount(c)
                        linkedMapOf(
                            "cash" to doubleEntry.balance(c),
                            "cached" to checkNotNull(accountBalances.findByAccount(c)).balance(resolver),
                        )
                    },
                "linecheck_last_line_id_checked" to
                    Case {
                        val none = lineCheck.lastLineIdChecked()
                        frozen(T0 - Duration.ofMinutes(3)) { createCheck(100) }
                        frozen(T0 - Duration.ofMinutes(1)) { createCheck(300) }
                        frozen(T0 - Duration.ofMinutes(2)) { createCheck(200) }
                        linkedMapOf("none" to none, "after" to lineCheck.lastLineIdChecked())
                    },
            )
    }
}
