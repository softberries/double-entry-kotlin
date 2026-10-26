package com.softberries.doubleentry.validation

import com.softberries.doubleentry.account.AccountFixtures
import com.softberries.doubleentry.ledger.DetailRef
import com.softberries.doubleentry.ledger.Line
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant

/** BR-095 texts and the X-06 record dump format of [LineCheckLog]. */
class LineCheckLogTest {
    private val resolver = AccountFixtures.resolver()
    private val usd = CurrencyUnit.of("USD")

    private fun line(
        id: Long,
        balance: Long,
        scope: String? = null,
        detail: DetailRef? = null,
        metadata: Map<String, Any?>? = null,
    ) = Line(
        id = id,
        accountIdentifier = if (scope == null) "bank" else "savings",
        scopeIdentity = scope,
        code = "deposit",
        amountMinorUnits = -10000,
        balanceMinorUnits = balance,
        partnerId = 2,
        partnerAccountIdentifier = "savings",
        partnerScopeIdentity = "1",
        detail = detail,
        metadata = metadata,
        createdAt = Instant.parse("2026-10-26T11:39:35.072344Z"),
        updatedAt = Instant.parse("2026-10-26T11:39:35.076486Z"),
        resolver = resolver,
    )

    @Test
    fun `line error - Ruby header lines, then the two dumps indented by 2 spaces and an empty line`() {
        val text = LineCheckLog.lineError(line(1, -9999), previousLine = null, previousBalance = Money.zero(usd))

        text shouldBe
            "*********************************\n" +
            "Error on line #1: balance:-99.99 != 0.00 + -100.00\n" +
            "*********************************\n" +
            "  []\n" +
            "  Line(id: 1, account: \"bank\", scope: null, code: \"deposit\", amount: -10000, balance: -9999, partner_id: 2, " +
            "partner_account: \"savings\", partner_scope: \"1\", detail_type: null, detail_id: null, metadata: null, " +
            "created_at: 2026-10-26T11:39:35.072344Z, updated_at: 2026-10-26T11:39:35.076486Z)\n" +
            "\n"
    }

    @Test
    fun `the previous line is rendered as a one-element list`() {
        LineCheckLog.dumpList(line(7, 1)) shouldBe "[${LineCheckLog.dump(line(7, 1))}]"
    }

    @Test
    fun `detail, metadata and quotes are rendered on one line`() {
        val dump =
            LineCheckLog.dump(
                line(3, 0, scope = "1", detail = DetailRef("Order", 42), metadata = mapOf("note" to "a \"b\"\nc", "n" to 1)),
            )

        dump shouldBe
            "Line(id: 3, account: \"savings\", scope: \"1\", code: \"deposit\", amount: -10000, balance: 0, partner_id: 2, " +
            "partner_account: \"savings\", partner_scope: \"1\", detail_type: \"Order\", detail_id: 42, " +
            "metadata: {\"note\":\"a \\\"b\\\"\\nc\",\"n\":1}, " +
            "created_at: 2026-10-26T11:39:35.072344Z, updated_at: 2026-10-26T11:39:35.076486Z)"
        dump shouldNotContain "\n"
    }

    @Test
    fun `account error - byte-identical to Ruby`() {
        val bank = resolver.account("bank")

        LineCheckLog.accountError(bank, Money.ofMinor(-10000, usd), Money.ofMinor(-9999, usd)) shouldBe
            "*********************************\n" +
            "Error on account #{Account account: bank scope:  currency: USD}: -100.00 (cached balance) != -99.99 (running balance)\n" +
            "*********************************\n" +
            "\n"
    }
}
