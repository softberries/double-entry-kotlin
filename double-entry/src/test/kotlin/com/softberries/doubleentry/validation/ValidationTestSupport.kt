package com.softberries.doubleentry.validation

import com.fasterxml.jackson.databind.node.ObjectNode
import com.softberries.doubleentry.account.AccountFixtures
import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.balance.BalanceCalculator
import com.softberries.doubleentry.config.DoubleEntryConfigurer
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.parity.DbSnapshot
import com.softberries.doubleentry.parity.OracleFixtureConfig
import com.softberries.doubleentry.parity.OracleScenario
import com.softberries.doubleentry.parity.RowComparator
import com.softberries.doubleentry.parity.SqlCapture
import com.softberries.doubleentry.testing.PostgresIntegrationTest
import com.softberries.doubleentry.testing.TestUser
import com.softberries.doubleentry.transfer.TransferService
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * The oracle's `standard_config` (`tools/oracle.rb:44-80`) plus the accounts and transfers `line_check_spec.rb` uses
 * from `spec/support/accounts.rb:6-21`: `test`, `btc_test`, `btc_savings` and the `bonus` / `btc_test_transfer`
 * transfers (`savings` is positive-only in both).
 */
@TestConfiguration(proxyBeanMethods = false)
class ValidationTestConfiguration {
    @Bean
    fun validationTestDefinitions(): DoubleEntryConfigurer =
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
                define("btc_test", scopeIdentifier = AccountFixtures.userScope, currency = "BTC")
                define("btc_savings", scopeIdentifier = AccountFixtures.userScope, currency = "BTC")
            }
            builder.transfers {
                OracleFixtureConfig.TRANSFERS.forEach { define(it.from, it.to, it.code) }
                define("test", "savings", "bonus")
                define("btc_test", "btc_savings", "btc_test_transfer")
            }
        }

    companion object {
        private val capture = SqlCapture()

        @Bean
        @JvmStatic
        fun validationSqlCapture(): SqlCapture = capture

        @Bean
        @JvmStatic
        fun validationSqlCapturePostProcessor(): BeanPostProcessor = capture.beanPostProcessor()
    }
}

/** Integration test base for LineCheck and AccountFixer. */
@Import(ValidationTestConfiguration::class)
abstract class ValidationIntegrationTestBase : PostgresIntegrationTest() {
    @Autowired
    protected lateinit var lineCheck: LineCheckService

    @Autowired
    protected lateinit var fixer: AccountFixer

    @Autowired
    protected lateinit var transfers: TransferService

    @Autowired
    protected lateinit var resolver: AccountResolver

    @Autowired
    protected lateinit var calculator: BalanceCalculator

    @Autowired
    protected lateinit var lineChecks: LineCheckRepository

    @Autowired
    protected lateinit var sqlCapture: SqlCapture

    @BeforeEach
    fun clearCapture() = sqlCapture.clear()

    protected fun usd(minor: Long) = Money.ofMinor(minor, CurrencyUnit.of("USD"))

    protected fun btc(minor: Long) = Money.ofMinor(minor, CurrencyUnit.of("BTC"))

    /** An unscoped account, or one scoped to the user with id [user]. */
    protected fun acct(
        identifier: String,
        user: Int? = null,
    ): AccountInstance = if (user == null) resolver.account(identifier) else resolver.account(identifier, scope = TestUser(user.toLong()))

    protected fun transfer(
        minor: Long,
        from: AccountInstance,
        to: AccountInstance,
        code: String,
    ) {
        transfers.transfer(Money.ofMinor(minor, from.currency), from, to, code)
    }

    /** `update_all` on raw columns: no timestamps are touched, as in the Ruby scenarios. */
    protected fun sql(statement: String) = jdbcTemplate.update(statement)

    protected fun lineCheckCount(): Long = lineChecks.count()

    protected fun firstLineBalance(): Long =
        jdbcTemplate.queryForObject("SELECT balance FROM double_entry_lines ORDER BY id LIMIT 1", Long::class.java)!!

    protected fun firstAccountBalance(): Long =
        jdbcTemplate.queryForObject("SELECT balance FROM double_entry_account_balances ORDER BY id LIMIT 1", Long::class.java)!!

    protected fun lineBalances(): List<Long> =
        jdbcTemplate.queryForList("SELECT balance FROM double_entry_lines ORDER BY id", Long::class.java)

    protected fun cachedBalance(account: AccountInstance): Long =
        if (account.scopeIdentity == null) {
            jdbcTemplate.queryForObject(
                "SELECT balance FROM double_entry_account_balances WHERE account = ? AND scope IS NULL",
                Long::class.java,
                account.identifier,
            )!!
        } else {
            jdbcTemplate.queryForObject(
                "SELECT balance FROM double_entry_account_balances WHERE account = ? AND scope = ?",
                Long::class.java,
                account.identifier,
                account.scopeIdentity,
            )!!
        }

    /**
     * The rows against the oracle's `db_after`. With [ignoreLog] the `log` column is left out on both sides: it holds
     * Ruby record dumps, which Kotlin renders differently (X-06); callers assert the log separately.
     */
    protected fun assertDbMatches(
        scenario: OracleScenario,
        ignoreLog: Boolean = false,
    ) {
        val actual = DbSnapshot.read(jdbcTemplate.dataSource!!)
        val expected = scenario.dbAfter
        val strip: (Map<String, List<ObjectNode>>) -> Map<String, List<ObjectNode>> = { tables ->
            if (!ignoreLog) {
                tables
            } else {
                tables.mapValues { (_, rows) -> rows.map { row -> row.deepCopy().also { it.remove("log") } } }
            }
        }
        RowComparator.compare(strip(expected), strip(actual)).assertMatches(scenario.id)
    }
}
