package com.softberries.doubleentry.balance.cache

import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.money.Money
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext

/** Query fragment of [AccountBalanceRepository]. */
interface AccountBalanceQueries {
    /**
     * `find_by_account(account, lock:)` (`account_balance.rb:31-35`): the balance row of [account], the lowest id if
     * there were several (BR-070), or null. With [lock] the row is locked `FOR UPDATE` until the transaction ends;
     * the caller must run inside one (BR-061).
     */
    fun findByAccount(
        account: AccountInstance,
        lock: Boolean = false,
    ): AccountBalanceEntity?

    /**
     * `scopes_with_minimum_balance_for_account` (BR-050, `account_balance.rb:50-52`): the scope identities of the
     * [accountIdentifier] rows whose cached balance is at least [minimumBalance]'s minor units; null for an unscoped
     * account. The currency of [minimumBalance] is ignored, as in Ruby. No guaranteed order.
     */
    fun scopesWithMinimumBalanceForAccount(
        minimumBalance: Money,
        accountIdentifier: String,
    ): List<String?>
}

/**
 * [findByAccount] uses native SQL so the statement has the same shape as ActiveRecord's
 * (`oracle/api/json/lock_order_sql.json`): `scope = ?` for scoped and `scope IS NULL` for unscoped accounts, then
 * `account = ?`, `ORDER BY id ASC LIMIT 1 [FOR UPDATE]`.
 */
class AccountBalanceQueriesImpl : AccountBalanceQueries {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun findByAccount(
        account: AccountInstance,
        lock: Boolean,
    ): AccountBalanceEntity? {
        val scopeIdentity = account.scopeIdentity
        val sql =
            buildString {
                append("SELECT * FROM double_entry_account_balances WHERE ")
                append(if (scopeIdentity == null) "scope IS NULL" else "scope = :scope")
                append(" AND account = :account ORDER BY id ASC LIMIT 1")
                if (lock) append(" FOR UPDATE")
            }
        val query = entityManager.createNativeQuery(sql, AccountBalanceEntity::class.java)
        scopeIdentity?.let { query.setParameter("scope", it) }
        query.setParameter("account", account.identifier)
        return query.resultList.firstOrNull() as AccountBalanceEntity?
    }

    override fun scopesWithMinimumBalanceForAccount(
        minimumBalance: Money,
        accountIdentifier: String,
    ): List<String?> =
        entityManager
            .createQuery(
                "select b.scopeIdentity from AccountBalanceEntity b where b.accountIdentifier = :account and b.balanceMinorUnits >= :minimum",
                String::class.java,
            ).setParameter("account", accountIdentifier)
            .setParameter("minimum", minimumBalance.minorUnits)
            .resultList
}
