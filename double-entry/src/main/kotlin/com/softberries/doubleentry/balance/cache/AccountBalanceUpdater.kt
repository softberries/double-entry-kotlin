package com.softberries.doubleentry.balance.cache

import com.softberries.doubleentry.money.Money
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.springframework.orm.jpa.EntityManagerFactoryUtils
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * `account_balance.update_attribute :balance, …` (`transfer.rb:104-105`): sets the cached balance of a row the caller
 * has locked, bumps `updated_at` from `doubleEntryClock` and writes the `UPDATE` immediately, so the statement order of
 * the transfer protocol is kept (BR-025, D-017). Must run inside the lock transaction.
 *
 * As with ActiveRecord dirty tracking, an unchanged balance writes nothing, not even `updated_at`
 * (a zero-amount transfer issues no balance `UPDATE`, `oracle/api/json/transfer_zero_amount.json`, BR-022).
 */
class AccountBalanceUpdater internal constructor(
    private val entityManager: EntityManager,
    private val clock: Clock,
) {
    internal fun update(
        row: AccountBalanceEntity,
        balance: Money,
    ): AccountBalanceEntity {
        requireNotNull(row.id) { "balance row is not persisted" }
        if (row.balanceMinorUnits == balance.minorUnits) return row
        row.assignBalance(balance)
        row.updatedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
        return try {
            entityManager.merge(row).also { entityManager.flush() }
        } catch (e: PersistenceException) {
            throw EntityManagerFactoryUtils.convertJpaAccessExceptionIfPossible(e) ?: e
        }
    }
}
