package com.softberries.doubleentry.validation

import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.balance.cache.AccountBalanceUpdater
import com.softberries.doubleentry.ledger.LineEntity
import com.softberries.doubleentry.ledger.LineWriter
import com.softberries.doubleentry.locking.Locking
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.money.MoneyConverter
import jakarta.persistence.EntityManager

/**
 * Port of `DoubleEntry::Validation::AccountFixer` (`lib/double_entry/validation/account_fixer.rb`, BR-096): rebuilds the
 * running balances of an account's lines and its cached balance from the line amounts.
 */
class AccountFixer internal constructor(
    private val entityManager: EntityManager,
    private val locking: Locking,
    private val lineWriter: LineWriter,
    private val balanceUpdater: AccountBalanceUpdater,
) {
    /**
     * `recalculate_account` (`account_fixer.rb:6-18`): inside `lockAccounts(account)` (so it must be the outermost
     * transaction), walks the account's lines by id with a running sum from zero, rewrites every line whose balance
     * differs, and sets the cached balance to the sum.
     *
     * Rewrites go through the BR-030 guard, so a positive-only account whose true history goes negative throws
     * [com.softberries.doubleentry.errors.AccountWouldBeSentNegative] (negative-only:
     * [com.softberries.doubleentry.errors.AccountWouldBeSentPositiveError]) and the whole repair is rolled back.
     */
    fun recalculateAccount(account: AccountInstance) {
        locking.lockAccounts(account) {
            var recalculated = Money.zero(account.currency)
            for (line in linesForAccount(account)) {
                recalculated += MoneyConverter.fromColumn(line.amount, account.currency)
                if (line.balance != recalculated.minorUnits) {
                    line.balance = recalculated.minorUnits
                    lineWriter.update(line)
                }
            }
            balanceUpdater.update(locking.balanceForLockedAccount(account), recalculated)
        }
    }

    /** `lines_for_account` (`account_fixer.rb:22-27`): identifier and scope identity (`scope IS NULL` if absent), by id. */
    private fun linesForAccount(account: AccountInstance): List<LineEntity> {
        val scope = account.scopeIdentity
        val jpql =
            "select l from LineEntity l where l.account = :account and " +
                (if (scope == null) "l.scope is null" else "l.scope = :scope") +
                " order by l.id"
        val query = entityManager.createQuery(jpql, LineEntity::class.java).setParameter("account", account.identifier)
        scope?.let { query.setParameter("scope", it) }
        return query.resultList
    }
}
