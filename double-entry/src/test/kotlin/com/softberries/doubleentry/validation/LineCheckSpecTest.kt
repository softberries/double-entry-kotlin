package com.softberries.doubleentry.validation

import com.softberries.doubleentry.config.DoubleEntryTables
import com.softberries.doubleentry.testing.MutableClock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import jakarta.persistence.Table
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Port of `spec/double_entry/validation/line_check_spec.rb` (all 20 examples; `subject` is
 * `LineCheck.perform!(fixer: AccountFixer.new)`, the users come from `spec/support/factories.rb`).
 *
 * Deviation in [1:2:1:3:2:1]: the Ruby spec expects `currency: }` for `savings`, because `spec/support/accounts.rb`
 * defines the accounts before `spec/support/money.rb` sets `Money.default_currency`, so the definition captured no
 * currency (`account.rb:148`, inferred). Kotlin always resolves the default currency at configuration time, so the
 * message reads `currency: USD}`, as in the oracle (`linecheck_account_balance_error_with_fixer.json`).
 */
class LineCheckSpecTest : ValidationIntegrationTestBase() {
    private fun subject() = lineCheck.perform(fixer)

    /** `create(:user, savings_balance: Money.new(100_00))`: test → savings, code bonus. */
    private fun userWith100Dollars() = transfer(100_00, acct("test", 1), acct("savings", 1), "bonus")

    /** `create(:user, bitcoin_balance: Money.new(100_00, 'BTC'))`: btc_test → btc_savings. */
    private fun userWithBitcoin() = transfer(100_00, acct("btc_test", 1), acct("btc_savings", 1), "btc_test_transfer")

    private fun corruptFirstLine() =
        sql("UPDATE double_entry_lines SET balance = balance + 1 WHERE id = (SELECT MIN(id) FROM double_entry_lines)")

    private fun corruptFirstAccountBalance() =
        sql("UPDATE double_entry_account_balances SET balance = balance + 1 WHERE id = (SELECT MIN(id) FROM double_entry_account_balances)")

    @Nested
    inner class LastLineIdChecked {
        /** [1:1:1:1] */
        @Test
        fun `should find the newest LineCheck created (by creation_date)`() {
            val now = MutableClock.ORACLE_T0
            insertCheck(100, now.minus(Duration.ofMinutes(3)))
            insertCheck(300, now.minus(Duration.ofMinutes(1)))
            insertCheck(200, now.minus(Duration.ofMinutes(2)))

            lineCheck.lastLineIdChecked() shouldBe 300L
        }

        private fun insertCheck(
            lastLineId: Long,
            at: java.time.Instant,
        ) {
            val stamp = java.time.LocalDateTime.ofInstant(at, java.time.ZoneOffset.UTC)
            jdbcTemplate.update(
                "INSERT INTO double_entry_line_checks (last_line_id, errors_found, log, created_at, updated_at) VALUES (?, false, '', ?, ?)",
                lastLineId,
                stamp,
                stamp,
            )
        }
    }

    @Nested
    inner class GivenAUserWith100DollarsAndAllIsConsistent {
        @Test
        fun `And all lines have been checked - is expected to be nil (1_2_1_1_1_1)`() {
            userWith100Dollars()
            lineCheck.perform(fixer)

            subject() shouldBe null
        }

        @Test
        fun `And all lines have been checked - should not persist a new LineCheck (1_2_1_1_1_2)`() {
            userWith100Dollars()
            lineCheck.perform(fixer)
            val before = lineCheckCount()

            subject()

            lineCheckCount() shouldBe before
        }

        @Test
        fun `is expected to be an instance of LineCheck (1_2_1_1_2)`() {
            userWith100Dollars()

            subject() shouldNotBe null
        }

        @Test
        fun `errors_found is expected to eq false (1_2_1_1_3_1)`() {
            userWith100Dollars()

            subject()!!.errorsFound shouldBe false
        }

        @Test
        fun `should persist the LineCheck (1_2_1_1_4)`() {
            userWith100Dollars()

            val result = subject()!!

            lineChecks.findAllByOrderByIdAsc().last().let { LineCheck.of(it) } shouldBe result
        }
    }

