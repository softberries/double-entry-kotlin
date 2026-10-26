package com.softberries.doubleentry.account

import com.softberries.doubleentry.errors.ScopeIdentifierTooLongError
import com.softberries.doubleentry.errors.UnknownAccount
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.testing.OracleSample
import com.softberries.doubleentry.testing.TestUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** `DoubleEntry::Account.account` and `Account::Set#find` (BR-007, BR-009, BR-010, BR-011); oracle `account_*`. */
class AccountResolverTest {
    private val resolver = AccountFixtures.resolver()

    /** Port of `account_spec.rb` describe `Account::Set` / `#find`. */
    @Nested
    inner class SetFind {
        private val set =
            AccountSet.build {
                define("savings")
                define("checking", scopeIdentifier = { it })
            }
        private val resolver = AccountResolver(set)

        @Test
        fun `finds unscoped accounts (AC 1-4-1-1)`() {
            resolver.find("savings", false).identifier shouldBe "savings"

            shouldThrow<UnknownAccount> { resolver.find("savings", true) }
        }

        @Test
        fun `finds scoped accounts (AC 1-4-1-2)`() {
            resolver.find("checking", true).identifier shouldBe "checking"

            shouldThrow<UnknownAccount> { resolver.find("checking", false) }
        }
    }

    @Nested
    inner class Lookup {
        @Test
        fun `unscoped account (account_unscoped)`() {
            val expected = OracleSample.result("account_unscoped")

            val account = resolver.account("bank")

            account.toString() shouldBe expected["account_instance"].asText()
            account.toString() shouldBe "#{Account account: bank scope:  currency: USD}"
            account.identifier shouldBe expected["identifier"].asText()
            expected["scope_identity"].isNull shouldBe true
            account.scopeIdentity.shouldBeNull()
            account.currency.code shouldBe expected["currency"].asText()
            account.scope.shouldBeNull()
        }

        @Test
        fun `scoped account resolves the identity through the scope identifier (account_scoped)`() {
            val expected = OracleSample.result("account_scoped")
            val user = TestUser(1)

            val account = resolver.account("checking", scope = user)

            account.identifier shouldBe expected["identifier"].asText()
            account.scopeIdentity shouldBe expected["scope_identity"].asText()
            account.currency.code shouldBe expected["currency"].asText()
            account.scope shouldBe user
            // Ruby renders `#<User:0x…>`; the Kotlin scope renders its own toString() in the same place.
            account.toString() shouldBe "#{Account account: checking scope: $user currency: USD}"
            account.toString() shouldContain user.toString()
        }

        @Test
        fun `scopeIdentity bypasses the resolver and leaves the scope part blank (account_scope_identity_option)`() {
            val expected = OracleSample.result("account_scope_identity_option")

            val account = resolver.account("checking", scopeIdentity = "abc")

            account.scopeIdentity shouldBe expected["scope_identity"].asText()
            account.toString() shouldBe expected["account_instance"].asText()
            account.identifier shouldBe expected["identifier"].asText()
        }

        @Test
        fun `scope 0 is present, so the lookup is scoped (account_scope_zero)`() {
            val expected = OracleSample.result("account_scope_zero")
            val resolver = AccountFixtures.resolver { define("ident", scopeIdentifier = { it }) }

            val account = resolver.account("ident", scope = 0)

            account.scopeIdentity shouldBe expected["scope_identity"].asText()
            account.scopeIdentity shouldBe "0"
        }

        @Test
        fun `a resolver returning null gives an empty identity (account_scope_lambda_returns_nil)`() {
            val expected = OracleSample.result("account_scope_lambda_returns_nil")
            val resolver = AccountFixtures.resolver { define("ident", scopeIdentifier = { null }) }

            val account = resolver.account("ident", scope = 5)

            account.scopeIdentity shouldBe expected["scope_identity"].asText()
            account.scopeIdentity shouldBe ""
        }

        @Test
        fun `exceptions from the resolver propagate unchanged (account_scope_lambda_raises, BR-011)`() {
            val expected = OracleSample.exception("account_scope_lambda_raises")

            val error = shouldThrow<IllegalArgumentException> { resolver.account("checking", scope = Any()) }

            error.message shouldBe expected["message"].asText()
        }

        @Test
        fun `a String scopeIdentity equals the identity resolved from the scope (X-08)`() {
            val ruby = OracleSample.result("account_scope_identity_integer")
            ruby["class"].asText() shouldBe "Integer" // Ruby kept 42 as an Integer; Kotlin always uses a String (D-014).

            val fromIdentity = resolver.account("checking", scopeIdentity = 42.toString())
            val fromScope = resolver.account("checking", scope = TestUser(42))

            fromIdentity shouldBe fromScope
            fromIdentity.hashCode() shouldBe fromScope.hashCode()
            fromIdentity.scopeIdentity shouldBe "42"
        }

        @Test
        fun `delegates the definition attributes (account_instance_semantics)`() {
            val expected = OracleSample.result("account_instance_semantics")
            val user = TestUser(1)

            resolver.account("checking", scope = user).scoped shouldBe expected["scoped?"][0].asBoolean()
            resolver.account("bank").scoped shouldBe expected["scoped?"][1].asBoolean()
            resolver.account("savings", scope = user).positiveOnly shouldBe expected["positive_only"].asBoolean()
            resolver.account("liability").negativeOnly shouldBe expected["negative_only"].asBoolean()
            resolver.account("btc_wallet", scope = user).currency shouldBe CurrencyUnit.of(expected["currency"].asText())
            resolver.account("checking", scope = user).scopeIdentifier shouldBe AccountFixtures.userScope
        }
    }

