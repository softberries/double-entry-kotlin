package com.softberries.doubleentry.locking

import org.springframework.transaction.ConfigurableTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionExecution
import org.springframework.transaction.TransactionExecutionListener
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * The Spring counterpart of ActiveRecord's `connection.open_transactions` (`locking.rb:117`), needed by BR-060 with
 * the fixtures flag (D-007). Spring does not expose a nesting depth, so this listener, registered on the transaction
 * manager, keeps the transactions begun on the current thread that are not completed yet: every new physical
 * transaction (`REQUIRED` without an outer one, `REQUIRES_NEW`) and every savepoint (`NESTED`) counts as one level,
 * like ActiveRecord's `transaction` and `transaction(requires_new: true)`; a participating `REQUIRED` adds none.
 */
class TransactionDepthTracker : TransactionExecutionListener {
    private val open = ThreadLocal.withInitial { ArrayList<TransactionExecution>() }

    /** Whether the tracker is registered on the transaction manager in use; without it [depth] can only say 0 or 1. */
    @Volatile
    var attached: Boolean = false
        private set

    /** Registers this tracker on [transactionManager] if it accepts listeners; returns whether it does. */
    fun attachTo(transactionManager: PlatformTransactionManager): Boolean {
        if (transactionManager is ConfigurableTransactionManager) {
            if (this !in transactionManager.transactionExecutionListeners) transactionManager.addListener(this)
            attached = true
        }
        return attached
    }

    /** Transaction nesting depth on the current thread: 0 when no actual transaction is active. */
    fun depth(): Int {
        val executions = open.get()
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            executions.clear()
            return 0
        }
        executions.removeIf { it.isCompleted }
        return if (attached) maxOf(1, executions.size) else 1
    }

    override fun afterBegin(
        transaction: TransactionExecution,
        beginFailure: Throwable?,
    ) {
        if (beginFailure == null && (transaction.isNewTransaction || transaction.isNested)) open.get() += transaction
    }

    override fun afterCommit(
        transaction: TransactionExecution,
        commitFailure: Throwable?,
    ) = remove(transaction)

    override fun afterRollback(
        transaction: TransactionExecution,
        rollbackFailure: Throwable?,
    ) = remove(transaction)

    private fun remove(transaction: TransactionExecution) {
        val executions = open.get()
        executions.removeIf { it === transaction || it.isCompleted }
        if (executions.isEmpty()) open.remove()
    }
}
