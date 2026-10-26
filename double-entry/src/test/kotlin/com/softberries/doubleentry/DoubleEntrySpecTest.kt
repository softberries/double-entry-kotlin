package com.softberries.doubleentry

import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.balance.BalanceOptions
import com.softberries.doubleentry.balance.cache.AccountBalanceRepository
import com.softberries.doubleentry.config.DoubleEntryConfigurer
import com.softberries.doubleentry.config.doubleEntry
import com.softberries.doubleentry.errors.DuplicateAccount
import com.softberries.doubleentry.errors.DuplicateTransfer
import com.softberries.doubleentry.errors.MismatchedCurrencies
import com.softberries.doubleentry.errors.TransferNotAllowed
import com.softberries.doubleentry.errors.UnknownAccount
import com.softberries.doubleentry.ledger.Line
import com.softberries.doubleentry.ledger.Lines
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.testing.MutableClock
import com.softberries.doubleentry.testing.PostgresIntegrationTest
import com.softberries.doubleentry.testing.TestUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.Instant

/*
 * Port of `spec/double_entry_spec.rb` (39 examples) against the `DoubleEntry` facade bean.
 *
 * The Ruby spec replaces `DoubleEntry.config` in each `describe` block. The Kotlin configuration is immutable (D-016,
 * X-09), so every block is its own test class with its own `DoubleEntryConfigurer`, i.e. its own application context.
 * Those contexts use a small connection pool so that the cached contexts fit in the shared container.
 */

private fun usd(minor: Long) = Money.ofMinor(minor, CurrencyUnit.USD)

private fun btc(minor: Long) = Money.ofMinor(minor, CurrencyUnit.BTC)

/** Base of the per-`describe` classes: the facade, the line reader and `lines_for_account` (`double_entry_spec_helper.rb:3-8`). */
@TestPropertySource(properties = ["spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=0"])
abstract class DoubleEntrySpecBase : PostgresIntegrationTest() {
    @Autowired
    protected lateinit var doubleEntry: DoubleEntry

    @Autowired
    protected lateinit var lines: Lines

    protected fun linesForAccount(account: AccountInstance): List<Line> =
        lines.all().filter {
            it.accountIdentifier == account.identifier && (!account.scoped || it.scopeIdentity == account.scopeIdentity)
        }
}

/** `describe 'configuration'`: [1:1:1], [1:1:2]. Definition errors are thrown when the configuration is built. */
class DoubleEntryConfigurationSpecTest {
    @Test
    fun `checks for duplicates of accounts`() {
        shouldThrow<DuplicateAccount> {
            doubleEntry {
                accounts {
                    define("gah!")
                    define("gah!")
                }
            }
        }
    }

