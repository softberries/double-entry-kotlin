package com.softberries.doubleentry.transfer

import com.softberries.doubleentry.errors.DuplicateTransfer
import com.softberries.doubleentry.errors.TransferCodeTooLongError
import com.softberries.doubleentry.errors.TransferNotAllowed
import com.softberries.doubleentry.testing.OracleSample
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Port of `spec/double_entry/transfer_spec.rb` [1:1:*] and [1:3:*]; BR-003, BR-004, BR-006, BR-020 (message). */
class TransferSetTest {
    /** `transfer_spec.rb` describe '::new', given a code_max_length of 47. */
    @Nested
    inner class CodeMaxLength {
        @Test
        fun `given a code 47 characters in length it does not raise (TR 1-1-1-1-1)`() {
            shouldNotThrowAny {
                TransferSet.build(codeMaxLength = 47) { define("a", "b", "xxxxxxxxxxxxxxxx 47 characters xxxxxxxxxxxxxxxx") }
            }
        }

        @Test
        fun `given a code 48 characters in length it raises TransferCodeTooLongError (TR 1-1-1-2-1)`() {
            val code = "xxxxxxxxxxxxxxxx 48 characters xxxxxxxxxxxxxxxxx"

            val error = shouldThrow<TransferCodeTooLongError> { TransferSet.build(codeMaxLength = 47) { define("a", "b", code) } }

            error.message shouldContain "'$code'"
        }

        @Test
        fun `max length 4 rejects abcde with the Ruby message (config_code_too_long)`() {
            val expected = OracleSample.exception("config_code_too_long")

            val error = shouldThrow<TransferCodeTooLongError> { TransferSet.build(codeMaxLength = 4) { define("a", "b", "abcde") } }

            error.rubyClassName shouldBe expected["class"].asText()
            error.message shouldBe expected["message"].asText()
            error.message shouldBe "transfer code 'abcde' is too long. Please limit it to 4 characters."
        }
    }

    /** `transfer_spec.rb` describe Transfer::Set. */
    @Nested
    inner class Lookup {
        private val set =
            TransferSet.build {
                define(from = "from", to = "to", code = "transfer_code")
                define(from = "from", to = "to", code = "another_transfer_code")
            }

        @Test
        fun `find finds the transfers (TR 1-3-1-1)`() {
            set.find("from", "to", "transfer_code") shouldBe TransferDefinition("from", "to", "transfer_code")
            set.find("from", "to", "another_transfer_code").shouldNotBeNull().let {
                it.code shouldBe "another_transfer_code"
                it.from shouldBe "from"
                it.to shouldBe "to"
            }
        }

        @Test
        fun `find returns nothing when searching for undefined transfers (TR 1-3-1-2)`() {
            set.find("to", "from", "transfer_code").shouldBeNull()
        }

        @Test
        fun `find! also finds the transfers (TR 1-3-2-1)`() {
            set.findOrThrow("from", "to", "transfer_code") shouldBe TransferDefinition("from", "to", "transfer_code")
            set.findOrThrow("from", "to", "another_transfer_code").code shouldBe "another_transfer_code"
        }

        @Test
        fun `find! raises TransferNotAllowed with the Ruby inspect message (TR 1-3-2-2)`() {
            val error = shouldThrow<TransferNotAllowed> { set.findOrThrow("to", "from", "transfer_code") }

            error.message shouldBe "[:to, :from, :transfer_code]"
        }
    }

    @Test
    fun `defining bank cash salary twice raises DuplicateTransfer (config_duplicate_transfer)`() {
        val expected = OracleSample.exception("config_duplicate_transfer")

        val error =
            shouldThrow<DuplicateTransfer> {
                TransferSet.build {
                    define("bank", "cash", "salary")
                    define("bank", "cash", "salary")
                }
            }

        error.rubyClassName shouldBe expected["class"].asText()
        error.message shouldBe expected["message"].asText()
    }

    @Test
    fun `the same accounts with another code are a different transfer`() {
        val set =
            TransferSet.build {
                define("bank", "cash", "salary")
                define("bank", "cash", "bonus")
                define("cash", "bank", "salary")
            }

        set.size shouldBe 3
    }

    /** BR-004, untested by the Ruby specs (`docs/80-tests.md` §3.1). */
    @Test
    fun `transfers may name undefined accounts (config_transfer_unknown_accounts_allowed)`() {
        val expected = OracleSample.result("config_transfer_unknown_accounts_allowed")["transfer"].map { it.asText() }

        val transfer = TransferSet.build { define(from = "nope", to = "nada", code = "x") }.single()

        listOf(transfer.from, transfer.to, transfer.code) shouldBe expected
    }
}
