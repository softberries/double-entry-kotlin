package com.softberries.doubleentry.account

import com.softberries.doubleentry.config.DoubleEntryDsl
import com.softberries.doubleentry.errors.AccountIdentifierTooLongError
import com.softberries.doubleentry.errors.DuplicateAccount
import com.softberries.doubleentry.money.CurrencyUnit
import java.util.Collections

/**
 * The configured accounts (`DoubleEntry::Account::Set`, `account.rb:28-66`), keyed by identifier, in definition order.
 * Immutable once built (D-016, X-09); build it with [build] or the `doubleEntry { accounts { … } }` DSL.
 */
class AccountSet private constructor(
    private val byIdentifier: Map<String, AccountDefinition>,
) : Iterable<AccountDefinition> {
    /** All definitions in definition order (`Set#all`). Read-only. */
    val all: List<AccountDefinition> = Collections.unmodifiableList(byIdentifier.values.toList())

    val size: Int get() = byIdentifier.size

    override fun iterator(): Iterator<AccountDefinition> = all.iterator()

    /** The definition with this identifier, scoped or not (`Set#find_without_scope`). */
    fun findWithoutScope(identifier: String): AccountDefinition? = byIdentifier[identifier]

    /** The configured currency of an account, or null if the identifier is unknown (`Account.currency`, BR-013). */
    fun currencyOf(identifier: String): CurrencyUnit? = findWithoutScope(identifier)?.currency

    override fun toString(): String = "AccountSet(${byIdentifier.keys})"

    /** Collects definitions (`Set#define`) and validates each one as it is defined. */
    @DoubleEntryDsl
    class Builder internal constructor(
        private val accountIdentifierMaxLength: Int?,
        private val defaultCurrency: CurrencyUnit,
    ) {
        private val definitions = LinkedHashMap<String, AccountDefinition>()

        /**
         * Defines an account. [currency] is a money-gem currency code, case-insensitive; null means the configured
         * default currency, resolved now (BR-001).
         *
         * @throws AccountIdentifierTooLongError if [identifier] is longer than the configured maximum (BR-005).
         * @throws DuplicateAccount if the identifier is already defined, scoped or unscoped (BR-002).
         * @throws com.softberries.doubleentry.money.UnknownCurrencyException if [currency] is not a known currency.
         */
        fun define(
            identifier: String,
            scopeIdentifier: ScopeIdentifier? = null,
            positiveOnly: Boolean = false,
            negativeOnly: Boolean = false,
            currency: String? = null,
        ): AccountDefinition {
            // BR-005: Ruby `String#length` counts characters, hence code points rather than UTF-16 units.
            if (accountIdentifierMaxLength != null && identifier.codePointCount() > accountIdentifierMaxLength) {
                throw AccountIdentifierTooLongError(
                    "account identifier '$identifier' is too long. Please limit it to $accountIdentifierMaxLength characters.",
                )
            }
            val definition =
                AccountDefinition(
                    identifier = identifier,
                    scopeIdentifier = scopeIdentifier,
                    positiveOnly = positiveOnly,
                    negativeOnly = negativeOnly,
                    currency = currency?.let(CurrencyUnit::of) ?: defaultCurrency,
                )
            if (identifier in definitions) throw DuplicateAccount()
            definitions[identifier] = definition
            return definition
        }

        internal fun build(): AccountSet = AccountSet(LinkedHashMap(definitions))
    }

    companion object {
        val EMPTY: AccountSet = AccountSet(emptyMap())

        /** Builds a standalone set; [defaultCurrency] mirrors `Money.default_currency` (USD in the Ruby specs). */
        fun build(
            accountIdentifierMaxLength: Int? = null,
            defaultCurrency: CurrencyUnit = CurrencyUnit.USD,
            block: Builder.() -> Unit,
        ): AccountSet = Builder(accountIdentifierMaxLength, defaultCurrency).apply(block).build()
    }
}

internal fun String.codePointCount(): Int = codePointCount(0, length)
