package com.softberries.doubleentry.locking

import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.balance.BalanceCalculator
import com.softberries.doubleentry.balance.cache.AccountBalanceEntity
import com.softberries.doubleentry.balance.cache.AccountBalanceQueries
import com.softberries.doubleentry.locking.errors.LockDisaster
import com.softberries.doubleentry.locking.errors.LockMustBeOutermostTransaction
import com.softberries.doubleentry.locking.errors.LockNotHeld
import org.springframework.transaction.TransactionStatus

/**
 * Handle passed to a `lockAccounts` block. Nested blocks receive the handle of the outermost lock.
 */
class LockContext internal constructor(
    private val status: TransactionStatus,
) {
    /**
     * The equivalent of `raise ActiveRecord::Rollback` (BR-065, U-009): marks the lock transaction rollback-only. The
     * block still runs to its end; then the transaction is rolled back silently and `lockAccounts` returns the block's
     * value (X-05).
     */
    fun rollback() = status.setRollbackOnly()

    val isRollbackOnly: Boolean get() = status.isRollbackOnly
}

/**
 * Port of `DoubleEntry::Locking` (`lib/double_entry/locking.rb:31-172`): runs a block in a transaction holding
 * `SELECT … FOR UPDATE` locks on the balance rows of the given accounts. The SQL sequence is protocol shared with Ruby
 * writers during cut-over (D-017), so it follows Ruby statement by statement.
 */
class Locking internal constructor(
    private val restartable: RestartableTransaction,
    private val balances: AccountBalanceQueries,
    private val rowCreator: BalanceRowCreator,
    private val calculator: BalanceCalculator,
    private val settings: LockingSettings,
    private val depthTracker: TransactionDepthTracker,
) {
    /**
     * `lock_accounts(*accounts, &block)` (`locking.rb:35-51`). Returns the block's value at every level (X-05, D-010;
     * Ruby returned nil at the outermost level).
     *
     * - Inside another `lockAccounts` on this thread every account must already be locked, else [LockNotHeld]; the
     *   block then runs directly in the outer transaction (BR-063).
     * - Otherwise there must be no enclosing transaction (one with the fixtures flag), else
     *   [LockMustBeOutermostTransaction] (BR-060), and the accounts are locked in sorted order (BR-061, BR-062).
     * - An exception from the block rolls the transaction back and propagates (BR-065); a lock wait timeout becomes
     *   [com.softberries.doubleentry.locking.errors.LockWaitTimeout] (BR-068).
     */
    fun <T> lockAccounts(
        vararg accounts: AccountInstance,
        block: (LockContext) -> T,
    ): T = lockAccounts(accounts.asList(), block)

    fun <T> lockAccounts(
        accounts: Collection<AccountInstance>,
        block: (LockContext) -> T,
    ): T =
        SqlStateTranslator.translatingLockWaitTimeout {
            // BR-012: deadlock-free lock order, the same as Ruby writers use.
            val sorted = accounts.sorted()
            val held = heldLocks.get()
            if (held != null) {
                held.ensureLocked(sorted)
                block(held.context)
            } else {
                performLock(sorted, block)
            }
        }

    /**
     * `balance_for_locked_account` (`locking.rb:55-57`, `Lock#balance_for :89-93`): the balance row locked for
     * [account] by the current thread's `lockAccounts`, or [LockNotHeld] (BR-073).
     */
    fun balanceForLockedAccount(account: AccountInstance): AccountBalanceEntity {
        val held = heldLocks.get() ?: throw notHeld(account)
        held.ensureLocked(listOf(account))
        return held.locks.getValue(account)
    }

    /** A variant sharing this instance's transaction depth tracker (one listener per transaction manager). */
    internal fun copy(
        settings: LockingSettings = this.settings,
        rowCreator: BalanceRowCreator = this.rowCreator,
    ): Locking = Locking(restartable, balances, rowCreator, calculator, settings, depthTracker)

    /** `Lock#perform_lock` (`locking.rb:67-74`, BR-062): one retry after creating the missing rows. */
    private fun <T> performLock(
        accounts: List<AccountInstance>,
        block: (LockContext) -> T,
    ): T {
        ensureOutermostTransaction()
        return when (val first = lockAndCall(accounts, block)) {
            is Attempt.Ran -> first.value
            is Attempt.Missing -> {
                createMissingAccountBalances(first.accounts)
                when (val second = lockAndCall(accounts, block)) {
                    is Attempt.Ran -> second.value
                    is Attempt.Missing -> throw LockDisaster()
                }
            }
        }
    }

    /** BR-060 (`locking.rb:115-120`, D-007). */
    private fun ensureOutermostTransaction() {
        val allowed = if (settings.runningInsideTransactionalFixtures) 1 else 0
        if (depthTracker.depth() > allowed) throw LockMustBeOutermostTransaction()
    }

    /**
     * `Lock#lock_and_call` and `grab_locks` (`locking.rb:127-161`, BR-061): in a restartable transaction, select every
     * row `FOR UPDATE` (all of them, even after one is missing, as Ruby). If one is missing, the transaction commits
     * without running the block.
     */
    private fun <T> lockAndCall(
        accounts: List<AccountInstance>,
        block: (LockContext) -> T,
    ): Attempt<T> =
        restartable.run { status ->
            val rows = restartable.withRestartOnDeadlock { accounts.map { balances.findByAccount(it, lock = true) } }
            val missing = accounts.filterIndexed { index, _ -> rows[index] == null }
            if (missing.isNotEmpty()) {
                Attempt.Missing(missing)
            } else {
                val held = HeldLocks(accounts.zip(rows.map { it!! }).toMap(), LockContext(status))
                heldLocks.set(held)
                try {
                    Attempt.Ran(block(held.context))
                } finally {
                    heldLocks.remove()
                }
            }
        }

    /**
     * `Lock#create_missing_account_balances` (`locking.rb:164-171`): per account, in lock order, the BR-040 balance
     * from the lines (outside any lock), then an insert that tolerates a concurrent creator ([BalanceRowCreator]).
     * No transaction is active here outside the fixtures flag, so each insert commits on its own and the rows stay even
     * if the block later fails (BR-031). With `runningInsideTransactionalFixtures` (D-007) they join the host's
     * transaction and roll back with it, as Ruby's `create!` does.
     */
    private fun createMissingAccountBalances(accounts: List<AccountInstance>) {
        for (account in accounts) {
            rowCreator.createIgnoringDuplicates(account, calculator.calculate(account))
        }
    }

    private sealed interface Attempt<out T> {
        data class Ran<T>(
            val value: T,
        ) : Attempt<T>

        data class Missing(
            val accounts: List<AccountInstance>,
        ) : Attempt<Nothing>
    }

    /** The lock map of `Thread.current[:double_entry_locks]` (BR-071) plus the outermost block's context. */
    private class HeldLocks(
        val locks: Map<AccountInstance, AccountBalanceEntity>,
        val context: LockContext,
    ) {
        /** `Lock#ensure_locked!` (`locking.rb:81-87`, BR-063). */
        fun ensureLocked(accounts: List<AccountInstance>) {
            accounts.firstOrNull { it !in locks }?.let { throw notHeld(it) }
        }
    }

    private companion object {
        /** Thread-local as in Ruby (BR-071): threads locking disjoint accounts proceed in parallel. */
        val heldLocks = ThreadLocal<HeldLocks>()

        /** BR-063: the scope part is the scope object's `toString()`, blank without one. */
        fun notHeld(account: AccountInstance) =
            LockNotHeld("No lock held for account: ${account.identifier}, scope ${account.scope?.toString().orEmpty()}")
    }
}
