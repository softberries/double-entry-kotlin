package com.softberries.doubleentry.balance.cache

import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.config.DoubleEntryTables
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.money.MoneyConverter
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/**
 * A row of `double_entry_account_balances` (`DoubleEntry::AccountBalance`, `account_balance.rb:10-53`;
 * `docs/10-domain-model.md` §6, `docs/50-data.md` §2): the cached current balance of one account, and the row that
 * is locked with `SELECT … FOR UPDATE` while a transfer runs.
 *
 * Columns hold the raw stored values (identifier, scope identity, minor units, UTC wall-clock timestamps). As in Ruby,
 * the account and the Money balance are rebuilt from them against the current configuration when they are read.
 * Setters are internal: rows are created by the locking protocol and updated by transfers and the AccountFixer.
 *
 * After Flyway V2 there is exactly one row per (scope, account), also for unscoped accounts (BR-070, X-03).
 */
@Entity
@Table(name = DoubleEntryTables.ACCOUNT_BALANCES)
class AccountBalanceEntity internal constructor(
    account: AccountInstance,
    balance: Money,
    now: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        internal set

    /** Account identifier (`self[:account]`). */
    @Column(name = "account", nullable = false)
    var accountIdentifier: String = account.identifier
        internal set

    /** Scope identity of the account, null for unscoped accounts (`self[:scope]`). */
    @Column(name = "scope")
    var scopeIdentity: String? = account.scopeIdentity
        internal set

    /** Cached balance in minor units (`self[:balance]`). */
    @Column(name = "balance", nullable = false)
    var balanceMinorUnits: Long = MoneyConverter.toColumn(balance)
        internal set

    /** `LOCAL_DATE_TIME` binds the UTC wall-clock value as is (see [com.softberries.doubleentry.ledger.LineEntity]). */
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = now
        internal set

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = now
        internal set

    /** `account=` (`account_balance.rb:21-25`): stores the identifier and the scope identity. */
    internal fun assignAccount(account: AccountInstance) {
        accountIdentifier = account.identifier
        scopeIdentity = account.scopeIdentity
    }

    /** `balance=` (`account_balance.rb:17-19`): stores the minor units; the currency is the account's. */
    internal fun assignBalance(money: Money) {
        balanceMinorUnits = MoneyConverter.toColumn(money)
    }

    /**
     * `account` (`account_balance.rb:27-29`): `DoubleEntry.account(identifier, scope_identity: scope)`.
     *
     * @throws com.softberries.doubleentry.errors.UnknownAccount if the account is no longer configured
     */
    fun account(resolver: AccountResolver): AccountInstance = resolver.account(accountIdentifier, scopeIdentity = scopeIdentity)

    /** `currency`, delegated to the account (`account_balance.rb:11`). */
    fun currency(resolver: AccountResolver): CurrencyUnit = account(resolver).currency

    /** `balance` (`account_balance.rb:13-15`): Money in the account's currency. */
    fun balance(resolver: AccountResolver): Money = MoneyConverter.fromColumn(balanceMinorUnits, currency(resolver))

    override fun equals(other: Any?): Boolean = other is AccountBalanceEntity && id != null && id == other.id

    override fun hashCode(): Int = AccountBalanceEntity::class.hashCode()

    override fun toString(): String =
        "AccountBalanceEntity(id=$id, account=$accountIdentifier, scope=$scopeIdentity, balance=$balanceMinorUnits, " +
            "createdAt=$createdAt, updatedAt=$updatedAt)"
}
