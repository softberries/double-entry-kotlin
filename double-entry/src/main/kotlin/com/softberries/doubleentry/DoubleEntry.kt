package com.softberries.doubleentry

import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.balance.BalanceCalculator
import com.softberries.doubleentry.balance.BalanceOptions
import com.softberries.doubleentry.ledger.DetailRef
import com.softberries.doubleentry.locking.LockContext
import com.softberries.doubleentry.locking.Locking
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.transfer.TransferResult
import com.softberries.doubleentry.transfer.TransferService

/**
 * The host API of the library (the `DoubleEntry` module functions, `lib/double_entry.rb:51-165`), registered as a bean
 * by the auto-configuration. Inject it instead of calling static functions:
 * ```
 * @Service
 * class Payments(private val doubleEntry: DoubleEntry) {
 *     fun save(user: User, amount: Money) =
 *         doubleEntry.transfer(amount, from = doubleEntry.account("checking", user), to = doubleEntry.account("savings", user), code = "save")
 * }
 * ```
 *
 * **Transactions (D-007, BR-060):** [transfer] and [lockAccounts] open their own transaction and must be the outermost
 * one. Calling them from a `@Transactional` method (or any active Spring/JDBC transaction) throws
 * [com.softberries.doubleentry.locking.errors.LockMustBeOutermostTransaction]. Do further database work of the same
 * unit inside a [lockAccounts] block instead. This class is deliberately not `@Transactional`.
 */
class DoubleEntry internal constructor(
    private val resolver: AccountResolver,
    private val transfers: TransferService,
    private val calculator: BalanceCalculator,
    private val locking: Locking,
) {
    /**
     * `DoubleEntry.account(identifier, scope:, scope_identity:)` (`double_entry.rb:51-53`); see [AccountResolver.account]
     * for the lookup rules and errors (BR-009..012).
     */
    fun account(
        identifier: String,
        scope: Any? = null,
        scopeIdentity: String? = null,
    ): AccountInstance = resolver.account(identifier, scope, scopeIdentity)

    /**
     * `DoubleEntry.transfer(amount, from:, to:, code:, detail:, metadata:)` (`double_entry.rb:88-90`); see
     * [TransferService.transfer] for the check order and errors. Returns the credit and debit lines, in that order.
     */
    fun transfer(
        amount: Money,
        from: AccountInstance,
        to: AccountInstance,
        code: String,
        detail: DetailRef? = null,
        metadata: Map<String, Any?>? = null,
    ): TransferResult = transfers.transfer(amount, from, to, code, detail, metadata)

    /** `DoubleEntry.balance(account, options)` (`double_entry.rb:142-145`) for an account instance (BR-040..048). */
    fun balance(
        account: AccountInstance,
        options: BalanceOptions = BalanceOptions(),
    ): Money = calculator.calculate(account, options)

    /**
     * `DoubleEntry.balance(:identifier, scope:, scope_identity:, …)`: resolves the account first, exactly like [account]
     * (`double_entry.rb:143`, `account.rb:17-18`), so an unknown or wrongly scoped identifier throws
     * [com.softberries.doubleentry.errors.UnknownAccount] and a throwing scope identifier propagates, before any query
     * runs (BR-049).
     */
    fun balance(
        identifier: String,
        scope: Any? = null,
        scopeIdentity: String? = null,
        options: BalanceOptions = BalanceOptions(),
    ): Money = calculator.calculate(account(identifier, scope, scopeIdentity), options)

    /**
     * `DoubleEntry.lock_accounts(*accounts) { … }` (`double_entry.rb:163-165`); see [Locking.lockAccounts]. Returns the
     * block's value (X-05).
     */
    fun <T> lockAccounts(
        vararg accounts: AccountInstance,
        block: (LockContext) -> T,
    ): T = locking.lockAccounts(accounts.asList(), block)

    fun <T> lockAccounts(
        accounts: Collection<AccountInstance>,
        block: (LockContext) -> T,
    ): T = locking.lockAccounts(accounts, block)
}
