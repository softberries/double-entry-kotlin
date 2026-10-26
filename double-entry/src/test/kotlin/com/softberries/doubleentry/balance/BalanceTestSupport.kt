package com.softberries.doubleentry.balance

import com.softberries.doubleentry.account.AccountFixtures
import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.config.DoubleEntryConfigurer
import com.softberries.doubleentry.parity.SqlCapture
import com.softberries.doubleentry.testing.PostgresIntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/** The accounts the balance oracle scenarios use (`tools/oracle.rb:51-62`) and a JDBC [SqlCapture]. */
@TestConfiguration(proxyBeanMethods = false)
class BalanceTestConfiguration {
    @Bean
    fun balanceTestAccounts(): DoubleEntryConfigurer =
        DoubleEntryConfigurer { builder ->
            builder.accounts {
                define("checking", scopeIdentifier = AccountFixtures.userScope)
                define("test", scopeIdentifier = AccountFixtures.userScope)
                define("bank")
                define("cash")
                define("store")
                define("btc_store", currency = "BTC")
                define("jpy_bank", currency = "JPY")
            }
        }

    companion object {
        private val capture = SqlCapture()

        @Bean
        @JvmStatic
        fun balanceSqlCapture(): SqlCapture = capture

        @Bean
        @JvmStatic
        fun balanceSqlCapturePostProcessor(): BeanPostProcessor = capture.beanPostProcessor()
    }
}

/** Integration test base for balances: the oracle accounts, [LineFixtures] and the SQL of the last query. */
@Import(BalanceTestConfiguration::class)
abstract class BalanceIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    protected lateinit var calculator: BalanceCalculator

    @Autowired
    protected lateinit var resolver: AccountResolver

    @Autowired
    protected lateinit var sqlCapture: SqlCapture

    protected val fixtures: LineFixtures by lazy { LineFixtures(jdbcTemplate) }

    @BeforeEach
    fun clearCapture() = sqlCapture.clear()

    protected fun account(
        identifier: String,
        scopeIdentity: String? = null,
    ): AccountInstance = resolver.account(identifier, scopeIdentity = scopeIdentity)
}
