package com.softberries.doubleentry.ledger

import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.money.MoneyConverter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Read model of a ledger line (`DoubleEntry::Line` accessors, `line.rb:64-143`): an immutable snapshot of the row.
 *
 * As in Ruby, [account], [partnerAccount] and the Money values are derived from the stored identifiers when they are
 * read, against the current configuration. If the account is no longer configured, reading them throws
 * [com.softberries.doubleentry.errors.UnknownAccount] (BR-080). The raw values are always available.
 *
 * Two lines are equal when they have the same id (ActiveRecord equality).
 */
class Line internal constructor(
    val id: Long,
    /** Stored account identifier (`line[:account]`). */
    val accountIdentifier: String,
    /** Stored scope identity of the account, null for unscoped accounts. */
    val scopeIdentity: String?,
    val code: String,
    val amountMinorUnits: Long,
    val balanceMinorUnits: Long,
    val partnerId: Long?,
    val partnerAccountIdentifier: String,
    val partnerScopeIdentity: String?,
    val detail: DetailRef?,
    /** The jsonb metadata as stored (read-only copy), or null. */
    val metadata: Map<String, Any?>?,
    val createdAt: Instant,
    val updatedAt: Instant,
    private val resolver: AccountResolver,
) {
    /** `DoubleEntry.account(line[:account], scope_identity: line.scope)` (`line.rb:106-108`). */
    val account: AccountInstance get() = resolver.account(accountIdentifier, scopeIdentity = scopeIdentity)

    /** `line.rb:121-123`. */
    val partnerAccount: AccountInstance get() = resolver.account(partnerAccountIdentifier, scopeIdentity = partnerScopeIdentity)

    /** The account's configured currency (`line.rb:110-112`). */
    val currency: CurrencyUnit get() = account.currency

    /** Money in the account's currency (BR-080). */
    val amount: Money get() = MoneyConverter.fromColumn(amountMinorUnits, currency)

    /** Running balance as Money in the account's currency (BR-080). */
    val balance: Money get() = MoneyConverter.fromColumn(balanceMinorUnits, currency)

    /** `decrease?`: amount < 0 (BR-083). */
    val isDecrease: Boolean get() = amount.isNegative

    /** `increase?`: amount > 0 (BR-083). */
    val isIncrease: Boolean get() = amount.isPositive

    override fun equals(other: Any?): Boolean = other is Line && id == other.id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "Line(id=$id, account=$accountIdentifier, scope=$scopeIdentity, code=$code, amount=$amountMinorUnits, " +
            "balance=$balanceMinorUnits, partnerId=$partnerId, partnerAccount=$partnerAccountIdentifier, " +
            "partnerScope=$partnerScopeIdentity, detail=$detail, metadata=$metadata, createdAt=$createdAt, updatedAt=$updatedAt)"

    internal companion object {
        fun of(
            entity: LineEntity,
            resolver: AccountResolver,
        ): Line =
            Line(
                id = requireNotNull(entity.id) { "line is not persisted" },
                accountIdentifier = entity.account,
                scopeIdentity = entity.scope,
                code = entity.code,
                amountMinorUnits = entity.amount,
                balanceMinorUnits = entity.balance,
                partnerId = entity.partnerId,
                partnerAccountIdentifier = entity.partnerAccount,
                partnerScopeIdentity = entity.partnerScope,
                detail = entity.detail,
                metadata = entity.metadata?.let { java.util.Collections.unmodifiableMap(LinkedHashMap(it)) },
                createdAt = entity.createdAt.toUtcInstant(),
                updatedAt = entity.updatedAt.toUtcInstant(),
                resolver = resolver,
            )

        private fun LocalDateTime.toUtcInstant(): Instant = toInstant(ZoneOffset.UTC)
    }
}
