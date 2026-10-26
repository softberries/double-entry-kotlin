package com.softberries.doubleentry.account

import com.softberries.doubleentry.money.CurrencyUnit

/**
 * Turns the scope object of a scoped account (e.g. a `User`) into its identity, like the Ruby `scope_identifier`
 * lambda (`account.rb:145`). The account instance (M-006) applies it and stringifies the result (D-014).
 */
typealias ScopeIdentifier = (Any) -> Any?

/**
 * A configured account (`DoubleEntry::Account`, `account.rb:141-159`): built only through [AccountSet.Builder.define],
 * which enforces BR-002 and BR-005. The currency is resolved when the definition is built (BR-001).
 */
class AccountDefinition internal constructor(
    val identifier: String,
    val scopeIdentifier: ScopeIdentifier?,
    val positiveOnly: Boolean,
    val negativeOnly: Boolean,
    val currency: CurrencyUnit,
) {
    /** `scoped?`: the account has a [scopeIdentifier]. */
    val scoped: Boolean get() = scopeIdentifier != null

    override fun toString(): String =
        "AccountDefinition(identifier=$identifier, scoped=$scoped, positiveOnly=$positiveOnly, " +
            "negativeOnly=$negativeOnly, currency=$currency)"
}
