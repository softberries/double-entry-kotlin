package com.softberries.doubleentry.parity.config

import com.fasterxml.jackson.databind.node.ObjectNode
import com.softberries.doubleentry.DoubleEntry
import com.softberries.doubleentry.config.DoubleEntryConfiguration
import com.softberries.doubleentry.config.DoubleEntryConfigurationBuilder
import com.softberries.doubleentry.config.DoubleEntryProperties
import com.softberries.doubleentry.config.DoubleEntryTables
import com.softberries.doubleentry.config.doubleEntry
import com.softberries.doubleentry.locking.LockingSettings
import com.softberries.doubleentry.parity.Comparison
import com.softberries.doubleentry.parity.DbSnapshot
import com.softberries.doubleentry.parity.NotificationCapture
import com.softberries.doubleentry.parity.OracleException
import com.softberries.doubleentry.parity.OracleFixtureConfig
import com.softberries.doubleentry.parity.OracleScenario
import com.softberries.doubleentry.parity.OracleScenarios
import com.softberries.doubleentry.parity.ParityArea
import com.softberries.doubleentry.parity.ParityReport
import com.softberries.doubleentry.parity.ParityStatus
import com.softberries.doubleentry.parity.RowComparator
import com.softberries.doubleentry.parity.SqlCapture
import com.softberries.doubleentry.testing.PostgresIntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import kotlin.io.path.readText

/**
 * Parity driver for the configuration and accounts area (M-018): replays every `config_*` and `account_*` scenario of
 * `oracle/api/json` against the Kotlin API and compares result or exception (class and message exactly), `db_after`,
 * the double_entry SQL protocol and notifications. The oracle's `users` rows and statements have no Kotlin counterpart
 * (scopes are [com.softberries.doubleentry.testing.TestUser]s); [SqlCapture.protocol] drops them.
 *
 * Scenarios listed in `parity/config-and-accounts.deviations.json` are still run: they must match their
 * [Case.kotlinExpectation] (from `docs/decisions.md`) and must differ from the oracle, and are reported as
 * `deviation (X-nn)` with the oracle diff. The report is `build/reports/parity/config-and-accounts.md`.
 */