    @Test
    fun `checks for duplicates of transfers`() {
        shouldThrow<DuplicateTransfer> {
            doubleEntry {
                transfers {
                    define(from = "savings", to = "cash", code = "xfer")
                    define(from = "savings", to = "cash", code = "xfer")
                }
            }
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class AccountsSpecDefinitions {
    @Bean
    fun accountsSpecConfigurer() =
        DoubleEntryConfigurer {
            it.accounts {
                define("unscoped")
                define("scoped", scopeIdentifier = { scope -> (scope as TestUser).id })
            }
        }
}

/** `describe 'accounts'`: [1:2:1:1]..[1:2:1:5], [1:2:2:1], [1:2:3:1]. `double('a scope', id: 1)` is `TestUser(1)`. */
@Import(AccountsSpecDefinitions::class)
class DoubleEntryAccountsSpecTest : DoubleEntrySpecBase() {
    private val scope = TestUser(1)

    @Test
    fun `can find an unscoped account by identifier`() {
        doubleEntry.account("unscoped").shouldNotBeNull()
    }

    @Test
    fun `can find a scoped account by identifier`() {
        doubleEntry.account("scoped", scope = scope).shouldNotBeNull()
    }

    @Test
    fun `raises an exception when it cannot find an account`() {
        shouldThrow<UnknownAccount> { doubleEntry.account("invalid") }
    }

    @Test
    fun `raises exception when you ask for an unscoped account w - scope`() {
        shouldThrow<UnknownAccount> { doubleEntry.account("unscoped", scope = scope) }
    }

    @Test
    fun `raises exception when you ask for a scoped account w - out scope`() {
        shouldThrow<UnknownAccount> { doubleEntry.account("scoped") }
    }

    @Test
    fun `an unscoped account has an identifier`() {
        doubleEntry.account("unscoped").identifier shouldBe "unscoped"
    }

    @Test
    fun `a scoped account has an identifier`() {
        doubleEntry.account("scoped", scope = scope).identifier shouldBe "scoped"
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TransfersSpecDefinitions {
    @Bean
    fun transfersSpecConfigurer() =
        DoubleEntryConfigurer {
            it.accounts {
                define("savings")
                define("cash")
                define("trash")
                define("bitbucket", currency = "btc")
            }
            it.transfers {
                define(from = "savings", to = "cash", code = "xfer")
                define(from = "trash", to = "bitbucket", code = "mismatch_xfer")
            }
        }
}

/** `describe 'transfers'`: [1:3:1]..[1:3:5]. */
@Import(TransfersSpecDefinitions::class)
class DoubleEntryTransfersSpecTest : DoubleEntrySpecBase() {
    private val savings get() = doubleEntry.account("savings")
    private val cash get() = doubleEntry.account("cash")
    private val trash get() = doubleEntry.account("trash")
    private val bitbucket get() = doubleEntry.account("bitbucket")

    @Test
    fun `can transfer from an account to an account, if the transfer is allowed`() {
        doubleEntry.transfer(usd(100_00), from = savings, to = cash, code = "xfer")
    }

    @Test
    fun `raises an exception when the transfer is not allowed (wrong direction)`() {
        shouldThrow<TransferNotAllowed> { doubleEntry.transfer(usd(100_00), from = cash, to = savings, code = "xfer") }
    }

    @Test
    fun `raises an exception when the transfer is not allowed (wrong code)`() {
        shouldThrow<TransferNotAllowed> { doubleEntry.transfer(usd(100_00), from = savings, to = cash, code = "yfer") }
    }

    /** Ruby omits the code (`code: nil`); the Kotlin code is a required String, so any code shows the same rule. */
    @Test
    fun `raises an exception when the transfer is not allowed (does not exist, at all)`() {
        shouldThrow<TransferNotAllowed> { doubleEntry.transfer(usd(100_00), from = cash, to = trash, code = "xfer") }
    }

    @Test
    fun `raises an exception when the transfer is not allowed (mismatched currencies)`() {
        shouldThrow<MismatchedCurrencies> {
            doubleEntry.transfer(usd(100_00), from = trash, to = bitbucket, code = "mismatch_xfer")
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class LinesSpecDefinitions {
    @Bean
    fun linesSpecConfigurer() =
        DoubleEntryConfigurer {
            it.accounts {
                define("a")
                define("b")
            }
            it.transfers { define(code = "xfer", from = "a", to = "b") }
        }
}

/** `describe 'lines'`: [1:4:1]..[1:4:8]. */
@Import(LinesSpecDefinitions::class)
class DoubleEntryLinesSpecTest : DoubleEntrySpecBase() {
    private val accountA get() = doubleEntry.account("a")
    private val accountB get() = doubleEntry.account("b")
    private val creditLine get() = linesForAccount(accountA).first()
    private val debitLine get() = linesForAccount(accountB).first()

    @BeforeEach
    fun transfer() {
        doubleEntry.transfer(usd(10_00), from = accountA, to = accountB, code = "xfer")
    }

    @Test
    fun `has an amount`() {
        creditLine.amount shouldBe usd(-10_00)
        debitLine.amount shouldBe usd(10_00)
    }

    @Test
    fun `has a code`() {
        creditLine.code shouldBe "xfer"
        debitLine.code shouldBe "xfer"
    }

    @Test
    fun `auto-sets scope when assigning account (and partner_account)`() {
        creditLine.accountIdentifier shouldBe "a"
        creditLine.scopeIdentity.shouldBeNull()
        creditLine.partnerAccountIdentifier shouldBe "b"
        creditLine.partnerScopeIdentity.shouldBeNull()
    }

    @Test
    fun `has a partner_account`() {
        creditLine.partnerAccount shouldBe debitLine.account
    }

    @Test
    fun `knows if it is an increase or decrease`() {
        creditLine.isDecrease shouldBe true
        debitLine.isIncrease shouldBe true
        creditLine.isIncrease shouldBe false
        debitLine.isDecrease shouldBe false
    }

    @Test
    fun `can reference its partner`() {
        lines.partner(creditLine) shouldBe debitLine
        lines.partner(debitLine) shouldBe creditLine
    }

    @Test
    fun `can ask for its pair (credit always coming first)`() {
        lines.pair(creditLine) shouldBe (creditLine to debitLine)
        lines.pair(debitLine) shouldBe (creditLine to debitLine)
    }

    @Test
    fun `can ask for the account (and get an instance)`() {
        creditLine.account shouldBe accountA
        debitLine.account shouldBe accountB
    }
}

@TestConfiguration(proxyBeanMethods = false)
class BalancesSpecDefinitions {
    @Bean
    fun balancesSpecConfigurer() =
        DoubleEntryConfigurer {
            it.accounts {
                define("work")
                define("cash")
                define("savings")
                define("store")
                define("btc_store", currency = "BTC")
                define("btc_wallet", currency = "BTC")
            }
            it.transfers {
                define(code = "salary", from = "work", to = "cash")
                define(code = "xfer", from = "cash", to = "savings")
                define(code = "xfer", from = "savings", to = "cash")
                define(code = "purchase", from = "cash", to = "store")
                define(code = "layby", from = "cash", to = "store")
                define(code = "deposit", from = "cash", to = "store")
                define(code = "btc_ex", from = "btc_store", to = "btc_wallet")
            }
        }
}

/**
 * `describe 'balances'`: [1:5:1]..[1:5:10]. Timecop is the [MutableClock]; "now" is frozen at [MutableClock.ORACLE_T0]
 * for the whole example, so `3.weeks.ago` etc. are fixed instants.
 */
@Import(BalancesSpecDefinitions::class)
class DoubleEntryBalancesSpecTest : DoubleEntrySpecBase() {
    @Autowired
    private lateinit var balances: AccountBalanceRepository

    @Autowired
    private lateinit var resolver: AccountResolver

    private val now: Instant = MutableClock.ORACLE_T0
    private val work get() = doubleEntry.account("work")
    private val savings get() = doubleEntry.account("savings")
    private val cash get() = doubleEntry.account("cash")
    private val store get() = doubleEntry.account("store")
    private val btcStore get() = doubleEntry.account("btc_store")
    private val btcWallet get() = doubleEntry.account("btc_wallet")

    private fun weeksAgo(weeks: Long) = now.minus(Duration.ofDays(7 * weeks))

    private fun at(
        instant: Instant,
        block: () -> Unit,
    ) {
        clock.set(instant)
        block()
    }

    @BeforeEach
    fun history() {
        at(weeksAgo(3).plus(Duration.ofDays(1))) {
            doubleEntry.transfer(usd(1_000_00), from = work, code = "salary", to = cash)
            doubleEntry.transfer(usd(500_00), from = cash, code = "xfer", to = savings)
        }
        at(weeksAgo(2).plus(Duration.ofDays(1))) {
            doubleEntry.transfer(usd(200_00), from = cash, code = "purchase", to = store)
            doubleEntry.transfer(usd(100_00), from = cash, code = "layby", to = store)
            doubleEntry.transfer(usd(100_00), from = cash, code = "deposit", to = store)
        }
        at(weeksAgo(1).plus(Duration.ofDays(1))) {
            doubleEntry.transfer(usd(200_00), from = savings, code = "xfer", to = cash)
            doubleEntry.transfer(usd(200_00), from = cash, code = "purchase", to = store)
        }
        at(weeksAgo(-1)) {
            doubleEntry.transfer(btc(200_00), from = btcStore, code = "btc_ex", to = btcWallet)
        }
        clock.set(now)
    }

    @Test
    fun `has the initial balances that we expect`() {
        doubleEntry.balance(work) shouldBe usd(-1_000_00)
        doubleEntry.balance(cash) shouldBe usd(100_00)
        doubleEntry.balance(savings) shouldBe usd(300_00)
        doubleEntry.balance(store) shouldBe usd(600_00)
        doubleEntry.balance(btcWallet) shouldBe btc(200_00)
    }

    @Test
    fun `should have correct account balance records`() {
        listOf(work, cash, savings, store, btcWallet).forEach { account ->
            balances.findByAccount(account)!!.balance(resolver) shouldBe doubleEntry.balance(account)
        }
    }

    @Test
    fun `should have correct account balance currencies`() {
        balances.findByAccount(btcWallet)!!.balance(resolver).currency shouldBe CurrencyUnit.BTC
    }

    @Test
    fun `affects origin - destination balance after transfer`() {
        val savingsBalance = doubleEntry.balance(savings)
        val cashBalance = doubleEntry.balance(cash)
        val amount = usd(10_00)

        doubleEntry.transfer(amount, from = savings, code = "xfer", to = cash)

        doubleEntry.balance(savings) shouldBe savingsBalance - amount
        doubleEntry.balance(cash) shouldBe cashBalance + amount
    }

    @Test
    fun `can be queried at a given point in time`() {
        doubleEntry.balance(cash, BalanceOptions(at = weeksAgo(1))) shouldBe usd(100_00)
    }

    @Test
    fun `can be queries between two points in time`() {
        doubleEntry.balance(cash, BalanceOptions(from = weeksAgo(3), to = weeksAgo(2))) shouldBe usd(500_00)
    }

    @Test
    fun `can be queried between two points in time, even in the future`() {
        doubleEntry.balance(btcWallet, BalanceOptions(from = now, to = weeksAgo(-2))) shouldBe btc(200_00)
    }

    @Test
    fun `can report on balances, scoped by code`() {
        doubleEntry.balance(cash, BalanceOptions(code = "salary")) shouldBe usd(1_000_00)
    }

    @Test
    fun `can report on balances, scoped by many codes`() {
        doubleEntry.balance(store, BalanceOptions(codes = listOf("layby", "deposit"))) shouldBe usd(200_00)
    }

    @Test
    fun `has running balances for each line`() {
        linesForAccount(cash).map { it.balance } shouldBe
            listOf(1_000_00L, 500_00, 300_00, 200_00, 100_00, 300_00, 100_00).map(::usd)
    }
}

/** The test-only `User` of the Ruby spec; [NotAUser] stands for `double(id: 7)`. */
private data class NotAUser(
    val id: Long,
)

@TestConfiguration(proxyBeanMethods = false)
class ScopingSpecDefinitions {
    @Bean
    fun scopingSpecConfigurer() =
        DoubleEntryConfigurer {
            it.accounts {
                // `raise 'not a User' unless user.class.name == 'User'` (RuntimeError).
                val userScope: (Any) -> Any? = { user -> (user as? TestUser)?.id ?: throw RuntimeException("not a User") }
                define("bank")
                define("cash", scopeIdentifier = userScope)
                define("savings", scopeIdentifier = userScope)
            }
            it.transfers {
                define(from = "bank", to = "cash", code = "xfer")
                define(from = "cash", to = "cash", code = "xfer")
                define(from = "cash", to = "savings", code = "xfer")
            }
        }
}

/** `describe 'scoping of accounts'`: [1:6:1]..[1:6:7] (BR-049). `create(:user)` is a [TestUser] with a fresh id. */
@Import(ScopingSpecDefinitions::class)
class DoubleEntryScopingSpecTest : DoubleEntrySpecBase() {
    private val bank get() = doubleEntry.account("bank")
    private val john = TestUser(1)
    private val ryan = TestUser(2)
    private val johnsCash get() = doubleEntry.account("cash", scope = john)
    private val ryansCash get() = doubleEntry.account("cash", scope = ryan)
    private val ryansSavings get() = doubleEntry.account("savings", scope = ryan)

    @Test
    fun `treats each separately scoped account having their own separate balances`() {
        doubleEntry.transfer(usd(20_00), from = bank, to = johnsCash, code = "xfer")
        doubleEntry.transfer(usd(10_00), from = bank, to = ryansCash, code = "xfer")
        doubleEntry.balance(johnsCash) shouldBe usd(20_00)
        doubleEntry.balance(ryansCash) shouldBe usd(10_00)
    }

    @Test
    fun `allows transfer between two separately scoped accounts`() {
        doubleEntry.transfer(usd(10_00), from = ryansCash, to = johnsCash, code = "xfer")
        doubleEntry.balance(ryansCash) shouldBe usd(-10_00)
        doubleEntry.balance(johnsCash) shouldBe usd(10_00)
    }

    @Test
    fun `reports balance correctly if called from either account or finances object`() {
        doubleEntry.transfer(usd(10_00), from = ryansCash, to = johnsCash, code = "xfer")
        doubleEntry.balance(ryansCash) shouldBe usd(-10_00)
        doubleEntry.balance("cash", scope = ryan) shouldBe usd(-10_00)
    }

    @Test
    fun `raises an exception if you try to scope with an object instance of differing class to that defined on the account`() {
        val notAUser = NotAUser(7)

        shouldThrowExactly<RuntimeException> { doubleEntry.account("savings", scope = notAUser) }.message shouldBe "not a User"
        shouldThrowExactly<RuntimeException> { doubleEntry.balance("savings", scope = notAUser) }.message shouldBe "not a User"
    }

    @Test
    fun `raises exception if you try to transfer between the same account, despite it being scoped`() {
        shouldThrow<TransferNotAllowed> { doubleEntry.transfer(usd(10_00), from = ryansCash, to = ryansCash, code = "xfer") }
    }

    @Test
    fun `allows transfer from one persons account to the same persons other kind of account`() {
        doubleEntry.transfer(usd(100_00), from = ryansCash, to = ryansSavings, code = "xfer")
        doubleEntry.balance(ryansCash) shouldBe usd(-100_00)
        doubleEntry.balance(ryansSavings) shouldBe usd(100_00)
    }

    @Test
    fun `disallows you to report on scoped accounts globally`() {
        shouldThrow<UnknownAccount> { doubleEntry.balance("cash") }
    }
}
