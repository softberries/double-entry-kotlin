package com.softberries.doubleentry.transfer

import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.balance.cache.AccountBalanceUpdater
import com.softberries.doubleentry.errors.MismatchedCurrencies
import com.softberries.doubleentry.errors.TransferIsNegative
import com.softberries.doubleentry.errors.TransferNotAllowed
import com.softberries.doubleentry.ledger.DetailRef
import com.softberries.doubleentry.ledger.Line
import com.softberries.doubleentry.ledger.LineEntity
import com.softberries.doubleentry.ledger.LineWriter
import com.softberries.doubleentry.locking.Locking
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.money.MoneyConverter

/**
 * Port of `DoubleEntry::Transfer.transfer` / `#process` / `#create_lines` (`lib/double_entry/transfer.rb:16-22,78-121`):
 * moves money between two accounts by writing a pair of ledger lines and updating both cached balances.
 *
 * Only the json metadata mode exists; the legacy `double_entry_line_metadata` table is never read or written (X-02).
 */
class TransferService internal constructor(
    private val transfers: TransferSet,
    private val locking: Locking,
    private val lineWriter: LineWriter,
    private val balanceUpdater: AccountBalanceUpdater,
    private val resolver: AccountResolver,
) {
    /**
     * Transfers [amount] from [from] to [to] with [code]. [detail] and [metadata] are stored on both lines (BR-027,
     * BR-028; an empty map stays `{}`, null is SQL NULL).
     *
     * Checks run in this order and write nothing when they fail (BR-021, D-013):
     * 1. [TransferIsNegative] if [amount] is negative (zero is allowed, BR-022);
     * 2. [TransferNotAllowed] `[:from, :to, :code]` unless `[from.identifier, to.identifier, code]` is defined (BR-020);
     * 3. [TransferNotAllowed] `from account and to account are identical` for the same identifier and scope identity
     *    (the same identifier with another scope is fine, BR-023);
     * 4. [MismatchedCurrencies] `Mismatched currency (<to> <> <from>)` if the account currencies differ;
     * 5. [MismatchedCurrencies] `Mismatched currency (<amount> <> <from>)` if the amount is in another currency (X-07).
     *
     * Then it locks both accounts ([Locking.lockAccounts]), so it must be the outermost transaction unless it runs inside
     * a `lockAccounts` block that holds both accounts (BR-034); otherwise
     * [com.softberries.doubleentry.locking.errors.LockMustBeOutermostTransaction] or
     * [com.softberries.doubleentry.locking.errors.LockNotHeld]. A positive/negative-only violation
     * ([com.softberries.doubleentry.errors.AccountWouldBeSentNegative],
     * [com.softberries.doubleentry.errors.AccountWouldBeSentPositiveError]) rolls the lock transaction back.
     *
     * @throws ArithmeticException if a new balance would overflow a bigint; nothing is written (BR-032).
     */
    fun transfer(
        amount: Money,
        from: AccountInstance,
        to: AccountInstance,
        code: String,
        detail: DetailRef? = null,
        metadata: Map<String, Any?>? = null,
    ): TransferResult {
        if (amount.isNegative) throw TransferIsNegative()
        transfers.findOrThrow(from.identifier, to.identifier, code)
        if (from.identifier == to.identifier && from.scopeIdentity == to.scopeIdentity) {
            throw TransferNotAllowed("from account and to account are identical")
        }
        if (to.currency != from.currency) throw MismatchedCurrencies("Mismatched currency (${to.currency} <> ${from.currency})")
        // X-07 (D-013): Ruby failed inside the lock with Money::Bank::UnknownRate, after committing balance rows.
        if (amount.currency != from.currency) {
            throw MismatchedCurrencies("Mismatched currency (${amount.currency} <> ${from.currency})")
        }
        return locking.lockAccounts(from, to) { createLines(amount, code, detail, from, to, metadata) }
    }

    /** `create_lines` (`transfer.rb:98-121`): the write protocol of BR-025, statement by statement (D-017). */
    private fun createLines(
        amount: Money,
        code: String,
        detail: DetailRef?,
        from: AccountInstance,
        to: AccountInstance,
        metadata: Map<String, Any?>?,
    ): TransferResult {
        val creditRow = locking.balanceForLockedAccount(from)
        val debitRow = locking.balanceForLockedAccount(to)
        // BR-051: new balances come from the locked cache rows. Both are computed before the first write, so a bigint
        // overflow (ArithmeticException) leaves the rows untouched.
        val creditBalance = creditRow.balance(resolver) - amount
        val debitBalance = debitRow.balance(resolver) + amount
        balanceUpdater.update(creditRow, creditBalance)
        balanceUpdater.update(debitRow, debitBalance)

        val credit = lineWriter.insert(line(from, to, code, -amount, creditBalance, detail, metadata, partnerId = null))
        val debit = lineWriter.insert(line(to, from, code, amount, debitBalance, detail, metadata, partnerId = credit.id))
        credit.partnerId = debit.id
        val updatedCredit = lineWriter.update(credit)
        return TransferResult(Line.of(updatedCredit, resolver), Line.of(debit, resolver))
    }

    private fun line(
        account: AccountInstance,
        partner: AccountInstance,
        code: String,
        amount: Money,
        balance: Money,
        detail: DetailRef?,
        metadata: Map<String, Any?>?,
        partnerId: Long?,
    ) = LineEntity(
        account = account.identifier,
        scope = account.scopeIdentity,
        code = code,
        amount = MoneyConverter.toColumn(amount),
        balance = MoneyConverter.toColumn(balance),
        partnerAccount = partner.identifier,
        partnerScope = partner.scopeIdentity,
        partnerId = partnerId,
        detail = detail,
        metadata = metadata?.let { LinkedHashMap(it) },
    )
}
