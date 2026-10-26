package com.softberries.doubleentry.locking

import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.balance.cache.AccountBalanceEntity
import com.softberries.doubleentry.instrumentation.DoubleEntryEventPublisher
import com.softberries.doubleentry.instrumentation.DoubleEntryEventType
import com.softberries.doubleentry.money.Money
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.springframework.orm.jpa.EntityManagerFactoryUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Port of `AccountBalance.create_ignoring_duplicates!` (`locking_extensions.rb:31-72`, BR-069): creates the balance
 * row of an account in its own transaction (`REQUIRES_NEW`), tolerating a concurrent writer that created it first.
 *
 * - unique violation (SQLSTATE `23505`) → `duplicate_ignore.double_entry` is published and the error swallowed;
 * - deadlock (`40P01`) → `deadlock_retry.double_entry` is published and the insert retried, without limit (as Ruby);
 * - anything else propagates unchanged.
 *
 * Must not be called while a `lockAccounts` transaction is open on the same thread: M-011 commits the first lock
 * transaction before creating missing rows (BR-062).
 */
class BalanceRowCreator internal constructor(
    transactionManager: PlatformTransactionManager,
    private val inserter: BalanceRowInserter,
    private val clock: Clock,
    private val publisher: DoubleEntryEventPublisher,
) {
    private val transactionTemplate =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    /**
     * Inserts the balance row of [account] with [balance] (`created_at` = `updated_at` = now). Returns the new row, or
     * null if a row for the account already existed and the duplicate was ignored.
     */
    fun createIgnoringDuplicates(
        account: AccountInstance,
        balance: Money,
    ): AccountBalanceEntity? {
        while (true) {
            try {
                // A fresh entity per attempt: a failed persist leaves the previous one in an undefined state.
                return transactionTemplate.execute { inserter.insert(AccountBalanceEntity(account, balance, now())) }
            } catch (e: RuntimeException) {
                when (SqlStateTranslator.sqlStateOf(e)) {
                    SqlStateTranslator.UNIQUE_VIOLATION -> {
                        publisher.publish(DoubleEntryEventType.DUPLICATE_IGNORE, e)
                        return null
                    }
                    SqlStateTranslator.DEADLOCK_DETECTED -> publisher.publish(DoubleEntryEventType.DEADLOCK_RETRY, e)
                    else -> throw e
                }
            }
        }
    }

    /** UTC wall-clock time with the column's microsecond precision (BR-035). */
    private fun now(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
}

/** Writes a new balance row immediately, so database errors surface as Spring `DataAccessException`s. */
internal class BalanceRowInserter(
    private val entityManager: EntityManager,
) {
    fun insert(entity: AccountBalanceEntity): AccountBalanceEntity =
        try {
            entityManager.persist(entity)
            entityManager.flush()
            entity
        } catch (e: PersistenceException) {
            // Hibernate exceptions are PersistenceExceptions; translate them as a @Repository proxy would.
            throw EntityManagerFactoryUtils.convertJpaAccessExceptionIfPossible(e) ?: e
        }
}
