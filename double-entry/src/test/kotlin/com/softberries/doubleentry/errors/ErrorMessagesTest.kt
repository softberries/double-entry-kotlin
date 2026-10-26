package com.softberries.doubleentry.errors

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.softberries.doubleentry.locking.RestartTransactionSignal
import com.softberries.doubleentry.locking.errors.LockDisaster
import com.softberries.doubleentry.locking.errors.LockMustBeOutermostTransaction
import com.softberries.doubleentry.locking.errors.LockNotHeld
import com.softberries.doubleentry.locking.errors.LockWaitTimeout
import com.softberries.doubleentry.locking.errors.LockingException
import com.softberries.doubleentry.testing.MigrationSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Error classes and default messages against `docs/30-http-api.md` §3 and `inventory/errors.json`. No Ruby spec
 * targets `errors.rb`; parametrised messages are tested by the units that build them (M-005, M-006, M-011, M-012).
 */
class ErrorMessagesTest {
    private val mapper = jacksonObjectMapper()

    @ParameterizedTest(name = "{0}")
    @MethodSource("allErrors")
    fun `default message is the Ruby class name`(
        rubyName: String,
        create: () -> RuntimeException,
    ) {
        create().message shouldBe rubyName
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allErrors")
    fun `an explicit message is kept`(
        rubyName: String,
        create: () -> RuntimeException,
    ) {
        val withMessage = create().javaClass.getConstructor(String::class.java, Throwable::class.java)
        val cause = IllegalStateException("cause")

        val exception = withMessage.newInstance("custom", cause)

        exception.message shouldBe "custom"
        exception.cause shouldBe cause
    }

    @Test
    fun `every entry of inventory errors json has a Kotlin class with that Ruby name and superclass`() {
        val inventory = mapper.readTree(MigrationSpec.read("inventory/errors.json"))
        val kotlinByRubyName = CASES.associate { (rubyName, create) -> rubyName to create() }
        val restart = RestartTransactionSignal()

        inventory.map { it["name"].asText() } shouldContainExactlyInAnyOrder
            kotlinByRubyName.keys + RestartTransactionSignal.RUBY_CLASS_NAME
        inventory.forEach { entry ->
            val name = entry["name"].asText()
            val exception = if (name == RestartTransactionSignal.RUBY_CLASS_NAME) restart else kotlinByRubyName.getValue(name)
            when (entry["superclass"].asText()) {
                "DoubleEntry::DoubleEntryError" -> exception.shouldBeInstanceOf<DoubleEntryException>()
                "RuntimeError" ->
                    if (exception.javaClass == DoubleEntryException::class.java) {
                        exception.javaClass.superclass shouldBe RuntimeException::class.java
                    } else {
                        exception.shouldNotBeInstanceOf<DoubleEntryException>()
                    }
                else -> error("unexpected superclass in $entry")
            }
        }
    }

    @Test
    fun `Kotlin simple names are the Ruby class names`() {
        CASES.forEach { (rubyName, create) ->
            val exception = create()
            if (exception.javaClass != DoubleEntryException::class.java) {
                rubyName shouldBe rubyName.substringBeforeLast("::") + "::" + exception.javaClass.simpleName
            }
        }
    }

    @Test
    fun `rubyClassName is exposed for parity comparison`() {
        TransferIsNegative().rubyClassName shouldBe "DoubleEntry::TransferIsNegative"
        DoubleEntryException("x").rubyClassName shouldBe "DoubleEntry::DoubleEntryError"
        LockNotHeld("No lock held").rubyClassName shouldBe "DoubleEntry::Locking::LockNotHeld"
    }

    @Test
    fun `DoubleEntryException subclasses are catchable as DoubleEntryException`() {
        doubleEntryErrors().forEach { create ->
            val caught = shouldThrow<DoubleEntryException> { throw create() }
            caught.shouldBeInstanceOf<RuntimeException>()
        }
    }

    @Test
    fun `locking errors are RuntimeExceptions but not DoubleEntryExceptions`() {
        lockingErrors().forEach { create ->
            val thrown = create()
            thrown.shouldBeInstanceOf<RuntimeException>()
            thrown.shouldNotBeInstanceOf<DoubleEntryException>()
            val caught =
                try {
                    try {
                        throw thrown
                    } catch (e: DoubleEntryException) {
                        error("locking error caught as DoubleEntryException: $e")
                    }
                } catch (e: LockingException) {
                    e
                }
            caught shouldBe thrown
        }
    }

    @Test
    fun `RestartTransactionSignal carries the Ruby class name and no stack trace`() {
        val signal = RestartTransactionSignal()

        signal.message shouldBe "ActiveRecord::RestartTransaction"
        signal.stackTrace.size shouldBe 0
        signal.shouldNotBeInstanceOf<DoubleEntryException>()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oracleSamples")
    fun `oracle exception class and message match for message-less errors`(
        sample: String,
        create: () -> RuntimeException,
    ) {
        val oracle = mapper.readTree(MigrationSpec.read("oracle/api/json/$sample.json"))["exception"]
        val exception = create()

        rubyClassName(exception) shouldBe oracle["class"].asText()
        exception.message shouldBe oracle["message"].asText()
        val isDoubleEntryError = oracle["ancestors"].any { it.asText() == "DoubleEntry::DoubleEntryError" }
        (exception is DoubleEntryException) shouldBe isDoubleEntryError
    }

    private fun rubyClassName(exception: RuntimeException): String =
        when (exception) {
            is DoubleEntryException -> exception.rubyClassName
            is LockingException -> exception.rubyClassName
            else -> error("not a library error: $exception")
        }

    companion object {
        private fun doubleEntryErrors(): List<() -> DoubleEntryException> =
            listOf(
                ::UnknownAccount,
                ::AccountIdentifierTooLongError,
                ::ScopeIdentifierTooLongError,
                ::TransferNotAllowed,
                ::TransferIsNegative,
                ::TransferCodeTooLongError,
                ::DuplicateAccount,
                ::DuplicateTransfer,
                ::AccountWouldBeSentNegative,
                ::AccountWouldBeSentPositiveError,
                ::MismatchedCurrencies,
                ::MissingAccountError,
            ).map { constructor -> { constructor(null, null) } }

        private fun lockingErrors(): List<() -> LockingException> =
            listOf(::LockMustBeOutermostTransaction, ::LockNotHeld, ::LockDisaster, ::LockWaitTimeout)
                .map { constructor -> { constructor(null, null) } }

        private val CASES: List<Pair<String, () -> RuntimeException>> =
            listOf(
                "DoubleEntry::DoubleEntryError" to { DoubleEntryException() },
                "DoubleEntry::UnknownAccount" to { UnknownAccount() },
                "DoubleEntry::AccountIdentifierTooLongError" to { AccountIdentifierTooLongError() },
                "DoubleEntry::ScopeIdentifierTooLongError" to { ScopeIdentifierTooLongError() },
                "DoubleEntry::TransferNotAllowed" to { TransferNotAllowed() },
                "DoubleEntry::TransferIsNegative" to { TransferIsNegative() },
                "DoubleEntry::TransferCodeTooLongError" to { TransferCodeTooLongError() },
                "DoubleEntry::DuplicateAccount" to { DuplicateAccount() },
                "DoubleEntry::DuplicateTransfer" to { DuplicateTransfer() },
                "DoubleEntry::AccountWouldBeSentNegative" to { AccountWouldBeSentNegative() },
                "DoubleEntry::AccountWouldBeSentPositiveError" to { AccountWouldBeSentPositiveError() },
                "DoubleEntry::MismatchedCurrencies" to { MismatchedCurrencies() },
                "DoubleEntry::MissingAccountError" to { MissingAccountError() },
                "DoubleEntry::Locking::LockMustBeOutermostTransaction" to { LockMustBeOutermostTransaction() },
                "DoubleEntry::Locking::LockNotHeld" to { LockNotHeld() },
                "DoubleEntry::Locking::LockDisaster" to { LockDisaster() },
                "DoubleEntry::Locking::LockWaitTimeout" to { LockWaitTimeout() },
            )

        @JvmStatic
        fun allErrors(): List<Arguments> = CASES.map { (rubyName, create) -> Arguments.of(rubyName, create) }

        @JvmStatic
        fun oracleSamples(): List<Arguments> =
            listOf(
                Arguments.of("config_duplicate_account", { DuplicateAccount() }),
                Arguments.of("config_duplicate_transfer", { DuplicateTransfer() }),
                Arguments.of("lock_inside_transaction", { LockMustBeOutermostTransaction() }),
            )
    }
}
