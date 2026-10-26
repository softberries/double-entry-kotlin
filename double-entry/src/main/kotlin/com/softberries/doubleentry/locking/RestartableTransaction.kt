package com.softberries.doubleentry.locking

import com.softberries.doubleentry.instrumentation.DoubleEntryEventPublisher
import com.softberries.doubleentry.instrumentation.DoubleEntryEventType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate

/**
 * Port of `restartable_transaction` and `with_restart_on_deadlock` (`locking_extensions.rb:9-27`, BR-067).
 *
 * [run] executes its block in a transaction; when the block throws [RestartTransactionSignal] the transaction is rolled
 * back and the block runs again from the beginning, with no limit (as Ruby; see `docs/unknowns.md` §D).
 * [withRestartOnDeadlock] turns a deadlock (SQLSTATE `40P01`, D-008) into that signal after publishing
 * `deadlock_restart.double_entry`. Only the code wrapped in [withRestartOnDeadlock] (acquiring the locks) is
 * restarted: a deadlock elsewhere in the block propagates unchanged.
 *
 * The transaction uses the default propagation (`REQUIRED`); the caller (`lockAccounts`, M-011) makes sure it is the
 * outermost one (BR-060).
 */
class RestartableTransaction(
    transactionManager: PlatformTransactionManager,
    private val publisher: DoubleEntryEventPublisher,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    /** Runs [block] in a transaction, rerunning it in a fresh transaction for every restart signal; returns its value. */
    fun <T> run(block: (TransactionStatus) -> T): T {
        while (true) {
            try {
                @Suppress("UNCHECKED_CAST")
                return transactionTemplate.execute { status -> block(status) } as T
            } catch (restart: RestartTransactionSignal) {
                // The transaction was rolled back; start again.
            }
        }
    }

    /** Runs [block]; a deadlock publishes `deadlock_restart.double_entry` and restarts the enclosing [run]. */
    fun <T> withRestartOnDeadlock(block: () -> T): T =
        try {
            block()
        } catch (signal: RestartTransactionSignal) {
            // A nested withRestartOnDeadlock already published; its cause is the deadlock, so don't classify it again.
            throw signal
        } catch (e: Exception) {
            // Exception, not RuntimeException: Kotlin code may throw a checked SQLException straight from JDBC.
            if (!SqlStateTranslator.isDeadlock(e)) throw e
            publisher.publish(DoubleEntryEventType.DEADLOCK_RESTART, e)
            throw RestartTransactionSignal(e)
        }
}
