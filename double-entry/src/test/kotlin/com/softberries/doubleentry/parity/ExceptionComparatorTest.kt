package com.softberries.doubleentry.parity

import com.softberries.doubleentry.errors.TransferNotAllowed
import com.softberries.doubleentry.locking.errors.LockNotHeld
import com.softberries.doubleentry.testing.TestUser
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ExceptionComparatorTest {
    private val notAllowed = OracleScenarios.load("transfer_not_allowed_code").exception!!

    @Test
    fun `TransferNotAllowed with the same message matches`() {
        notAllowed.`class` shouldBe "DoubleEntry::TransferNotAllowed"
        notAllowed.message shouldBe "[:bank, :cash, :nope]"

        ExceptionComparator.compare(notAllowed, TransferNotAllowed("[:bank, :cash, :nope]")).matches shouldBe true
    }

    @Test
    fun `a different message or class fails`() {
        ExceptionComparator.compare(notAllowed, TransferNotAllowed("[:bank, :cash, :salary]")).differences.single() shouldBe
            "message: expected <[:bank, :cash, :nope]> but was <[:bank, :cash, :salary]>"
        ExceptionComparator.compare(notAllowed, IllegalStateException("[:bank, :cash, :nope]")).differences.single() shouldContain
            "class: expected <TransferNotAllowed>"
        ExceptionComparator.compare(notAllowed, null).differences.single() shouldContain "nothing was thrown"
    }

    @Test
    fun `the Ruby User address is normalised to TestUser toString`() {
        val expected = OracleScenarios.load("lock_wrong_locks").exception!!
        val user = TestUser(1)

        ExceptionComparator.normalisedMessage(expected, user) shouldBe "No lock held for account: checking, scope $user"
        ExceptionComparator.compare(expected, LockNotHeld("No lock held for account: checking, scope $user"), user).matches shouldBe true
        ExceptionComparator.compare(expected, LockNotHeld("No lock held for account: checking, scope $user")).matches shouldBe false
    }

    @Test
    fun `Ruby class names map to Kotlin simple names`() {
        ExceptionComparator.kotlinClassName("DoubleEntry::Locking::LockMustBeOutermostTransaction") shouldBe
            "LockMustBeOutermostTransaction"
        ExceptionComparator.kotlinClassName("DoubleEntry::AccountIdentifierTooLongError") shouldBe "AccountIdentifierTooLongError"
        ExceptionComparator.kotlinClassName("RuntimeError") shouldBe "RuntimeException"
    }

    @Test
    fun `every DoubleEntry exception of the oracle maps to an existing library class`() {
        val packages = listOf("com.softberries.doubleentry.errors", "com.softberries.doubleentry.locking.errors")
        OracleScenarios
            .all()
            .mapNotNull { it.exception?.`class` }
            .filter { it.startsWith("DoubleEntry::") }
            .toSet()
            .forEach { rubyClass ->
                val name = ExceptionComparator.kotlinClassName(rubyClass)
                withClue(rubyClass) {
                    packages.any { runCatching { Class.forName("$it.$name") }.isSuccess } shouldBe true
                }
            }
    }

    @Test
    fun `a Ruby RuntimeError matches a RuntimeException`() {
        val expected = OracleScenarios.load("account_scope_lambda_raises").exception!!
        val thrown = runCatching { OracleFixtureConfig.userScope(Any()) }.exceptionOrNull()

        ExceptionComparator.compare(expected, thrown).assertMatches()
    }
}
