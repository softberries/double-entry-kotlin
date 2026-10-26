package com.softberries.doubleentry.balance

import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.money.Money
import jakarta.persistence.EntityManager
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Current or historic balance of an account (`DoubleEntry::BalanceCalculator`, `balance_calculator.rb:16-96`).
 *
 * Always reads `double_entry_lines`, never the cached `double_entry_account_balances` (BR-048):
 * - no `from`+`to` pair and no codes: the `balance` of the latest line **by id**, optionally limited to
 *   `created_at <= at` (BR-040, BR-042); no line gives zero (BR-041);
 * - otherwise `SUM(amount)` over the `BETWEEN from AND to` window or `created_at <= at`, and the codes (BR-043..045).
 *
 * The result is Money in the account's currency. The MySQL `USE INDEX` hint is not ported (BR-047, X-01).
 * The queries run in the caller's transaction if there is one (Hibernate flushes pending lines first).
 */
class BalanceCalculator(
    private val entityManager: EntityManager,
) {
    fun calculate(
        account: AccountInstance,
        options: BalanceOptions = BalanceOptions(),
    ): Money {
        val conditions = mutableListOf("l.account = :account")
        val parameters = mutableMapOf<String, Any>("account" to account.identifier)
        // Same predicate order as `RelationBuilder#build` (`balance_calculator.rb:88-95`).
        options.at?.let {
            conditions += "l.createdAt <= :at"
            parameters["at"] = it.toColumn()
        }
        val from = options.from
        val to = options.to
        if (options.isBetween && from != null && to != null) {
            conditions += "l.createdAt between :from and :to"
            parameters["from"] = from.toColumn()
            parameters["to"] = to.toColumn()
        }
        val codes = options.effectiveCodes
        when (codes.size) {
            0 -> Unit
            // ActiveRecord renders a one-element list as `code = ?`.
            1 -> {
                conditions += "l.code = :code"
                parameters["code"] = codes.single()
            }
            else -> {
                conditions += "l.code in :codes"
                parameters["codes"] = codes
            }
        }
        // BR-040: a scope predicate only for scoped accounts; there is no `scope IS NULL` for unscoped ones.
        account.scopeIdentity?.let {
            conditions += "l.scope = :scope"
            parameters["scope"] = it
        }
        val where = conditions.joinToString(" and ")

        val minorUnits =
            if (options.isSum) {
                val query = entityManager.createQuery("select sum(l.amount) from LineEntity l where $where", Long::class.javaObjectType)
                parameters.forEach { (name, value) -> query.setParameter(name, value) }
                query.singleResult ?: 0L
            } else {
                val query =
                    entityManager.createQuery(
                        "select l.balance from LineEntity l where $where order by l.id desc",
                        Long::class.javaObjectType,
                    )
                parameters.forEach { (name, value) -> query.setParameter(name, value) }
                query.maxResults = 1
                query.resultList.firstOrNull() ?: 0L
            }
        return Money.ofMinor(minorUnits, account.currency)
    }

    /** UTC wall-clock time at the column's microsecond precision (BR-046). */
    private fun Instant.toColumn(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
}

/** `Account::Instance#balance(options)` (`account.rb:96-98`): delegates to [calculator]. */
fun AccountInstance.balance(
    calculator: BalanceCalculator,
    options: BalanceOptions = BalanceOptions(),
): Money = calculator.calculate(this, options)
