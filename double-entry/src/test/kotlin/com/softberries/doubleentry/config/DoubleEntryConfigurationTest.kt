package com.softberries.doubleentry.config

import com.softberries.doubleentry.account.AccountDefinition
import com.softberries.doubleentry.account.AccountSet
import com.softberries.doubleentry.errors.AccountIdentifierTooLongError
import com.softberries.doubleentry.errors.DuplicateAccount
import com.softberries.doubleentry.errors.DuplicateTransfer
import com.softberries.doubleentry.errors.TransferCodeTooLongError
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.UnknownCurrencyException
import com.softberries.doubleentry.testing.OracleSample
import com.softberries.doubleentry.transfer.TransferDefinition
import com.softberries.doubleentry.transfer.TransferSet
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties

/** Port of `spec/double_entry/configuration_spec.rb` [1:1:1]..[1:5:1], plus BR-001/BR-004/BR-008 and X-09. */
class DoubleEntryConfigurationTest {
    @Test
    fun `accounts is an AccountSet (CF 1-1-1)`() {
        doubleEntry().accounts.shouldBeInstanceOf<AccountSet>()
    }

    @Test
    fun `transfers is a TransferSet (CF 1-2-1)`() {
        doubleEntry().transfers.shouldBeInstanceOf<TransferSet>()
    }

    /** CF describe 'max lengths'. */
    @Nested
    inner class MaxLengths {
        @Test
        fun `code_max_length is nil when not set (CF 1-3-1-1-1)`() {
            doubleEntry().codeMaxLength.shouldBeNull()
        }

        @Test
        fun `scope_identifier_max_length is nil when not set (CF 1-3-1-2-1)`() {
            doubleEntry().scopeIdentifierMaxLength.shouldBeNull()
        }

        @Test
        fun `account_identifier_max_length is nil when not set (CF 1-3-1-3-1)`() {
            doubleEntry().accountIdentifierMaxLength.shouldBeNull()
        }

        @Test
        fun `a code max length of 10 reads back 10 (CF 1-3-2-1-1)`() {
            doubleEntry { codeMaxLength = 10 }.codeMaxLength shouldBe 10
        }

        @Test
        fun `a scope identifier max length of 11 reads back 11 (CF 1-3-3-1-1)`() {
            doubleEntry { scopeIdentifierMaxLength = 11 }.scopeIdentifierMaxLength shouldBe 11
        }

        @Test
        fun `an account identifier max length of 9 reads back 9 (CF 1-3-4-1-1)`() {
            doubleEntry { accountIdentifierMaxLength = 9 }.accountIdentifierMaxLength shouldBe 9
        }

        @Test
        fun `defaults match config_defaults json (BR-008)`() {
            val expected = OracleSample.result("config_defaults")
            val config = doubleEntry()

            listOf("code_max_length", "scope_identifier_max_length", "account_identifier_max_length").forEach {
                expected[it].isNull shouldBe true
            }
            config.codeMaxLength.shouldBeNull()
            config.scopeIdentifierMaxLength.shouldBeNull()
            config.accountIdentifierMaxLength.shouldBeNull()
            config.defaultCurrency.code shouldBe expected["default_currency_of_account_without_currency"].asText()
        }

        @Test
        fun `max lengths apply to definitions wherever they are set in the block`() {
            shouldThrow<AccountIdentifierTooLongError> {
                doubleEntry {
                    accounts { define("abcdef") }
                    accountIdentifierMaxLength = 5
                }
            }
            shouldThrow<TransferCodeTooLongError> {
                doubleEntry {
                    transfers { define("a", "b", "abcde") }
                    codeMaxLength = 4
                }
            }
        }
    }

    @Test
    fun `define_accounts yields the account set builder (CF 1-4-1)`() {
        var received: AccountSet.Builder? = null

        val config = doubleEntry { accounts { received = this.also { define("savings") } } }

        received.shouldNotBeNull()
        config.accounts.map { it.identifier } shouldBe listOf("savings")
    }

    @Test
    fun `define_transfers yields the transfer set builder (CF 1-5-1)`() {
        var received: TransferSet.Builder? = null

        val config = doubleEntry { transfers { received = this.also { define("bank", "cash", "salary") } } }

        received.shouldNotBeNull()
        config.transfers.all shouldBe listOf(TransferDefinition("bank", "cash", "salary"))
    }

    @Test
    fun `accounts and transfers blocks can be given more than once and accumulate`() {
        val config =
            doubleEntry {
                accounts { define("bank") }
                accounts { define("cash") }
                transfers { define("bank", "cash", "salary") }
                transfers { define("cash", "bank", "refund") }
            }

        config.accounts.map { it.identifier } shouldBe listOf("bank", "cash")
        config.transfers.size shouldBe 2
        shouldThrow<DuplicateAccount> {
            doubleEntry {
                accounts { define("bank") }
                accounts { define("bank") }
            }
        }
        shouldThrow<DuplicateTransfer> {
            doubleEntry {
                transfers { define("bank", "cash", "salary") }
                transfers { define("bank", "cash", "salary") }
            }
        }
    }

