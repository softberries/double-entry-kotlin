package com.softberries.doubleentry

import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.config.DoubleEntryConfigurer
import com.softberries.doubleentry.errors.AccountWouldBeSentNegative
import com.softberries.doubleentry.errors.UnknownAccount
import com.softberries.doubleentry.ledger.Lines
import com.softberries.doubleentry.locking.errors.LockMustBeOutermostTransaction
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.parity.ExceptionComparator
import com.softberries.doubleentry.parity.MoneyComparator
import com.softberries.doubleentry.parity.OracleScenarios
import com.softberries.doubleentry.testing.PostgresIntegrationTest
import com.softberries.doubleentry.testing.TestUser
import com.softberries.doubleentry.transfer.TransferIntegrationTestBase
import com.softberries.doubleentry.transfer.TransferResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * BR-049 on the facade against the oracle's `standard_config`: `DoubleEntry.balance(:checking, scope: user)` resolves the
 * account first (`balance_symbol_with_scope.json`, `balance_scoped_without_scope.json`; spec DE [1:6:3], [1:6:4], [1:6:7]).
 */
class DoubleEntryBalanceOracleTest : TransferIntegrationTestBase() {
    @Autowired
    private lateinit var doubleEntry: DoubleEntry

    @Test
    fun `balance by identifier and scope (balance_symbol_with_scope)`() {
        val oracle = OracleScenarios.load("balance_symbol_with_scope")
        val user = TestUser(1)
        doubleEntry.transfer(usd(300), from = doubleEntry.account("bank"), to = doubleEntry.account("checking", user), code = "deposit")

        val balance = doubleEntry.balance("checking", scope = user)

        balance shouldBe usd(300)
        balance.toString() shouldBe "3.00"
        MoneyComparator.compare(oracle.result!!, observed(balance)).assertMatches(oracle.id)
        assertDbMatches(oracle)
    }

    @Test
    fun `balance by identifier and stored scope identity (balance_symbol_with_scope, scope_identity option)`() {
        val oracle = OracleScenarios.load("balance_symbol_with_scope")
        val checking = doubleEntry.account("checking", TestUser(1))
        doubleEntry.transfer(usd(300), from = doubleEntry.account("bank"), to = checking, code = "deposit")

        val balance = doubleEntry.balance("checking", scopeIdentity = "1")

        balance shouldBe usd(300)
        MoneyComparator.compare(oracle.result!!, observed(balance)).assertMatches(oracle.id)
        doubleEntry.balance("checking", scopeIdentity = "2") shouldBe usd(0)
    }

    @Test
    fun `balance of a scoped identifier without a scope (balance_scoped_without_scope)`() {
        val oracle = OracleScenarios.load("balance_scoped_without_scope")

        val thrown = shouldThrow<UnknownAccount> { doubleEntry.balance("checking") }

        thrown.message shouldBe "account: checking scoped?: false"
        ExceptionComparator.compare(oracle.exception!!, thrown).assertMatches(oracle.id)
        sqlCapture.shapes shouldBe emptyList()
        lineCount() shouldBe 0
    }

    @Test
    fun `an exception of the scope identifier propagates unchanged (DE 1-6-4)`() {
        val thrown = shouldThrowExactly<IllegalArgumentException> { doubleEntry.balance("checking", scope = "not a user") }

        thrown.message shouldBe "not a User"
    }
}

/** The accounts and transfers of `spec/support/accounts.rb:3-25`, the Ruby spec suite's default configuration. */
@TestConfiguration(proxyBeanMethods = false)
class SpecSupportAccounts {
    @Bean
    fun specSupportAccountsConfigurer() =
        DoubleEntryConfigurer {
            it.accounts {
                val userScope: (Any) -> Any? = { user -> (user as TestUser).id }
                define("savings", scopeIdentifier = userScope, positiveOnly = true)
                define("checking", scopeIdentifier = userScope, positiveOnly = true)
                define("test", scopeIdentifier = userScope)
                define("btc_test", scopeIdentifier = userScope, currency = "BTC")
                define("btc_savings", scopeIdentifier = userScope, currency = "BTC")
                define("deposit_fees", scopeIdentifier = userScope, positiveOnly = true)
                define("account_fees", scopeIdentifier = userScope, positiveOnly = true)
            }
            it.transfers {
                define(from = "test", to = "savings", code = "bonus")
                define(from = "test", to = "checking", code = "pay")
                define(from = "savings", to = "test", code = "test_withdrawal")
                define(from = "btc_test", to = "btc_savings", code = "btc_test_transfer")
                define(from = "savings", to = "deposit_fees", code = "fee")
                define(from = "savings", to = "account_fees", code = "fee")
            }
        }
}

