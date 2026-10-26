package com.softberries.doubleentry.ledger

import com.softberries.doubleentry.errors.AccountWouldBeSentNegative
import com.softberries.doubleentry.errors.AccountWouldBeSentPositiveError
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException

/** Port of `spec/double_entry/line_spec.rb` ("persistance"). [1:1] and [1:2:4] (table name): `@Table` + M-001. */
class LineSpecTest : LedgerIntegrationTest() {
    private fun persistedLine(
        code: String = "test_code",
        account: String = "test",
        accountScope: String = "17",
        partner: String = "test",
        partnerScope: String = "72",
    ): Line {
        val created =
            writer.create(
                NewLine(
                    account = account(account, accountScope),
                    partnerAccount = account(partner, partnerScope),
                    code = code,
                    amount = Money(10_00, CurrencyUnit.USD),
                    balance = Money.zero(CurrencyUnit.USD),
                ),
            )
        // `line_to_persist.save!; line_to_persist.reload`
        return lines.find(created.id)!!
    }

    @Test
    fun `1_2_1_1_1_1 given code = the_code, code is the_code`() {
        persistedLine(code = "the_code").code shouldBe "the_code"
    }

    @Test
    fun `1_2_1_2_1 given code = nil, saving raises a not-null violation`() {
        // A null code cannot be passed through NewLine (D-013), so the DB constraint is exercised directly.
        shouldThrow<DataIntegrityViolationException> {
            jdbcTemplate.update(
                "INSERT INTO double_entry_lines (account, scope, amount, balance, partner_account, partner_scope, " +
                    "created_at, updated_at) VALUES ('test', '17', 1000, 0, 'test', '72', now(), now())",
            )
        }
    }

    @Test
    fun `1_2_1_3_1_1 given account = test, 54, account identifier is test`() {
        persistedLine(accountScope = "54").account.account.identifier shouldBe "test"
    }

    @Test
    fun `1_2_1_3_2_1 given account = test, 54, scope identity is 54`() {
        persistedLine(accountScope = "54").account.scopeIdentity shouldBe "54"
    }

    @Test
    fun `1_2_1_4_1_1 given partner_account = test, 91, partner identifier is test`() {
        persistedLine(partnerScope = "91").partnerAccount.account.identifier shouldBe "test"
    }

    @Test
    fun `1_2_1_4_2_1 given partner_account = test, 91, partner scope identity is 91`() {
        persistedLine(partnerScope = "91").partnerAccount.scopeIdentity shouldBe "91"
    }

    @Test
    fun `1_2_1_5_1_1 currency is the account currency BTC`() {
        val line = persistedLine(account = "btc_test", partner = "btc_test")
        line.currency shouldBe CurrencyUnit.BTC
        line.currency.code shouldBe "BTC"
        line.amount shouldBe Money(10_00, CurrencyUnit.BTC)
        line.balance shouldBe Money(0, CurrencyUnit.BTC)
    }

    @Test
    fun `1_2_1_6_1 given detail = nil, saving does not raise`() {
        persistedLine().detail.shouldBeNull()
    }

    @Test
    fun `1_2_2_1 when balance is sent negative, raises AccountWouldBeSentNegative`() {
        shouldThrow<AccountWouldBeSentNegative> {
            writer.create(
                NewLine(
                    account("a_positive_only_acc"),
                    account("bank"),
                    "x",
                    Money(0, CurrencyUnit.USD),
                    Money(-1, CurrencyUnit.USD),
                ),
            )
        }
    }

    @Test
    fun `1_2_3_1 when balance is sent positive, raises AccountWouldBeSentPositiveError`() {
        shouldThrow<AccountWouldBeSentPositiveError> {
            writer.create(
                NewLine(
                    account("a_negative_only_acc"),
                    account("bank"),
                    "x",
                    Money(0, CurrencyUnit.USD),
                    Money(1, CurrencyUnit.USD),
                ),
            )
        }
    }
}
