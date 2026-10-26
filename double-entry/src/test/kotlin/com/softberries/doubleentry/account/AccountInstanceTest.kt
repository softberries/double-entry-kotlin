package com.softberries.doubleentry.account

import com.softberries.doubleentry.errors.ScopeIdentifierTooLongError
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.testing.OracleSample
import com.softberries.doubleentry.testing.TestUser
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Port of `spec/double_entry/account_spec.rb` describe `Account::Instance` [1:2:*] and 'currency'; BR-007, BR-012. */
class AccountInstanceTest {
    private val identityScope: ScopeIdentifier = { it }
    private val savings = AccountSet.build { define("savings", scopeIdentifier = identityScope) }.single()

    private fun instance(
        account: AccountDefinition,
        scope: Any? = null,
        scopeIdentity: String? = null,
        scopeIdentifierMaxLength: Int? = null,
    ) = AccountInstance(account, scope, scopeIdentity, scopeIdentifierMaxLength)

    @Test
    fun `is sortable (AC 1-2-1)`() {
        val a = instance(savings, scope = "123")
        val b = instance(savings, scope = "456")

        listOf(b, a).sorted() shouldBe listOf(a, b)
    }

    @Test
    fun `is hashable (AC 1-2-2)`() {
        val a1 = instance(savings, scope = "123")
        val a2 = instance(savings, scope = "123")
        val b = instance(savings, scope = "456")

        a1.hashCode() shouldBe a2.hashCode()
        a1.hashCode() shouldNotBe b.hashCode()
        setOf(a1, a2, b).size shouldBe 2
    }

    /** `account_spec.rb` describe '::new', given a scope_identifier_max_length of 23. */
    @Nested
    inner class ScopeIdentifierMaxLength {
        private val x = AccountSet.build { define("x", scopeIdentifier = identityScope) }.single()

        @Test
        fun `given a scope identifier 23 characters in length it does not raise (AC 1-2-3-1-1-1)`() {
            shouldNotThrowAny { instance(x, scope = "xxxx 23 characters xxxx", scopeIdentifierMaxLength = 23) }
        }

        @Test
        fun `given a scope identifier 24 characters in length it raises (AC 1-2-3-1-2-1)`() {
            val scope = "xxxx 24 characters xxxxx"

            val error = shouldThrow<ScopeIdentifierTooLongError> { instance(x, scope = scope, scopeIdentifierMaxLength = 23) }

            error.message shouldContain "'$scope'"
        }

        @Test
        fun `length counts characters like Ruby, not UTF-16 units`() {
            shouldNotThrowAny { instance(x, scope = "💰x", scopeIdentifierMaxLength = 2) }
        }

        @Test
        fun `unscoped instances have no identity to check`() {
            val bank = AccountSet.build { define("bank") }.single()

            shouldNotThrowAny { instance(bank, scopeIdentifierMaxLength = 0) }
        }
    }

    /** `account_spec.rb` describe 'currency' (AC [1:3:1], [1:3:2]) on the instance. */
    @Nested
    inner class Currency {
        @Test
        fun `defaults to USD currency`() {
            instance(savings).currency shouldBe CurrencyUnit.USD
        }

        @Test
        fun `allows the currency to be set`() {
            val aud = AccountSet.build { define("savings", scopeIdentifier = identityScope, currency = "AUD") }.single()

            instance(aud).currency shouldBe CurrencyUnit.of("AUD")
        }

        @Test
        fun `a scoped instance without scope object has an empty identity, like Ruby's identity lambda on nil`() {
            instance(savings).scopeIdentity shouldBe ""
        }
    }

    /** Oracle `account_instance_semantics.json` (BR-012). */
    @Nested
    inner class Semantics {
        private val resolver = AccountFixtures.resolver()
        private val expected = OracleSample.result("account_instance_semantics")
        private val u1 = TestUser(1)
        private val u2 = TestUser(2)

        @Test
        fun `equal instances are equal with equal hash codes, a different scope is not`() {
            val a1 = resolver.account("checking", scope = u1)
            val a1b = resolver.account("checking", scope = u1)
            val a2 = resolver.account("checking", scope = u2)

            (a1 == a1b) shouldBe expected["a1==a1b"].asBoolean()
            (a1.hashCode() == a1b.hashCode()) shouldBe expected["a1.hash==a1b.hash"].asBoolean()
            (a1 == a2) shouldBe expected["a1==a2"].asBoolean()
        }

        @Test
        fun `sorts by scope identity, then identifier`() {
            val a1 = resolver.account("checking", scope = u1)
            val a2 = resolver.account("checking", scope = u2)
            val b = resolver.account("bank")
            val s1 = resolver.account("savings", scope = u1)

            val sorted = listOf(s1, a2, b, a1).sorted()

            sorted shouldBe listOf(b, a1, s1, a2)
            sorted.map { it.toString().withoutScope() } shouldBe expected["sorted"].map { it.asText().withoutScope() }
            listOf(a1, b, s1, a2).sorted() shouldBe listOf(b, a1, s1, a2)
        }

        @Test
        fun `toString and its scope part`() {
            val a1 = resolver.account("checking", scope = u1)
            val b = resolver.account("bank")

            a1.toString().withoutScope() shouldBe expected["to_s"].asText().withoutScope()
            a1.toString() shouldBe "#{Account account: checking scope: TestUser(id=1) currency: USD}"
            b.toString() shouldBe expected["inspect"].asText()
        }

        @Test
        fun `an instance rebuilt from a scope identity equals the one built from the scope`() {
            val fromScope = resolver.account("checking", scope = u1)
            val fromRow = resolver.account("checking", scopeIdentity = "1")

            fromRow shouldBe fromScope
            fromRow.toString() shouldBe "#{Account account: checking scope:  currency: USD}"
        }

        /** Ruby objects render as `#<User:0x…>`; compare the rest of the text. */
        private fun String.withoutScope(): String = replace(Regex("scope: \\S* currency"), "scope: <scope> currency")
    }

    @Test
    fun `different identifiers with the same scope are not equal`() {
        val resolver = AccountFixtures.resolver()

        resolver.account("checking", scope = TestUser(1)) shouldNotBe resolver.account("savings", scope = TestUser(1))
    }

    @Test
    fun `orders by code point like Ruby byte-wise string comparison`() {
        // U+FF5E (BMP, high) sorts before U+1F4B0 (supplementary) in UTF-8 / code point order; UTF-16 order differs.
        val high = instance(savings, scopeIdentity = "～")
        val emoji = instance(savings, scopeIdentity = "💰")

        listOf(emoji, high).sorted() shouldBe listOf(high, emoji)
    }
}