@Import(ConfigAccountParityConfiguration::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ConfigAccountParityTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var doubleEntry: DoubleEntry

    @Autowired
    private lateinit var configuration: DoubleEntryConfiguration

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
        val kotlinExpectation: Expected? = null,
        val run: ConfigAccountParityTest.() -> Any?,
    )

    /** A scope object that is not a user (`Struct.new(:id).new(7)` in `account_scope_lambda_raises`). */
    private data class OtherScope(
        val id: Long,
    )

    private fun acct(
        identifier: String,
        user: Int? = null,
    ) = if (user == null) doubleEntry.account(identifier) else doubleEntry.account(identifier, scope = OracleFixtureConfig.user(user))

    private fun limits(
        accountIdentifier: Int? = null,
        scopeIdentifier: Int? = null,
        code: Int? = null,
    ) = DoubleEntryProperties.DEFAULTS.copy(
        accountIdentifierMaxLength = accountIdentifier,
        scopeIdentifierMaxLength = scopeIdentifier,
        codeMaxLength = code,
    )

    @Order(1)
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarioIds")
    fun `replays the oracle scenario`(id: String) {
        val oracle = OracleScenarios.load(id)
        val case = CASES[id]
        if (case == null) {
            report.record(id, Comparison.of("scenario is not driven by ConfigAccountParityTest"))
            throw AssertionError("$id: no Kotlin driver case")
        }
        val outcome = KotlinOutcome.of { case.run(this) }
        val sideEffects = sideEffects(oracle)
        val vsOracle = OutcomeComparator.compare(Expected(oracle.result, oracle.exception), outcome) + sideEffects
        val recorded = report.record(id, vsOracle)

        val deviation = report.deviations.deviationFor(id)
        if (deviation == null) {
            case.kotlinExpectation shouldBe null
            vsOracle.assertMatches(id)
        } else {
            val expectation = checkNotNull(case.kotlinExpectation) { "$id is a deviation (${deviation.xRef}) without a Kotlin expectation" }
            (OutcomeComparator.compare(expectation, outcome) + sideEffects).assertMatches("$id Kotlin expectation (${deviation.xRef})")
            // A listed deviation that no longer differs from Ruby must be removed from the deviation file.
            vsOracle.matches shouldBe false
            recorded.status shouldBe ParityStatus.DEVIATION
        }
    }

    /** `db_after`, the double_entry SQL protocol (D-017) and notifications; none of them depends on the deviations here. */
    private fun sideEffects(oracle: OracleScenario): Comparison {
        val expectedSql = SqlCapture.protocol(SqlCapture.fromRubyLog(oracle.sql))
        val actualSql = sqlCapture.protocolSteps
        val sql = Comparison.of("sql: expected $expectedSql but was $actualSql".takeUnless { expectedSql == actualSql })
        return sql +
            RowComparator.compare(oracle.dbAfter, DbSnapshot.read(jdbcTemplate.dataSource!!)) +
            NotificationCapture.compare(oracle.notifications, notifications.notifications)
    }

    /**
     * X-09: with the definition under test alone in a configuration built with the limit, the outcome is exactly the
     * oracle's. (The scenario itself shows that a limit set after `configure` cannot be expressed.)
     */
    @Test
    fun `max lengths reproduce the oracle when the limit is set before the definitions (X-09)`() {
        fun assertOracle(
            id: String,
            block: () -> Any?,
        ) = OutcomeComparator
            .compare(OracleScenarios.load(id).let { Expected(it.result, it.exception) }, KotlinOutcome.of(block))
            .assertMatches(id)

        assertOracle("config_account_identifier_max_length_ok") {
            doubleEntryOnly(limits(accountIdentifier = 5)) { accounts { define("abcde") } }.accounts.findWithoutScope("abcde")
        }
        assertOracle("config_account_identifier_too_long") {
            doubleEntryOnly(limits(accountIdentifier = 5)) { accounts { define("abcdef") } }
        }
        assertOracle("config_code_too_long") {
            doubleEntryOnly(limits(code = 4)) { transfers { define("a", "b", "abcde") } }.transfers.find("a", "b", "abcde")
        }
    }

    @Test
    fun `the account toString in an exception renders the TestUser like the oracle renders a User`() {
        val normalised =
            OutcomeComparator.normaliseUsers(OracleScenarios.load("account_instance_semantics").result) as ObjectNode
        normalised["sorted"].map { it.asText() } shouldContainExactly
            listOf(
                "#{Account account: bank scope:  currency: USD}",
                "#{Account account: checking scope: TestUser(id=1) currency: USD}",
                "#{Account account: savings scope: TestUser(id=1) currency: USD}",
                "#{Account account: checking scope: TestUser(id=2) currency: USD}",
            )
    }

    @Order(2)
    @Test
    fun `every scenario of the area is driven and the report has no mismatches`() {
        CASES.keys shouldContainExactlyInAnyOrder ParityArea.CONFIG_AND_ACCOUNTS.scenarioIds
        report.assertParity()
        val written = ParityReport.reportDir().resolve("${ParityArea.CONFIG_AND_ACCOUNTS.key}.md").readText()
        ParityArea.CONFIG_AND_ACCOUNTS.scenarioIds.forEach { id ->
            val line = written.lines().single { it.startsWith("| $id |") }
            (line.contains("| match |") || line.contains("| deviation (X-")) shouldBe true
        }
        written.lines().none { it.contains("| mismatch |") || it.contains("| not run |") } shouldBe true
        report.outcomes().count { it.status == ParityStatus.DEVIATION } shouldNotBe 0
    }

    companion object {
        private val report = ParityReport(ParityArea.CONFIG_AND_ACCOUNTS)

        @JvmStatic
        fun scenarioIds(): List<String> = ParityArea.CONFIG_AND_ACCOUNTS.scenarioIds

        /** A configuration holding only what [block] defines. */
        private fun doubleEntryOnly(
            properties: DoubleEntryProperties,
            block: DoubleEntryConfigurationBuilder.() -> Unit,
        ): DoubleEntryConfiguration = doubleEntry(properties, block)

        private fun exception(
            rubyClass: String,
            message: String,
        ) = OracleException(rubyClass, message)

        /** The scenario blocks of `tools/oracle.rb:147-185`, in the same order. */
        private val CASES: Map<String, Case> =
            mapOf(
                // ---- configuration (BR-001..008)
                "config_duplicate_account" to
                    Case { oracleConfiguration { accounts { define("bank") } } },
                "config_duplicate_account_scoped_vs_unscoped" to
                    Case { oracleConfiguration { accounts { define("checking") } } },
                "config_duplicate_transfer" to
                    Case { oracleConfiguration { transfers { define("bank", "cash", "salary") } } },
                "config_transfer_unknown_accounts_allowed" to
                    Case { oracleConfiguration { transfers { define("nope", "nada", "x") } }.transfers.find("nope", "nada", "x") },
                // X-09: the Ruby block sets the limit after the standard configuration; in Kotlin it is a build-time
                // setting, so it also applies to the standard definitions and the first too-long one fails.
                "config_account_identifier_max_length_ok" to
                    Case(
                        Expected(
                            exception =
                                exception(
                                    "DoubleEntry::AccountIdentifierTooLongError",
                                    "account identifier 'savings' is too long. Please limit it to 5 characters.",
                                ),
                        ),
                    ) {
                        oracleConfiguration(
                            limits(accountIdentifier = 5),
                        ) { accounts { define("abcde") } }.accounts.findWithoutScope("abcde")
                    },
                "config_account_identifier_too_long" to
                    Case(
                        Expected(
                            exception =
                                exception(
                                    "DoubleEntry::AccountIdentifierTooLongError",
                                    "account identifier 'savings' is too long. Please limit it to 5 characters.",
                                ),
                        ),
                    ) { oracleConfiguration(limits(accountIdentifier = 5)) { accounts { define("abcdef") } } },
                "config_code_too_long" to
                    Case(
                        Expected(
                            exception =
                                exception(
                                    "DoubleEntry::TransferCodeTooLongError",
                                    "transfer code 'deposit' is too long. Please limit it to 4 characters.",
                                ),
                        ),
                    ) {
                        oracleConfiguration(
                            limits(code = 4),
                        ) { transfers { define("a", "b", "abcde") } }.transfers.find("a", "b", "abcde")
                    },
                "config_scope_too_long" to
                    Case { oracleConfiguration(limits(scopeIdentifier = 3)).resolver().account("checking", scopeIdentity = "1234") },
                "config_scope_too_long_via_lambda" to
                    Case { oracleConfiguration(limits(scopeIdentifier = 1)).resolver().account("checking", OracleFixtureConfig.user(12)) },
                "config_existing_config_snapshot" to
                    Case { mapOf("accounts" to configuration.accounts.all, "transfers" to configuration.transfers.all) },
                "config_defaults" to
                    Case(
                        Expected(
                            result =
                                (OracleScenarios.load("config_defaults").result!!.deepCopy() as ObjectNode)
                                    .apply { remove("json_metadata_default") },
                        ),
                    ) {
                        val defaults = DoubleEntryProperties.DEFAULTS
                        linkedMapOf(
                            "code_max_length" to defaults.codeMaxLength,
                            "scope_identifier_max_length" to defaults.scopeIdentifierMaxLength,
                            "account_identifier_max_length" to defaults.accountIdentifierMaxLength,
                            // The default, like `Locking::Configuration.new` in the oracle, not the context's bean.
                            "running_inside_transactional_fixtures" to LockingSettings().runningInsideTransactionalFixtures,
                            "table_name_prefix" to DoubleEntryTables.PREFIX,
                            "tables" to DoubleEntryTables.ALL,
                            "default_currency_of_account_without_currency" to acct("bank").currency,
                            "Account.currency(:btc_store)" to configuration.accounts.currencyOf("btc_store"),
                            "Account.currency(:unknown)" to configuration.accounts.currencyOf("unknown"),
                        )
                    },
                // ---- account lookup and instances (BR-009..013)
                "account_unscoped" to Case { acct("bank") },
                "account_scoped" to Case { acct("checking", user = 1) },
                "account_unknown" to Case { acct("nope") },
                "account_scoped_without_scope" to Case { acct("checking") },
                "account_unscoped_with_scope" to Case { acct("bank", user = 1) },
                "account_scope_lambda_raises" to Case { doubleEntry.account("checking", scope = OtherScope(7)) },
                "account_scope_identity_option" to Case { doubleEntry.account("checking", scopeIdentity = "abc") },
                // X-08: the API takes the identity as a String, so Ruby's Integer 42 is passed as "42".
                "account_scope_identity_integer" to
                    Case(Expected(result = OracleSerializer.obj("scope_identity" to "42", "class" to "String"))) {
                        val identity = doubleEntry.account("checking", scopeIdentity = 42.toString()).scopeIdentity
                        mapOf("scope_identity" to identity, "class" to identity?.let { it::class.simpleName })
                    },
                "account_scope_empty_string" to Case { doubleEntry.account("checking", scope = "") },
                "account_scope_false" to Case { doubleEntry.account("checking", scope = false) },
                "account_scope_zero" to
                    Case {
                        val config = oracleConfiguration { accounts { define("ident", scopeIdentifier = { it }) } }
                        mapOf("scope_identity" to config.resolver().account("ident", scope = 0).scopeIdentity)
                    },
                "account_scope_lambda_returns_nil" to
                    Case {
                        val config = oracleConfiguration { accounts { define("ident", scopeIdentifier = { null }) } }
                        mapOf("scope_identity" to config.resolver().account("ident", scope = 5).scopeIdentity)
                    },
                // X-11 (proposed): Ruby's :bank and "bank" are different keys; Kotlin identifiers are Strings only.
                "account_string_identifier" to
                    Case(Expected(result = OracleScenarios.load("account_unscoped").result)) { doubleEntry.account("bank") },
                "account_instance_semantics" to
                    Case {
                        val a1 = acct("checking", user = 1)
                        val a1b = acct("checking", user = 1)
                        val a2 = acct("checking", user = 2)
                        val b = acct("bank")
                        val s1 = acct("savings", user = 1)
                        linkedMapOf(
                            "a1==a1b" to (a1 == a1b),
                            // Ruby `eql?` is `==` for Account::Instance; Kotlin has only equals.
                            "a1.eql?(a1b)" to a1.equals(a1b),
                            "a1.hash==a1b.hash" to (a1.hashCode() == a1b.hashCode()),
                            "a1==a2" to (a1 == a2),
                            "sorted" to listOf(s1, a2, b, a1).sorted().map { it.toString() },
                            "to_s" to a1.toString(),
                            "inspect" to b.toString(),
                            "scoped?" to listOf(a1.scoped, b.scoped),
                            "positive_only" to s1.positiveOnly,
                            "negative_only" to acct("liability").negativeOnly,
                            "currency" to acct("btc_wallet", user = 1).currency,
                        )
                    },
            )
    }
}
