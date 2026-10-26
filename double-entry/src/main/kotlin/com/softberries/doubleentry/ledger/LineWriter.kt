package com.softberries.doubleentry.ledger

import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.errors.AccountWouldBeSentNegative
import com.softberries.doubleentry.errors.AccountWouldBeSentPositiveError
import com.softberries.doubleentry.money.MoneyConverter
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.springframework.orm.jpa.EntityManagerFactoryUtils
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * The only save path for ledger lines (`Line#save`/`save!`, `line.rb:80-88`). Every insert and update first runs the
 * positive/negative-only guard (BR-030), then stamps the timestamps from `doubleEntryClock` (BR-035) and writes the
 * row immediately, so database errors surface here as Spring `DataAccessException`s.
 *
 * Joins the caller's transaction, or opens one (as `save!` does).
 */
@Transactional
class LineWriter(
    private val entityManager: EntityManager,
    private val resolver: AccountResolver,
    private val clock: Clock,
) {
    /** Creates a line directly (BR-085). */
    fun create(line: NewLine): Line {
        val entity =
            LineEntity(
                account = line.account.identifier,
                scope = line.account.scopeIdentity,
                code = line.code,
                amount = MoneyConverter.toColumn(line.amount),
                balance = MoneyConverter.toColumn(line.balance),
                partnerAccount = line.partnerAccount.identifier,
                partnerScope = line.partnerAccount.scopeIdentity,
                partnerId = line.partnerId,
                detail = line.detail,
                metadata = line.metadata,
            )
        return Line.of(insert(entity), resolver)
    }

    /** Inserts a new [entity]; `created_at` = `updated_at` = now. */
    internal fun insert(entity: LineEntity): LineEntity {
        require(entity.id == null) { "line ${entity.id} is already persisted" }
        checkBalanceWillRemainValid(entity)
        val now = now()
        entity.createdAt = now
        entity.updatedAt = now
        return write {
            entityManager.persist(entity)
            entityManager.flush()
            entity
        }
    }

    /**
     * Saves the changes made to an existing [entity] (e.g. the partner-id update of a transfer, BR-025, or the
     * AccountFixer rewrite, BR-096) and bumps `updated_at`. Returns the managed instance.
     */
    internal fun update(entity: LineEntity): LineEntity {
        requireNotNull(entity.id) { "line is not persisted" }
        checkBalanceWillRemainValid(entity)
        entity.updatedAt = now()
        return write {
            val managed = entityManager.merge(entity)
            entityManager.flush()
            managed
        }
    }

    /**
     * BR-030: checked against the account rebuilt from the stored identifier and scope identity, so the message is
     * that account's `toString()` (scope object absent) and an unconfigured account throws `UnknownAccount`.
     * A balance of exactly zero is allowed.
     */
    private fun checkBalanceWillRemainValid(entity: LineEntity) {
        val account = resolver.account(entity.account, scopeIdentity = entity.scope)
        if (account.positiveOnly && entity.balance < 0) throw AccountWouldBeSentNegative(account.toString())
        if (account.negativeOnly && entity.balance > 0) throw AccountWouldBeSentPositiveError(account.toString())
    }

    /** UTC wall-clock time with the column's microsecond precision (BR-035). */
    private fun now(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)

    private fun <T> write(block: () -> T): T =
        try {
            block()
        } catch (e: PersistenceException) {
            // Hibernate exceptions are PersistenceExceptions; translate them as a @Repository proxy would.
            throw EntityManagerFactoryUtils.convertJpaAccessExceptionIfPossible(e) ?: e
        }
}