    /** BR-001: currency default from `double-entry.default-currency`, explicit codes case-insensitive. */
    @Nested
    inner class Currency {
        @Test
        fun `an account without currency gets the default currency USD`() {
            doubleEntry { accounts { define("savings") } }.accounts.currencyOf("savings") shouldBe CurrencyUnit.USD
        }

        @Test
        fun `the default currency comes from the properties`() {
            val config = doubleEntry(DoubleEntryProperties(defaultCurrency = "eur")) { accounts { define("savings") } }

            config.defaultCurrency shouldBe CurrencyUnit.EUR
            config.accounts.currencyOf("savings") shouldBe CurrencyUnit.EUR
        }

        @Test
        fun `BTC and btc both give BTC and currencyOf answers BTC and null (config_defaults)`() {
            val expected = OracleSample.result("config_defaults")
            val config =
                doubleEntry {
                    accounts {
                        define("btc_store", currency = "BTC")
                        define("bitbucket", currency = "btc")
                    }
                }

            config.accounts.currencyOf("btc_store")?.code shouldBe expected["Account.currency(:btc_store)"].asText()
            config.accounts.currencyOf("bitbucket") shouldBe CurrencyUnit.BTC
            config.accounts.currencyOf("unknown").shouldBeNull()
        }

        @Test
        fun `an unknown currency fails when the configuration is built`() {
            shouldThrow<UnknownCurrencyException> { doubleEntry { accounts { define("x", currency = "XYZ") } } }
            shouldThrow<UnknownCurrencyException> { doubleEntry(DoubleEntryProperties(defaultCurrency = "XYZ")) }
        }
    }

    /** BR-004: no check of transfer account identifiers against the account set. */
    @Test
    fun `a transfer between undefined accounts is accepted (config_transfer_unknown_accounts_allowed)`() {
        val config =
            doubleEntry {
                accounts { define("bank") }
                transfers { define(from = "nope", to = "nada", code = "x") }
            }

        config.transfers.find("nope", "nada", "x") shouldBe TransferDefinition("nope", "nada", "x")
        config.accounts.findWithoutScope("nope").shouldBeNull()
    }

    /** X-09 (D-016): the built configuration cannot be changed. */
    @Nested
    inner class Immutability {
        private val config =
            doubleEntry {
                accounts { define("bank") }
                transfers { define("bank", "cash", "salary") }
            }

        @Test
        fun `the public API has no mutable properties or setters`() {
            listOf<KClass<*>>(
                DoubleEntryConfiguration::class,
                AccountSet::class,
                AccountDefinition::class,
                TransferSet::class,
                TransferDefinition::class,
            ).forEach { type ->
                type.memberProperties
                    .filter { it.visibility == KVisibility.PUBLIC }
                    .filterIsInstance<KMutableProperty<*>>()
                    .shouldBeEmpty()
                type.java.methods
                    .filter { it.name.startsWith("set") || it.name in MUTATOR_NAMES }
                    .shouldBeEmpty()
            }
        }

        @Test
        fun `collections are read-only even through a cast`() {
            @Suppress("UNCHECKED_CAST")
            val accounts = config.accounts.all as MutableList<AccountDefinition>

            @Suppress("UNCHECKED_CAST")
            val transfers = config.transfers.all as MutableList<TransferDefinition>

            shouldThrow<UnsupportedOperationException> { accounts.clear() }
            shouldThrow<UnsupportedOperationException> { transfers.add(TransferDefinition("a", "b", "c")) }
            shouldThrow<UnsupportedOperationException> {
                val iterator = config.accounts.iterator() as MutableIterator<AccountDefinition>
                iterator.next()
                iterator.remove()
            }
            config.accounts.size shouldBe 1
            config.transfers.size shouldBe 1
        }

        @Test
        fun `a builder used after build does not change the built configuration`() {
            var leaked: AccountSet.Builder? = null
            val built = doubleEntry { accounts { leaked = this } }

            leaked!!.define("late")

            built.accounts.findWithoutScope("late").shouldBeNull()
        }
    }

    /** `config_existing_config_snapshot.json`: the oracle's standard configuration (`tools/oracle.rb` `standard_config`). */
    @Test
    fun `a Kotlin config equivalent to the oracle fixture lists the same accounts and transfers`() {
        val expected = OracleSample.result("config_existing_config_snapshot")
        val config = standardConfig()

        config.accounts.map { Triple(it.identifier, it.scoped, it.currency.code) } shouldBe
            expected["accounts"].map { Triple(it["account"].asText(), it["scoped"].asBoolean(), it["currency"].asText()) }
        config.transfers.map { listOf(it.from, it.to, it.code) } shouldBe
            expected["transfers"].map { node -> node["transfer"].map { it.asText() } }
    }

    private fun standardConfig(): DoubleEntryConfiguration {
        val userScope: (Any) -> Any? = { it }
        return doubleEntry {
            accounts {
                define("savings", scopeIdentifier = userScope, positiveOnly = true)
                define("checking", scopeIdentifier = userScope)
                define("btc_wallet", scopeIdentifier = userScope, currency = "BTC")
                define("bank")
                define("cash")
                define("store")
                define("liability", negativeOnly = true)
                define("btc_store", currency = "BTC")
                define("jpy_wallet", currency = "JPY")
                define("jpy_bank", currency = "JPY")
            }
            transfers {
                define(from = "bank", to = "checking", code = "deposit")
                define(from = "bank", to = "savings", code = "deposit")
                define(from = "checking", to = "savings", code = "save")
                define(from = "savings", to = "checking", code = "withdraw")
                define(from = "checking", to = "checking", code = "p2p")
                define(from = "bank", to = "cash", code = "salary")
                define(from = "bank", to = "cash", code = "bonus")
                define(from = "cash", to = "store", code = "purchase")
                define(from = "cash", to = "store", code = "layby")
                define(from = "cash", to = "bank", code = "refund")
                define(from = "bank", to = "btc_store", code = "mismatch")
                define(from = "btc_store", to = "btc_wallet", code = "btc_buy")
                define(from = "jpy_bank", to = "jpy_wallet", code = "jpy_in")
                define(from = "cash", to = "liability", code = "repay")
                define(from = "liability", to = "cash", code = "borrow")
                define(from = "savings", to = "savings", code = "self")
            }
        }
    }

    private companion object {
        val MUTATOR_NAMES = setOf("define", "add", "remove", "clear", "put")
    }
}
