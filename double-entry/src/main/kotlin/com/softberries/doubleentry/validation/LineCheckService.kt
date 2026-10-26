package com.softberries.doubleentry.validation

import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.balance.BalanceCalculator
import com.softberries.doubleentry.balance.cache.AccountBalanceQueries
import com.softberries.doubleentry.ledger.Line
import com.softberries.doubleentry.ledger.LineEntity
import com.softberries.doubleentry.locking.Locking
import com.softberries.doubleentry.money.Money
import jakarta.persistence.EntityManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Port of `DoubleEntry::Validation::LineCheck` (`lib/double_entry/validation/line_check.rb`): detects broken running
 * balances and cached balances, and optionally repairs them with an [AccountFixer]. The library ships no schedule;
 * hosts call [perform] themselves (e.g. hourly from a `@Scheduled` method).
 *
 * Known limitation, kept for parity (U-013): a run checks the lines with `id > lastLineIdChecked()`. A line that takes
 * its id before, but commits after, a run that already saw a higher id is never checked.
 */
class LineCheckService internal constructor(
    private val entityManager: EntityManager,
    private val repository: LineCheckRepository,
    private val balances: AccountBalanceQueries,
    private val calculator: BalanceCalculator,
    private val locking: Locking,
    private val resolver: AccountResolver,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) {
    private val transaction = TransactionTemplate(transactionManager)

    /** `LineCheck.last_line_id_checked` (BR-090): `last_line_id` of the newest check by created_at, else 0. */
    fun lastLineIdChecked(): Long = repository.findFirstByOrderByCreatedAtDesc()?.lastLineId ?: 0L

    /**
     * `LineCheck.perform!(fixer:)` (`line_check.rb:12-42`).
     *
     * 1. Every line with `id > lastLineIdChecked()`, in id order and in pages of [BATCH_SIZE], is compared with the
     *    previous line of the same account and scope (BR-091). Its account becomes active; a mismatch is logged and
     *    marks the account incorrect.
     * 2. Every active account, in first-seen order, is locked on its own ([Locking.lockAccounts]) and its cached balance
     *    compared with the line balance (BR-092).
     * 3. With a [fixer], every incorrect account is recalculated (BR-093).
     * 4. If at least one line was visited, a check row is inserted and returned; otherwise nothing is written and the
     *    result is null (BR-094).
     *
     * Must not run inside a transaction once there are new lines: the cached-balance check takes account locks
     * ([com.softberries.doubleentry.locking.errors.LockMustBeOutermostTransaction]). Line pages are read outside
     * any lock, so a run never holds one long transaction.
     */
    fun perform(fixer: AccountFixer? = null): LineCheck? {
        val log = StringBuilder()
        var currentLineId: Long? = null
        val activeAccounts = LinkedHashSet<AccountInstance>()
        val incorrectAccounts = LinkedHashSet<AccountInstance>()

        val lastChecked = lastLineIdChecked()
        var after = lastChecked
        while (true) {
            val page = linesAfter(lastChecked, after)
            for (line in page) {
                if (!runningBalanceCorrect(line, log)) incorrectAccounts += line.account
                activeAccounts += line.account
                currentLineId = line.id
            }
            if (page.size < BATCH_SIZE) break
            after = page.last().id
        }

        for (account in activeAccounts) {
            if (!cachedBalanceCorrect(account, log)) incorrectAccounts += account
        }

        if (fixer != null) incorrectAccounts.forEach(fixer::recalculateAccount)

        val lastLineId = currentLineId ?: return null
        return transaction.execute {
            val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
            val entity = LineCheckEntity(lastLineId, incorrectAccounts.isNotEmpty(), log.toString(), now)
            entityManager.persist(entity)
            entityManager.flush()
            LineCheck.of(entity)
        }
    }

    /**
     * One `find_each` page (`Line.with_id_greater_than(last)`, BR-082): ids above both the last checked id and the
     * last id of the previous page, ordered by id.
     */
    private fun linesAfter(
        lastChecked: Long,
        after: Long,
    ): List<Line> {
        val query =
            if (after == lastChecked) {
                entityManager.createQuery("select l from LineEntity l where l.id > :last order by l.id", LineEntity::class.java)
            } else {
                entityManager
                    .createQuery(
                        "select l from LineEntity l where l.id > :last and l.id > :after order by l.id",
                        LineEntity::class.java,
                    ).setParameter("after", after)
            }
        return query
            .setParameter("last", lastChecked)
            .setMaxResults(BATCH_SIZE)
            .resultList
            .map { Line.of(it, resolver) }
    }

    /** `running_balance_correct?` (`line_check.rb:50-60`, BR-091). */
    private fun runningBalanceCorrect(
        line: Line,
        log: StringBuilder,
    ): Boolean {
        val previousLine = findPreviousLine(line.account.identifier, line.scopeIdentity, line.id)
        val previousBalance = previousLine?.balance ?: Money.zero(line.account.currency)
        val correct = line.balance == line.amount + previousBalance
        if (!correct) log.append(LineCheckLog.lineError(line, previousLine, previousBalance))
        return correct
    }

    /**
     * `find_previous_line` (`line_check.rb:62-85`): the latest line of the same account and scope before [id], with
     * `scope IS NULL` for unscoped lines. The MySQL `FORCE INDEX` hint (`:87-95`) is not ported (X-01).
     */
    private fun findPreviousLine(
        identifier: String,
        scope: String?,
        id: Long,
    ): Line? {
        val sql =
            "SELECT * FROM double_entry_lines WHERE account = :account AND " +
                (if (scope == null) "scope IS NULL" else "scope = :scope") +
                " AND id < :id ORDER BY id DESC LIMIT 1"
        val query = entityManager.createNativeQuery(sql, LineEntity::class.java)
        query.setParameter("account", identifier)
        scope?.let { query.setParameter("scope", it) }
        query.setParameter("id", id)
        return (query.resultList.firstOrNull() as LineEntity?)?.let { Line.of(it, resolver) }
    }

    /** `cached_balance_correct?` (`line_check.rb:108-121`, BR-092): read-only, inside the account's lock. */
    private fun cachedBalanceCorrect(
        account: AccountInstance,
        log: StringBuilder,
    ): Boolean =
        locking.lockAccounts(account) {
            // The lock created the balance row if it was missing, so it exists here.
            val cached = checkNotNull(balances.findByAccount(account)) { "no balance row for $account" }.balance(resolver)
            val running = calculator.calculate(account)
            val correct = cached == running
            if (!correct) log.append(LineCheckLog.accountError(account, cached, running))
            correct
        }

    internal companion object {
        /** `find_each` default batch size. */
        const val BATCH_SIZE = 1000
    }
}
