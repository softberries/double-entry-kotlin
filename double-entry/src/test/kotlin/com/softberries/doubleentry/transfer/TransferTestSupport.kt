package com.softberries.doubleentry.transfer

import com.fasterxml.jackson.databind.JsonNode
import com.softberries.doubleentry.account.AccountFixtures
import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.balance.BalanceCalculator
import com.softberries.doubleentry.config.DoubleEntryConfigurer
import com.softberries.doubleentry.ledger.Line
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.parity.DbSnapshot
import com.softberries.doubleentry.parity.ObservedMoney
import com.softberries.doubleentry.parity.OracleFixtureConfig
import com.softberries.doubleentry.parity.OracleScenario
import com.softberries.doubleentry.parity.OracleScenarios
import com.softberries.doubleentry.parity.RowComparator
import com.softberries.doubleentry.parity.SqlCapture
import com.softberries.doubleentry.testing.PostgresIntegrationTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * The oracle's `standard_config` (`tools/oracle.rb:44-80`, [OracleFixtureConfig]) plus the definitions the ported Ruby
 * specs use: `test` → `savings` `bonus` (`spec/support/accounts.rb:9,18`) and the identity-scoped `account_a`..`account_e`
 * of `spec/double_entry/locking_spec.rb:16-31`.
 */
@TestConfiguration(proxyBeanMethods = false)
class TransferTestConfiguration {
    @Bean
    fun transferTestDefinitions(): DoubleEntryConfigurer =
        DoubleEntryConfigurer { builder ->
            builder.accounts {
                OracleFixtureConfig.ACCOUNTS.forEach {
                    define(
                        it.identifier,
                        scopeIdentifier = AccountFixtures.userScope.takeIf { _ -> it.scoped },
                        positiveOnly = it.positiveOnly,
                        negativeOnly = it.negativeOnly,
                        currency = it.currency,
                    )
                }
                define("test", scopeIdentifier = AccountFixtures.userScope)
                val identity: (Any) -> Any? = { it }
                listOf("account_a", "account_b", "account_c", "account_d").forEach { define(it, scopeIdentifier = identity) }
                define("account_e")
            }
            builder.transfers {
                OracleFixtureConfig.TRANSFERS.forEach { define(it.from, it.to, it.code) }
                define("test", "savings", "bonus")
                define("account_a", "account_b", "test")
                define("account_c", "account_d", "test")
            }
        }

    companion object {
        private val capture = SqlCapture()

        @Bean
        @JvmStatic
        fun transferSqlCapture(): SqlCapture = capture

        @Bean
        @JvmStatic
        fun transferSqlCapturePostProcessor(): BeanPostProcessor = capture.beanPostProcessor()
    }
}

/** Integration test base for transfers: [TransferTestConfiguration], SQL capture and oracle helpers. */
@Import(TransferTestConfiguration::class)
abstract class TransferIntegrationTestBase : PostgresIntegrationTest() {
    @Autowired
    protected lateinit var transfers: TransferService

    @Autowired
    protected lateinit var resolver: AccountResolver

    @Autowired
    protected lateinit var calculator: BalanceCalculator

    @Autowired
    protected lateinit var sqlCapture: SqlCapture

    @BeforeEach
    fun clearCapture() = sqlCapture.clear()

    protected val usd: CurrencyUnit get() = CurrencyUnit.of("USD")

    protected fun usd(minor: Long) = Money.ofMinor(minor, usd)

    protected fun money(
        minor: Long,
        currency: String,
    ) = Money.ofMinor(minor, CurrencyUnit.of(currency))

    /** An unscoped account, or a user-scoped one for the oracle's n-th user ([OracleFixtureConfig.user]). */
    protected fun acct(
        identifier: String,
        user: Int? = null,
    ): AccountInstance =
        if (user == null) resolver.account(identifier) else resolver.account(identifier, scope = OracleFixtureConfig.user(user))

    protected fun lineCount(): Long = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM double_entry_lines", Long::class.java)!!

    protected fun balanceRowCount(): Long =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM double_entry_account_balances", Long::class.java)!!

    protected fun cachedBalances(): List<Pair<String, Long>> =
        jdbcTemplate.query("SELECT account, scope, balance FROM double_entry_account_balances ORDER BY id") { rs, _ ->
            (rs.getString("account") + (rs.getString("scope")?.let { "@$it" } ?: "")) to rs.getLong("balance")
        }

    protected fun assertDbMatches(scenario: OracleScenario) =
        RowComparator.compare(scenario.dbAfter, DbSnapshot.read(jdbcTemplate.dataSource!!)).assertMatches(scenario.id)

    protected fun observed(money: Money) = ObservedMoney(money.minorUnits, money.currency.code, money.toString())

    /** A returned [Line] against the oracle's serialised `DoubleEntry::Line` record (all columns but the timestamps). */
    protected fun assertLineMatches(
        expected: JsonNode,
        actual: Line,
    ) {
        val attributes = expected["attributes"]

        fun text(name: String): String? = attributes[name].takeUnless { it.isNull }?.asText()

        fun long(name: String): Long? = attributes[name].takeUnless { it.isNull }?.asLong()
        actual.id shouldBe long("id")
        actual.accountIdentifier shouldBe text("account")
        actual.scopeIdentity shouldBe text("scope")
        actual.code shouldBe text("code")
        actual.amountMinorUnits shouldBe long("amount")
        actual.balanceMinorUnits shouldBe long("balance")
        actual.partnerId shouldBe long("partner_id")
        actual.partnerAccountIdentifier shouldBe text("partner_account")
        actual.partnerScopeIdentity shouldBe text("partner_scope")
        actual.detail?.type shouldBe text("detail_type")
        actual.detail?.id shouldBe long("detail_id")
        val metadata = attributes["metadata"]
        if (metadata.isNull) {
            actual.metadata shouldBe null
        } else {
            RowComparator.semanticEquals(metadata, OracleScenarios.mapper.valueToTree(actual.metadata)) shouldBe true
        }
    }

    /** BR-026: per (account, scope), ordered by id, `balance = previous balance + amount` (previous = 0). */
    protected fun assertRunningBalances() {
        val rows =
            jdbcTemplate.queryForList("SELECT account, scope, amount, balance FROM double_entry_lines ORDER BY id")
        val running = mutableMapOf<Pair<Any?, Any?>, Long>()
        for (row in rows) {
            val key = row["account"] to row["scope"]
            val expected = running.getOrDefault(key, 0L) + (row["amount"] as Long)
            (row["balance"] as Long) shouldBe expected
            running[key] = expected
        }
    }
}
