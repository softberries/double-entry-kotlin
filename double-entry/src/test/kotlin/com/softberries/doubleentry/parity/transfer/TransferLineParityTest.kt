package com.softberries.doubleentry.parity.transfer

import com.fasterxml.jackson.databind.node.ObjectNode
import com.softberries.doubleentry.DoubleEntry
import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.account.AccountSet
import com.softberries.doubleentry.balance.BalanceCalculator
import com.softberries.doubleentry.balance.cache.AccountBalanceUpdater
import com.softberries.doubleentry.config.DoubleEntryConfiguration
import com.softberries.doubleentry.config.DoubleEntryTables
import com.softberries.doubleentry.errors.DoubleEntryException
import com.softberries.doubleentry.ledger.DetailRef
import com.softberries.doubleentry.ledger.Line
import com.softberries.doubleentry.ledger.LineCriteria
import com.softberries.doubleentry.ledger.LineEntity
import com.softberries.doubleentry.ledger.LineRepository
import com.softberries.doubleentry.ledger.LineWriter
import com.softberries.doubleentry.ledger.Lines
import com.softberries.doubleentry.ledger.NewLine
import com.softberries.doubleentry.locking.Locking
import com.softberries.doubleentry.locking.LockingSettings
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.parity.Comparison
import com.softberries.doubleentry.parity.DbSnapshot
import com.softberries.doubleentry.parity.NotificationCapture
import com.softberries.doubleentry.parity.OracleException
import com.softberries.doubleentry.parity.OracleFixtureConfig
import com.softberries.doubleentry.parity.OracleScenarios
import com.softberries.doubleentry.parity.ParityArea
import com.softberries.doubleentry.parity.ParityReport
import com.softberries.doubleentry.parity.ParityStatus
import com.softberries.doubleentry.parity.RowComparator
import com.softberries.doubleentry.parity.SqlCapture
import com.softberries.doubleentry.parity.SqlProtocolStep
import com.softberries.doubleentry.parity.config.ConfigAccountParityConfiguration
import com.softberries.doubleentry.parity.transfer.NotExpressible.Companion.transferRequires
import com.softberries.doubleentry.parity.transfer.TransferLineOutcomes.dbAfterWithout
import com.softberries.doubleentry.testing.PostgresIntegrationTest
import com.softberries.doubleentry.transfer.TransferResult
import com.softberries.doubleentry.transfer.TransferService
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OneToOne
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import kotlin.io.path.readText
import kotlin.reflect.full.memberProperties

/**
 * Parity driver for the transfers and lines area (M-019): replays every `transfer_*` and `line_*` scenario of
 * `oracle/api/json` against the Kotlin API and compares result or exception (class and message exactly), `db_after`
 * rows, the double_entry SQL protocol (D-017) and notifications. It shares the Spring context of the M-018 driver
 * (standard configuration, SQL and notification capture); the oracle's `users` rows are [OracleFixtureConfig.user]s.
 *
 * Scenarios listed in `parity/transfers-and-lines.deviations.json` are still run: they must match their
 * [KotlinExpectation] (from `docs/decisions.md`), must differ from the oracle, and are reported as `deviation (X-nn)`
 * with the oracle diff. The report is `build/reports/parity/transfers-and-lines.md`.
 */
