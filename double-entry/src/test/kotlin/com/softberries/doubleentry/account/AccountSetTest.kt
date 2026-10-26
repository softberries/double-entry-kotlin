package com.softberries.doubleentry.account

import com.softberries.doubleentry.errors.AccountIdentifierTooLongError
import com.softberries.doubleentry.errors.DuplicateAccount
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.testing.OracleSample
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Port of `spec/double_entry/account_spec.rb` [1:1:*] and [1:3:*]; BR-001, BR-002, BR-005, BR-013. */
class AccountSetTest {
    private val identityScope: ScopeIdentifier = { it }

    /** `account_spec.rb` describe '::new', given a account_identifier_max_length of 31. */
    @Nested
    inner class IdentifierMaxLength {
        @Test
        fun `given an identifier 31 characters in length it does not raise (AC 1-1-1-1-1)`() {
            shouldNotThrowAny {
                AccountSet.build(accountIdentifierMaxLength = 31) { define("xxxxxxxx 31 characters xxxxxxxx") }
            }
        }

        @Test
        fun `given an identifier 32 characters in length it raises AccountIdentifierTooLongError (AC 1-1-1-2-1)`() {
            val identifier = "xxxxxxxx 32 characters xxxxxxxxx"

            val error =
                shouldThrow<AccountIdentifierTooLongError> {
                    AccountSet.build(accountIdentifierMaxLength = 31) { define(identifier) }
                }

            error.message shouldContain "'$identifier'"
        }

        @Test
        fun `max length 5 accepts abcde (config_account_identifier_max_length_ok)`() {
            val expected = OracleSample.result("config_account_identifier_max_length_ok")

            val account = AccountSet.build(accountIdentifierMaxLength = 5) { define("abcde") }.all.single()

            account.identifier shouldBe expected["account"].asText()
            account.scoped shouldBe expected["scoped"].asBoolean()
            account.currency.code shouldBe expected["currency"].asText()
        }

        @Test
        fun `max length 5 rejects abcdef with the Ruby message (config_account_identifier_too_long)`() {
            val expected = OracleSample.exception("config_account_identifier_too_long")

            val error = shouldThrow<AccountIdentifierTooLongError> { AccountSet.build(accountIdentifierMaxLength = 5) { define("abcdef") } }

            error.rubyClassName shouldBe expected["class"].asText()
            error.message shouldBe expected["message"].asText()
            error.message shouldBe "account identifier 'abcdef' is too long. Please limit it to 5 characters."
        }

        @Test
        fun `length counts characters like Ruby, not UTF-16 units`() {
            shouldNotThrowAny { AccountSet.build(accountIdentifierMaxLength = 2) { define("💰x") } }
        }
    }

    /** `account_spec.rb` describe 'currency'. */
    @Nested
    inner class Currency {
        @Test
        fun `defaults to USD currency (AC 1-3-1)`() {
            val account = AccountSet.build { define("savings", scopeIdentifier = identityScope) }.all.single()

            account.currency shouldBe CurrencyUnit.USD
        }

        @Test
        fun `allows the currency to be set (AC 1-3-2)`() {
            val account = AccountSet.build { define("savings", scopeIdentifier = identityScope, currency = "AUD") }.all.single()

            account.currency shouldBe CurrencyUnit.of("AUD")
        }

        @Test
        fun `currency codes are case-insensitive`() {
            val set =
                AccountSet.build {
                    define("upper", currency = "BTC")
                    define("lower", currency = "btc")
                }

            set.currencyOf("upper") shouldBe CurrencyUnit.BTC
            set.currencyOf("lower") shouldBe CurrencyUnit.BTC
        }

        @Test
        fun `the default currency is resolved when the account is defined (BR-001)`() {
            val account = AccountSet.build(defaultCurrency = CurrencyUnit.EUR) { define("savings") }.all.single()

            account.currency shouldBe CurrencyUnit.EUR
        }
    }

    /** BR-002, spec DE [1:1:1]. */
    @Nested
    inner class Duplicates {
        @Test
        fun `defining savings twice raises DuplicateAccount (config_duplicate_account)`() {
            val expected = OracleSample.exception("config_duplicate_account")

            val error =
                shouldThrow<DuplicateAccount> {
                    AccountSet.build {
                        define("savings")
                        define("savings")
                    }
                }

            error.rubyClassName shouldBe expected["class"].asText()
            error.message shouldBe expected["message"].asText()
        }

        @Test
        fun `scoped and unscoped definitions share one namespace (config_duplicate_account_scoped_vs_unscoped)`() {
            val expected = OracleSample.exception("config_duplicate_account_scoped_vs_unscoped")

            val error =
                shouldThrow<DuplicateAccount> {
                    AccountSet.build {
                        define("savings", scopeIdentifier = identityScope)
                        define("savings")
                    }
                }

            error.message shouldBe expected["message"].asText()
        }
    }

    /** BR-013, untested by the Ruby specs (`docs/80-tests.md` §3.1). Oracle `config_defaults.json`. */
    @Test
    fun `currencyOf returns the configured currency or null`() {
        val expected = OracleSample.result("config_defaults")
        val set =
            AccountSet.build {
                define("btc_store", currency = "BTC")
                define("savings")
            }

        set.currencyOf("btc_store")?.code shouldBe expected["Account.currency(:btc_store)"].asText()
        expected["Account.currency(:unknown)"].isNull shouldBe true
        set.currencyOf("unknown").shouldBeNull()
        set.currencyOf("savings")?.code shouldBe expected["default_currency_of_account_without_currency"].asText()
    }

    @Test
    fun `definitions keep their attributes and order`() {
        val set =
            AccountSet.build {
                define("savings", scopeIdentifier = identityScope, positiveOnly = true)
                define("liability", negativeOnly = true)
            }

        set.map { it.identifier } shouldBe listOf("savings", "liability")
        set.findWithoutScope("savings")!!.let {
            it.scoped shouldBe true
            it.positiveOnly shouldBe true
            it.negativeOnly shouldBe false
        }
        set.findWithoutScope("liability")!!.let {
            it.scoped shouldBe false
            it.negativeOnly shouldBe true
        }
    }
}