    @Nested
    inner class GivenAUserWith100DollarsAndAConsistencyErrorInLines {
        @Test
        fun `errors_found is expected to equal true (1_2_1_2_1_1)`() {
            userWith100Dollars()
            corruptFirstLine()

            subject()!!.errorsFound shouldBe true
        }

        @Test
        fun `log is expected to match Error on line (1_2_1_2_2_1)`() {
            userWith100Dollars()
            corruptFirstLine()

            subject()!!.log!! shouldContain Regex("Error on line")
        }

        @Test
        fun `should correct the running balance (1_2_1_2_3)`() {
            userWith100Dollars()
            corruptFirstLine()
            val before = firstLineBalance()

            subject()

            (firstLineBalance() - before) shouldBe -1L
        }

        @Test
        fun `And given we ask not to correct the errors - should not correct the running balance (1_2_1_2_4_1)`() {
            userWith100Dollars()
            corruptFirstLine()
            val before = firstLineBalance()

            lineCheck.perform(fixer = null)

            firstLineBalance() shouldBe before
        }
    }

    @Nested
    inner class GivenAUserWith100DollarsAndAConsistencyErrorInAccountBalance {
        @Test
        fun `errors_found is expected to equal true (1_2_1_3_1_1)`() {
            userWith100Dollars()
            corruptFirstAccountBalance()

            subject()!!.errorsFound shouldBe true
        }

        @Test
        fun `log is expected to include the account error (1_2_1_3_2_1)`() {
            userWith100Dollars()
            corruptFirstAccountBalance()

            subject()!!.log!! shouldContain
                "Error on account #{Account account: savings scope:  currency: USD}: 100.01 (cached balance) != 100.00 (running balance)\n"
        }

        @Test
        fun `should correct the account balance (1_2_1_3_3)`() {
            userWith100Dollars()
            corruptFirstAccountBalance()
            val before = firstAccountBalance()

            subject()

            (firstAccountBalance() - before) shouldBe -1L
        }

        @Test
        fun `And given we ask not to correct the errors - should not correct the account balance (1_2_1_3_4_1)`() {
            userWith100Dollars()
            corruptFirstAccountBalance()
            val before = firstAccountBalance()

            lineCheck.perform(fixer = null)

            firstAccountBalance() shouldBe before
        }
    }

    @Nested
    inner class GivenAUserWithANonDefaultCurrencyBalance {
        @Test
        fun `errors_found is expected to eq false (1_2_2_1_1)`() {
            userWithBitcoin()

            subject()!!.errorsFound shouldBe false
        }

        @Test
        fun `And there is a consistency error in lines - errors_found is expected to eq true (1_2_2_2_1_1)`() {
            userWithBitcoin()
            corruptFirstLine()

            subject()!!.errorsFound shouldBe true
        }

        @Test
        fun `And there is a consistency error in lines - log is expected to include the BTC account error (1_2_2_2_2_1)`() {
            userWithBitcoin()
            corruptFirstLine()

            subject()!!.log!! shouldContain
                "Error on account #{Account account: btc_test scope:  currency: BTC}: -0.00010000 (cached balance) != -0.00009999 (running balance)\n"
        }

        @Test
        fun `And there is a consistency error in lines - should correct the running balance (1_2_2_2_3)`() {
            userWithBitcoin()
            corruptFirstLine()
            val before = firstLineBalance()

            subject()

            // Money.new(-1, 'BTC'): one satoshi.
            (firstLineBalance() - before) shouldBe -1L
            btc(firstLineBalance()).toString() shouldBe "-0.00010000"
        }

        @Test
        fun `And given we ask not to correct the errors - should not correct the running balance (1_2_2_2_4_1)`() {
            userWithBitcoin()
            corruptFirstLine()
            val before = firstLineBalance()

            lineCheck.perform(fixer = null)

            firstLineBalance() shouldBe before
        }
    }

    /** [1:2:3] (also covered by the M-001 schema test). */
    @Test
    fun `has a table name prefixed with double_entry_`() {
        LineCheckEntity::class.java.getAnnotation(Table::class.java).name shouldBe "double_entry_line_checks"
        DoubleEntryTables.LINE_CHECKS shouldBe "double_entry_line_checks"
    }
}