@Import(ConfigAccountParityConfiguration::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TransferLineParityTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var doubleEntry: DoubleEntry

    @Autowired
    private lateinit var lines: Lines

    @Autowired
    private lateinit var lineWriter: LineWriter

    @Autowired
    private lateinit var lineRepository: LineRepository

    @Autowired
    private lateinit var resolver: AccountResolver

    @Autowired
    private lateinit var configuration: DoubleEntryConfiguration

    @Autowired
    private lateinit var locking: Locking

    @Autowired
    private lateinit var balanceUpdater: AccountBalanceUpdater

    @Autowired
    private lateinit var calculator: BalanceCalculator

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

    /** One scenario: the Kotlin port of its `tools/oracle.rb` block, and for a deviation the Kotlin expectation. */
    private class Case(
        val kotlinExpectation: KotlinExpectation? = null,
        val run: TransferLineParityTest.() -> Any?,
    )

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
        detail: DetailRef? = null,
        metadata: Map<String, Any?>? = null,
    ) = doubleEntry.transfer(Money(minor, from.currency), from, to, code, detail, metadata)

    private fun usd(minor: Long) = Money(minor, CurrencyUnit.USD)

    private fun money(
        minor: Long,
        currency: String,
    ) = Money(minor, CurrencyUnit.of(currency))

    private fun transaction(propagation: Int = TransactionDefinition.PROPAGATION_REQUIRED) =
        TransactionTemplate(transactionManager).apply { propagationBehavior = propagation }

    /** `DoubleEntry::Locking.configuration.running_inside_transactional_fixtures = true` (D-007), as a separate facade. */
    private fun withFixturesFlag(): DoubleEntry {
        val flagged = locking.copy(settings = LockingSettings(runningInsideTransactionalFixtures = true))
        return DoubleEntry(
            resolver,
            TransferService(configuration.transfers, flagged, lineWriter, balanceUpdater, resolver),
            calculator,
            flagged,
        )
    }

    @Order(1)
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarioIds")
    fun `replays the oracle scenario`(id: String) {
        val oracle = OracleScenarios.load(id)
        val case = CASES[id]
        if (case == null) {
            report.record(id, Comparison.of("scenario is not driven by TransferLineParityTest"))
            throw AssertionError("$id: no Kotlin driver case")
        }
        val outcome = TransferLineSerializer.outcome { case.run(this) }
        // Before the snapshot: its SELECTs go through the captured data source too.
        val actualSql = SqlCapture.withoutReadOnlyTransactions(sqlCapture.protocolSteps)
        val actualDb = DbSnapshot.read(jdbcTemplate.dataSource!!)
        val expectedSql = SqlCapture.withoutReadOnlyTransactions(SqlCapture.protocol(SqlCapture.fromRubyLog(oracle.sql)))
        val vsOracle =
            TransferLineOutcomes.compare(oracle.result, oracle.exception, outcome) +
                sideEffects(oracle.dbAfter, expectedSql, actualDb, actualSql) +
                NotificationCapture.compare(oracle.notifications, notifications.notifications)
        val recorded = report.record(id, vsOracle)

        val deviation = report.deviations.deviationFor(id)
        if (deviation == null) {
            case.kotlinExpectation shouldBe null
            vsOracle.assertMatches(id)
        } else {
            val expectation = checkNotNull(case.kotlinExpectation) { "$id is a deviation (${deviation.xRef}) without a Kotlin expectation" }
            val outcomeCheck =
                expectation.check?.invoke(outcome) ?: TransferLineOutcomes.compare(expectation.result, expectation.exception, outcome)
            (
                outcomeCheck +
                    sideEffects(
                        expectation.dbAfter ?: oracle.dbAfter,
                        expectation.sql?.let(SqlCapture::withoutReadOnlyTransactions) ?: expectedSql,
                        actualDb,
                        actualSql,
                    ) +
                    NotificationCapture.compare(oracle.notifications, notifications.notifications)
            ).assertMatches("$id Kotlin expectation (${deviation.xRef})")
            // A listed deviation that no longer differs from Ruby must be removed from the deviation file.
            vsOracle.matches shouldBe false
            recorded.status shouldBe ParityStatus.DEVIATION
        }
    }

    private fun sideEffects(
        expectedDb: Map<String, List<ObjectNode>>,
        expectedSql: List<SqlProtocolStep>,
        actualDb: Map<String, List<ObjectNode>>,
        actualSql: List<SqlProtocolStep>,
    ): Comparison =
        Comparison.of("sql: expected $expectedSql but was $actualSql".takeUnless { expectedSql == actualSql }) +
            RowComparator.compare(expectedDb, actualDb)

    @Test
    fun `transfer_scoped_basic rows, SQL protocol and timestamp pattern`() {
        val (credit, debit) = CASES.getValue("transfer_scoped_basic").run(this) as TransferResult
        val steps = sqlCapture.protocolSteps
        val shapes = sqlCapture.shapes

        val db = DbSnapshot.read(jdbcTemplate.dataSource!!)
        db.getValue(DoubleEntryTables.LINES).map { row ->
            listOf("account", "scope", "amount", "balance", "partner_id", "partner_account", "partner_scope").map { row[it].asText() }
        } shouldContainExactly
            listOf(
                listOf("bank", "null", "-1000", "-1000", "2", "checking", "1"),
                listOf("checking", "1", "1000", "1000", "1", "bank", "null"),
            )
        db.getValue(DoubleEntryTables.ACCOUNT_BALANCES).map { it["balance"].asLong() } shouldContainExactly listOf(-1000L, 1000L)
        // Lock (rows missing) → create rows in their own transactions → relock → write protocol (BR-025, BR-062).
        steps.map {
            "${it.verb} ${it.table ?: ""}".trim() + (if (it.forUpdate) " FOR UPDATE" else "")
        } shouldContainExactly
            listOf(
                "BEGIN",
                "SELECT double_entry_account_balances FOR UPDATE",
                "SELECT double_entry_account_balances FOR UPDATE",
                "COMMIT",
                "SELECT double_entry_lines",
                "BEGIN",
                "INSERT double_entry_account_balances",
                "COMMIT",
                "SELECT double_entry_lines",
                "BEGIN",
                "INSERT double_entry_account_balances",
                "COMMIT",
                "BEGIN",
                "SELECT double_entry_account_balances FOR UPDATE",
                "SELECT double_entry_account_balances FOR UPDATE",
                "UPDATE double_entry_account_balances",
                "UPDATE double_entry_account_balances",
                "INSERT double_entry_lines",
                "INSERT double_entry_lines",
                "UPDATE double_entry_lines",
                "COMMIT",
            )
        shapes.last { it.verb == "UPDATE" && it.table == DoubleEntryTables.LINES }.whereColumns shouldContainExactly listOf("id")
        // The credit line's partner-id update is a later clock read than its insert; the debit line is written once.
        credit.updatedAt shouldBeGreaterThan credit.createdAt
        debit.updatedAt shouldBe debit.createdAt
    }

    @Test
    fun `named db_after values of the oracle are reproduced`() {
        fun lineValues(id: String): List<List<String>> {
            cleanDatabase()
            runCatching { CASES.getValue(id).run(this) }
            return DbSnapshot.read(jdbcTemplate.dataSource!!).getValue(DoubleEntryTables.LINES).map { row ->
                listOf("amount", "detail_type", "detail_id", "metadata").map { row[it].toString() }
            }
        }
        lineValues("transfer_btc").map { it[0] } shouldContainExactly listOf("-12345678", "12345678")
        lineValues("transfer_jpy").map { it[0] } shouldContainExactly listOf("-500", "500")
        lineValues("transfer_with_detail").map { it[1] to it[2] } shouldContainExactly List(2) { "\"User\"" to "1" }
        val metadata = OracleScenarios.mapper.readTree("""{"country":"AU","tax":["GST","VAT"],"n":1,"nested":{"a":1}}""")
        lineValues("transfer_with_metadata").map { RowComparator.semanticEquals(metadata, OracleScenarios.mapper.readTree(it[3])) } shouldBe
            listOf(true, true)
        // BR-031: the balance row created by the failed transfer's lock stays.
        lineValues("transfer_positive_only_violation").size shouldBe 2
        DbSnapshot.read(jdbcTemplate.dataSource!!).getValue(DoubleEntryTables.ACCOUNT_BALANCES).map {
            Triple(it["account"].asText(), it["scope"].asText(), it["balance"].asLong())
        } shouldContainExactly listOf(Triple("bank", "null", -500L), Triple("savings", "1", 500L), Triple("checking", "1", 0L))
    }

    @Order(2)
    @Test
    fun `every scenario of the area is driven and the report has no mismatches`() {
        CASES.keys shouldContainExactlyInAnyOrder ParityArea.TRANSFERS_AND_LINES.scenarioIds
        report.assertParity()
        val written = ParityReport.reportDir().resolve("${ParityArea.TRANSFERS_AND_LINES.key}.md").readText()
        ParityArea.TRANSFERS_AND_LINES.scenarioIds.forEach { id ->
            val line = written.lines().single { it.startsWith("| $id |") }
            (line.contains("| match |") || line.contains("| deviation (X-")) shouldBe true
        }
        written.lines().none { it.contains("| mismatch |") || it.contains("| not run |") } shouldBe true
        report.outcomes().count { it.status == ParityStatus.DEVIATION } shouldNotBe 0
    }

    companion object {
        private val report = ParityReport(ParityArea.TRANSFERS_AND_LINES)

        @JvmStatic
        fun scenarioIds(): List<String> = ParityArea.TRANSFERS_AND_LINES.scenarioIds

        private fun mismatched(
            amount: String,
            id: String,
        ) = KotlinExpectation(
            exception = OracleException("DoubleEntry::MismatchedCurrencies", "Mismatched currency ($amount <> USD)"),
            dbAfter = dbAfterWithout(id, DoubleEntryTables.ACCOUNT_BALANCES),
            sql = emptyList(),
        )

        private fun notExpressible(
            reason: String,
            dbAfter: Map<String, List<ObjectNode>>? = null,
            sql: List<SqlProtocolStep>? = null,
        ) = KotlinExpectation(result = TransferLineSerializer.ser(NotExpressible(reason)), dbAfter = dbAfter, sql = sql)

        private const val AMOUNT_IS_MONEY = "amount: Money is required and non-null (D-013)"
        private const val FROM_REQUIRED = "from: AccountInstance is required (D-013)"
        private const val CODE_REQUIRED = "code: String is required and non-null (D-013)"

        private fun returnedLine(line: Line) =
            linkedMapOf(
                "id" to line.id,
                "amount" to line.amount,
                "balance" to line.balance,
                "account" to line.account,
                "partner_account" to line.partnerAccount,
                "partner_id" to line.partnerId,
                // Ruby returns the code as a Symbol; Kotlin codes are Strings (proposed X-11), rendered in Ruby's form.
                "code" to mapOf("symbol" to line.code),
            )

        /** The scenario blocks of `tools/oracle.rb:186-226,373-388`, in the same order. */
        private val CASES: Map<String, Case> =
            mapOf(
                // ---- transfers (BR-020..035)
                "transfer_scoped_basic" to Case { xfer(10_00, acct("bank"), acct("checking", 1), "deposit") },
                "transfer_unscoped_basic" to Case { xfer(1_000_00, acct("bank"), acct("cash"), "salary") },
                "transfer_sequence_running_balances" to
                    Case {
                        val (b, c, s) = listOf(acct("bank"), acct("cash"), acct("store"))
                        xfer(1_000_00, b, c, "salary")
                        xfer(200_00, c, s, "purchase")
                        xfer(100_00, c, s, "layby")
                        xfer(50_00, c, b, "refund")
                        linkedMapOf("cash" to doubleEntry.balance(c), "bank" to doubleEntry.balance(b), "store" to doubleEntry.balance(s))
                    },
                "transfer_zero_amount" to Case { xfer(0, acct("bank"), acct("cash"), "salary") },
                "transfer_negative_amount" to Case { xfer(-1, acct("bank"), acct("cash"), "salary") },
                "transfer_not_allowed_direction" to Case { xfer(1, acct("cash"), acct("bank"), "salary") },
                "transfer_not_allowed_code" to Case { xfer(1, acct("bank"), acct("cash"), "nope") },
                "transfer_missing_code" to Case(notExpressible(CODE_REQUIRED)) { transferRequires("code", reason = CODE_REQUIRED) },
                // X-11 (proposed): Ruby's :salary and "salary" are different codes; Kotlin codes are Strings only, so
                // this is the plain 1-cent salary transfer (rows of the fixtures-flag scenario, SQL of an unscoped one).
                "transfer_string_code" to
                    Case(
                        KotlinExpectation(
                            result = OracleScenarios.load("transfer_inside_transaction_with_fixtures_flag").result,
                            dbAfter = OracleScenarios.load("transfer_inside_transaction_with_fixtures_flag").dbAfter,
                            sql = SqlCapture.protocol(SqlCapture.fromRubyLog(OracleScenarios.load("transfer_unscoped_basic").sql)),
                        ),
                    ) { doubleEntry.transfer(usd(1), from = acct("bank"), to = acct("cash"), code = "salary") },
                "transfer_missing_from" to Case(notExpressible(FROM_REQUIRED)) { transferRequires("from", reason = FROM_REQUIRED) },
                "transfer_same_account" to Case { xfer(1, acct("savings", 1), acct("savings", 1), "self") },
                "transfer_same_identifier_other_scope" to
                    Case {
                        xfer(5_00, acct("bank"), acct("checking", 1), "deposit")
                        xfer(3_00, acct("checking", 1), acct("checking", 2), "p2p")
                        linkedMapOf("alice" to doubleEntry.balance(acct("checking", 1)), "bob" to doubleEntry.balance(acct("checking", 2)))
                    },
                "transfer_mismatched_account_currencies" to
                    Case { doubleEntry.transfer(usd(1), from = acct("bank"), to = acct("btc_store"), code = "mismatch") },
                // X-07 (D-013): checked before the lock, so no balance rows are created and no SQL runs.
                "transfer_amount_currency_differs_from_accounts" to
                    Case(mismatched("BTC", "transfer_amount_currency_differs_from_accounts")) {
                        doubleEntry.transfer(money(1_00, "BTC"), from = acct("bank"), to = acct("cash"), code = "salary")
                    },
                "transfer_amount_currency_differs_first_transfer_zero_balance" to
                    Case(mismatched("EUR", "transfer_amount_currency_differs_first_transfer_zero_balance")) {
                        doubleEntry.transfer(money(5_00, "EUR"), from = acct("cash"), to = acct("store"), code = "purchase")
                    },
                "transfer_btc" to
                    Case {
                        doubleEntry.transfer(
                            money(12_345_678, "BTC"),
                            from = acct("btc_store"),
                            to = acct("btc_wallet", 1),
                            code = "btc_buy",
                        )
                    },
                "transfer_jpy" to
                    Case { doubleEntry.transfer(money(500, "JPY"), from = acct("jpy_bank"), to = acct("jpy_wallet"), code = "jpy_in") },
                // BR-032: `Money.new(10.5)` rounds half-even to whole minor units.
                "transfer_fractional_cents" to
                    Case {
                        doubleEntry.transfer(
                            Money.ofMinorRounded("10.5".toBigDecimal(), CurrencyUnit.USD),
                            acct("bank"),
                            acct("cash"),
                            "salary",
                        )
                    },
                "transfer_fractional_cents_11_5" to
                    Case {
                        doubleEntry.transfer(
                            Money.ofMinorRounded("11.5".toBigDecimal(), CurrencyUnit.USD),
                            acct("bank"),
                            acct("cash"),
                            "salary",
                        )
                    },
                "transfer_bigint_max" to
                    Case { doubleEntry.transfer(usd(Long.MAX_VALUE), from = acct("bank"), to = acct("cash"), code = "salary") },
                // BR-032, D-013: the overflow is detected before the second transfer writes; the same rows remain.
                "transfer_bigint_overflow" to
                    Case(KotlinExpectation(exception = OracleException("ArithmeticException", "long overflow"))) {
                        repeat(2) { doubleEntry.transfer(usd(Long.MAX_VALUE), from = acct("bank"), to = acct("cash"), code = "salary") }
                    },
                // X-07: Ruby committed both balance rows before the TypeError; Kotlin cannot make the call at all.
                "transfer_integer_amount" to
                    Case(
                        notExpressible(
                            AMOUNT_IS_MONEY,
                            dbAfter = dbAfterWithout("transfer_integer_amount", DoubleEntryTables.ACCOUNT_BALANCES),
                            sql = emptyList(),
                        ),
                    ) { transferRequires("amount", reason = AMOUNT_IS_MONEY) },
                "transfer_nil_amount" to Case(notExpressible(AMOUNT_IS_MONEY)) { transferRequires("amount", reason = AMOUNT_IS_MONEY) },
                "transfer_positive_only_violation" to
                    Case {
                        xfer(5_00, acct("bank"), acct("savings", 1), "deposit")
                        xfer(6_00, acct("savings", 1), acct("checking", 1), "withdraw")
                    },
                "transfer_positive_only_exact_zero" to
                    Case {
                        xfer(5_00, acct("bank"), acct("savings", 1), "deposit")
                        xfer(5_00, acct("savings", 1), acct("checking", 1), "withdraw")
                        doubleEntry.balance(acct("savings", 1))
                    },
                "transfer_negative_only_violation" to Case { xfer(1, acct("cash"), acct("liability"), "repay") },
                "transfer_negative_only_ok" to
                    Case {
                        xfer(7_00, acct("liability"), acct("cash"), "borrow")
                        doubleEntry.balance(acct("liability"))
                    },
                // D-015: the polymorphic detail is a (type, id) reference; "User" is the Ruby class name.
                "transfer_with_detail" to Case { xfer(1_00, acct("bank"), acct("checking", 1), "deposit", detail = DetailRef("User", 1)) },
                "transfer_with_metadata" to
                    Case {
                        val metadata = mapOf("country" to "AU", "tax" to listOf("GST", "VAT"), "n" to 1, "nested" to mapOf("a" to 1))
                        xfer(1_00, acct("bank"), acct("checking", 1), "deposit", metadata = metadata)
                    },
                "transfer_with_metadata_simple" to
                    Case {
                        xfer(
                            1_00,
                            acct("bank"),
                            acct("checking", 1),
                            "deposit",
                            metadata =
                                mapOf(
                                    "country" to "AU",
                                    "tax" to listOf("GST", "VAT"),
                                ),
                        )
                    },
                "transfer_with_empty_metadata" to Case { xfer(1_00, acct("bank"), acct("checking", 1), "deposit", metadata = emptyMap()) },
                "transfer_return_value" to
                    Case {
                        val (credit, debit) = xfer(2_50, acct("bank"), acct("cash"), "salary")
                        linkedMapOf(
                            "credit" to
                                returnedLine(credit) +
                                mapOf(
                                    "decrease?" to credit.isDecrease,
                                    "increase?" to credit.isIncrease,
                                    "currency" to credit.currency.code,
                                ),
                            "debit" to returnedLine(debit),
                            "credit.partner == debit" to (lines.partner(credit) == debit),
                            "debit.pair" to lines.pair(debit).toList().map { it.id },
                            "credit.pair" to lines.pair(credit).toList().map { it.id },
                        )
                    },
                "transfer_inside_ar_transaction" to
                    Case { transaction().execute { xfer(1, acct("bank"), acct("cash"), "salary") } },
                "transfer_inside_transaction_with_fixtures_flag" to
                    Case {
                        val flagged = withFixturesFlag()
                        transaction().execute { flagged.transfer(usd(1), acct("bank"), acct("cash"), "salary") }
                    },
                // `transaction(requires_new: true)`: Spring REQUIRES_NEW, a second enclosing transaction.
                "transfer_inside_two_transactions_with_fixtures_flag" to
                    Case {
                        val flagged = withFixturesFlag()
                        transaction().execute {
                            transaction(TransactionDefinition.PROPAGATION_REQUIRES_NEW).execute {
                                flagged.transfer(usd(1), acct("bank"), acct("cash"), "salary")
                            }
                        }
                    },
                // ---- line model (BR-080..085)
                "line_scopes" to
                    Case {
                        val (credit, debit) = xfer(5_00, acct("bank"), acct("cash"), "salary")
                        linkedMapOf(
                            "credit_line_id(from, negative)" to credit.id,
                            "debit_line_id(to, positive)" to debit.id,
                            "Line.credits.ids" to lines.credits().map { it.id },
                            "Line.debits.ids" to lines.debits().map { it.id },
                            "with_id_greater_than(1)" to lines.withIdGreaterThan(1).map { it.id },
                        )
                    },
                // U-007: typed criteria instead of raw SQL `conditions: ['account = ?', 'cash']`; the same rows.
                "line_find_id_and_created_at" to
                    Case {
                        clock.set(OracleFixtureConfig.T0)
                        xfer(5_00, acct("bank"), acct("cash"), "salary")
                        clock.reset()
                        lines.findIdAndCreatedAt(LineCriteria(account = "cash"))
                    },
                // json mode: `metadata` is the jsonb column, never an association (X-02: there is no metadata table).
                "line_metadata_reader" to
                    Case {
                        val (credit, _) = xfer(1_00, acct("bank"), acct("cash"), "salary", metadata = mapOf("k" to "v"))
                        val line = checkNotNull(lines.find(credit.id))
                        val metadataField = LineEntity::class.java.getDeclaredField("metadata")
                        linkedMapOf(
                            "metadata" to line.metadata,
                            "responds_to_metadata" to Line::class.memberProperties.any { it.name == "metadata" },
                            "Line.reflect_on_association(:metadata)" to
                                listOf(ManyToOne::class.java, OneToOne::class.java, OneToMany::class.java)
                                    .firstOrNull { metadataField.isAnnotationPresent(it) }
                                    ?.simpleName,
                        )
                    },
                "line_direct_create_positive_only_check" to
                    Case { lineWriter.create(NewLine(acct("savings", 1), acct("bank"), "x", usd(-1), usd(-1))) },
                // X-04 (D-008): NewLine.code is non-null (D-013), so only raw SQL reaches the NOT NULL constraint; the
                // driver error surfaces as Spring's DataIntegrityViolationException with SQLSTATE 23502.
                "line_direct_create_missing_code" to
                    Case(
                        KotlinExpectation(
                            check = { outcome ->
                                val error = outcome.exception as? DataIntegrityViolationException
                                val cause = error?.mostSpecificCause as? SQLException
                                Comparison.of(
                                    "expected DataIntegrityViolationException with SQLSTATE 23502 but was ${outcome.exception ?: outcome.result}"
                                        .takeUnless {
                                            cause?.sqlState == "23502" &&
                                                cause.message.orEmpty().contains(
                                                    "null value in column \"code\" of relation \"double_entry_lines\"",
                                                )
                                        },
                                )
                            },
                        ),
                    ) {
                        transaction().execute {
                            jdbcTemplate.update(
                                "INSERT INTO double_entry_lines (account, amount, balance, partner_account, created_at, updated_at) " +
                                    "VALUES (?, ?, ?, ?, now(), now())",
                                "bank",
                                1,
                                1,
                                "cash",
                            )
                        }
                    },
                "line_account_unknown_after_config_change" to
                    Case {
                        xfer(1_00, acct("bank"), acct("cash"), "salary")
                        // `DoubleEntry.config.accounts = Account::Set.new` (X-09): read against an empty configuration.
                        val line = Line.of(lineRepository.findAllByOrderByIdAsc().first(), AccountResolver(AccountSet.build {}))
                        val amount =
                            try {
                                line.amount
                            } catch (e: DoubleEntryException) {
                                "${e.rubyClassName}: ${e.message}"
                            }
                        linkedMapOf("raw_account" to line.accountIdentifier, "amount" to amount)
                    },
            )
    }
}
