package com.softberries.doubleentry.validation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.ledger.Line
import com.softberries.doubleentry.money.Money

/**
 * The texts [LineCheckService] appends to the `log` column (BR-095, D-011).
 *
 * Header lines are byte-identical to Ruby (`line_check.rb:97-118`). A line error is
 * ```
 * *********************************
 * Error on line #<id>: balance:<balance> != <previous balance> + <amount>
 * *********************************
 *   <previous line dump>
 *   <line dump>
 *
 * ```
 * and an account error is
 * ```
 * *********************************
 * Error on account <account>: <cached> (cached balance) != <running> (running balance)
 * *********************************
 *
 * ```
 * with Money in `Money#to_s` form (`-99.99`, `-0.00010000`) and the account as [AccountInstance.toString].
 *
 * **Record dumps (X-06).** Ruby writes `previous_line.inspect` and `line.inspect`
 * (`[#<DoubleEntry::Line id: 1, account: "bank", …>]`). Kotlin renders the same fields, in the same order, on one line
 * indented by 2 spaces:
 * - the line: `Line(id: 1, account: "bank", scope: null, code: "deposit", amount: -10000, balance: -9999,
 *   partner_id: 2, partner_account: "savings", partner_scope: "1", detail_type: null, detail_id: null, metadata: null,
 *   created_at: 2026-10-26T11:39:35.072344Z, updated_at: 2026-10-26T11:39:35.076486Z)` — strings quoted, absent
 *   values `null`, amounts and balances in minor units, `metadata` as compact JSON, timestamps as ISO-8601 UTC;
 * - the previous line: a list, `[]` when there is none, else `[Line(…)]`.
 */
internal object LineCheckLog {
    private const val RULE = "*********************************"
    private val json = jacksonObjectMapper()

    fun lineError(
        line: Line,
        previousLine: Line?,
        previousBalance: Money,
    ): String =
        "$RULE\n" +
            "Error on line #${line.id}: balance:${line.balance} != $previousBalance + ${line.amount}\n" +
            "$RULE\n" +
            "  ${dumpList(previousLine)}\n" +
            "  ${dump(line)}\n" +
            "\n"

    fun accountError(
        account: AccountInstance,
        cached: Money,
        running: Money,
    ): String =
        "$RULE\n" +
            "Error on account $account: $cached (cached balance) != $running (running balance)\n" +
            "$RULE\n" +
            "\n"

    fun dumpList(line: Line?): String = if (line == null) "[]" else "[${dump(line)}]"

    fun dump(line: Line): String =
        listOf(
            "id" to line.id.toString(),
            "account" to quoted(line.accountIdentifier),
            "scope" to quoted(line.scopeIdentity),
            "code" to quoted(line.code),
            "amount" to line.amountMinorUnits.toString(),
            "balance" to line.balanceMinorUnits.toString(),
            "partner_id" to line.partnerId.toString(),
            "partner_account" to quoted(line.partnerAccountIdentifier),
            "partner_scope" to quoted(line.partnerScopeIdentity),
            "detail_type" to quoted(line.detail?.type),
            "detail_id" to line.detail?.id.toString(),
            "metadata" to (line.metadata?.let(json::writeValueAsString) ?: "null"),
            "created_at" to line.createdAt.toString(),
            "updated_at" to line.updatedAt.toString(),
        ).joinToString(", ", prefix = "Line(", postfix = ")") { (name, value) -> "$name: $value" }

    /** A JSON string literal (escapes quotes and control characters, so a dump stays on one line), or `null`. */
    private fun quoted(value: String?): String = if (value == null) "null" else json.writeValueAsString(value)
}