    @Nested
    inner class UnknownAccounts {
        private fun assertUnknown(
            sample: String,
            lookup: () -> AccountInstance,
        ) {
            val expected = OracleSample.exception(sample)

            val error = shouldThrow<UnknownAccount>(lookup)

            error.rubyClassName shouldBe expected["class"].asText()
            error.message shouldBe expected["message"].asText()
        }

        @Test
        fun `unknown identifier (account_unknown)`() {
            assertUnknown("account_unknown") { resolver.account("nope") }
        }

        @Test
        fun `scoped account without a scope (account_scoped_without_scope)`() {
            assertUnknown("account_scoped_without_scope") { resolver.account("checking") }
        }

        @Test
        fun `unscoped account with a scope (account_unscoped_with_scope)`() {
            assertUnknown("account_unscoped_with_scope") { resolver.account("bank", scope = TestUser(1)) }
        }

        @Test
        fun `an empty string scope is an unscoped lookup (account_scope_empty_string)`() {
            assertUnknown("account_scope_empty_string") { resolver.account("checking", scope = "") }
        }

        @Test
        fun `a false scope is an unscoped lookup (account_scope_false)`() {
            assertUnknown("account_scope_false") { resolver.account("checking", scope = false) }
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "   ", "\t\n"])
        fun `blank scope identities are an unscoped lookup like Ruby present?`(blank: String) {
            val error = shouldThrow<UnknownAccount> { resolver.account("checking", scopeIdentity = blank) }

            error.message shouldBe "account: checking scoped?: false"
        }

        @Test
        fun `empty collections are blank, non-empty ones present`() {
            shouldThrow<UnknownAccount> { resolver.account("checking", scope = emptyList<Any>()) }
            shouldThrow<UnknownAccount> { resolver.account("checking", scope = emptyMap<Any, Any>()) }
            shouldThrow<UnknownAccount> { resolver.account("bank", scope = listOf(1)) }.message shouldBe
                "account: bank scoped?: true"
        }
    }

    /** BR-007 via the resolver. */
    @Nested
    inner class ScopeIdentifierMaxLength {
        @Test
        fun `a given identity longer than the maximum is rejected (config_scope_too_long)`() {
            val expected = OracleSample.exception("config_scope_too_long")

            val error =
                shouldThrow<ScopeIdentifierTooLongError> {
                    AccountFixtures.resolver(scopeIdentifierMaxLength = 3).account("checking", scopeIdentity = "1234")
                }

            error.rubyClassName shouldBe expected["class"].asText()
            error.message shouldBe expected["message"].asText()
        }

        @Test
        fun `a resolved identity longer than the maximum is rejected (config_scope_too_long_via_lambda)`() {
            val expected = OracleSample.exception("config_scope_too_long_via_lambda")

            val error =
                shouldThrow<ScopeIdentifierTooLongError> {
                    AccountFixtures.resolver(scopeIdentifierMaxLength = 1).account("checking", scope = TestUser(12))
                }

            error.message shouldBe expected["message"].asText()
        }

        @Test
        fun `an identity at the maximum is accepted`() {
            AccountFixtures.resolver(scopeIdentifierMaxLength = 3).account("checking", scopeIdentity = "123").scopeIdentity shouldBe "123"
        }
    }
}