/**
 * The facade delegates to the feature services with identical semantics (`double_entry.rb:51-53,88-90,142-145,163-165`),
 * using the Ruby spec suite's account fixture. It adds no transaction of its own (D-007).
 */
@Import(SpecSupportAccounts::class)
@TestPropertySource(properties = ["spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=0"])
class DoubleEntryFacadeTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var doubleEntry: DoubleEntry

    @Autowired
    private lateinit var resolver: AccountResolver

    @Autowired
    private lateinit var lines: Lines

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val alice = TestUser(1)

    private fun usd(minor: Long) = Money.ofMinor(minor, CurrencyUnit.USD)

    @Test
    fun `account delegates to the resolver`() {
        doubleEntry.account("checking", scope = alice) shouldBe resolver.account("checking", scope = alice)
        doubleEntry.account("checking", scopeIdentity = "1") shouldBe resolver.account("checking", scope = alice)
        shouldThrow<UnknownAccount> { doubleEntry.account("checking") }.message shouldBe "account: checking scoped?: false"
    }

    @Test
    fun `transfer returns the credit and debit lines`() {
        val test = doubleEntry.account("test", alice)
        val checking = doubleEntry.account("checking", alice)

        val result: TransferResult = doubleEntry.transfer(usd(10_00), from = test, to = checking, code = "pay")

        result.credit shouldBe lines.find(1)
        result.debit shouldBe lines.find(2)
        result.credit.amount shouldBe usd(-10_00)
        result.debit.amount shouldBe usd(10_00)
        result.credit.partnerId shouldBe result.debit.id
        doubleEntry.balance(checking) shouldBe usd(10_00)
        doubleEntry.balance("test", scope = alice) shouldBe usd(-10_00)
    }

    @Test
    fun `both fee transfers out of savings are allowed and positive-only accounts are enforced`() {
        val test = doubleEntry.account("test", alice)
        val savings = doubleEntry.account("savings", alice)
        doubleEntry.transfer(usd(5_00), from = test, to = savings, code = "bonus")

        doubleEntry.transfer(usd(1_00), from = savings, to = doubleEntry.account("deposit_fees", alice), code = "fee")
        doubleEntry.transfer(usd(1_00), from = savings, to = doubleEntry.account("account_fees", alice), code = "fee")

        doubleEntry.balance(savings) shouldBe usd(3_00)
        shouldThrow<AccountWouldBeSentNegative> {
            doubleEntry.transfer(usd(4_00), from = savings, to = test, code = "test_withdrawal")
        }
        doubleEntry.balance(savings) shouldBe usd(3_00)
    }

    @Test
    fun `lockAccounts returns the block value and transfers may run inside it`() {
        val test = doubleEntry.account("test", alice)
        val checking = doubleEntry.account("checking", alice)

        val value =
            doubleEntry.lockAccounts(test, checking) {
                doubleEntry.transfer(usd(1_00), from = test, to = checking, code = "pay")
                doubleEntry.transfer(usd(2_00), from = test, to = checking, code = "pay")
                "done"
            }

        value shouldBe "done"
        doubleEntry.balance(checking) shouldBe usd(3_00)
        doubleEntry.lockAccounts(listOf(checking)) { it }.javaClass.simpleName shouldBe "LockContext"
    }

    @Test
    fun `transfer and lockAccounts inside an enclosing transaction violate the outermost rule (D-007)`() {
        val test = doubleEntry.account("test", alice)
        val checking = doubleEntry.account("checking", alice)
        val template = TransactionTemplate(transactionManager)

        shouldThrow<LockMustBeOutermostTransaction> {
            template.execute { doubleEntry.transfer(usd(1_00), from = test, to = checking, code = "pay") }
        }
        shouldThrow<LockMustBeOutermostTransaction> { template.execute { doubleEntry.lockAccounts(test) { 1 } } }
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM double_entry_lines", Long::class.java) shouldBe 0
    }
}
