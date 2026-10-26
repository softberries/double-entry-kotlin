package com.softberries.doubleentry.account

import com.softberries.doubleentry.errors.ScopeIdentifierTooLongError
import com.softberries.doubleentry.errors.UnknownAccount

/**
 * Looks up configured accounts and builds [AccountInstance]s (`DoubleEntry::Account.account`, `account.rb:16-19`).
 */
class AccountResolver(
    private val accounts: AccountSet,
    private val scopeIdentifierMaxLength: Int? = null,
) {
    /**
     * The account [identifier], scoped by [scope] (a scope object passed to the account's [ScopeIdentifier]) or by a
     * ready [scopeIdentity] (e.g. read back from a row), which takes precedence for the identity.
     *
     * The lookup is scoped iff [scope] or [scopeIdentity] is present in the Ruby `present?` sense (BR-009): null,
     * blank strings, `false` and empty collections are absent; `0` is present.
     *
     * @throws UnknownAccount `account: <identifier> scoped?: <bool>` if the identifier is not defined or its scoped-ness
     *   differs from the lookup (BR-009).
     * @throws ScopeIdentifierTooLongError if the identity exceeds the configured maximum (BR-007).
     * Exceptions thrown by the account's [ScopeIdentifier] propagate unchanged (BR-011).
     */
    fun account(
        identifier: String,
        scope: Any? = null,
        scopeIdentity: String? = null,
    ): AccountInstance {
        val definition = find(identifier, isPresent(scope) || isPresent(scopeIdentity))
        return AccountInstance(definition, scope, scopeIdentity, scopeIdentifierMaxLength)
    }

    /** `Set#find`: the definition if it exists and its scoped-ness equals [scoped]. */
    fun find(
        identifier: String,
        scoped: Boolean,
    ): AccountDefinition =
        accounts.findWithoutScope(identifier)?.takeIf { it.scoped == scoped }
            ?: throw UnknownAccount("account: $identifier scoped?: $scoped")

    internal companion object {
        /** ActiveSupport `Object#present?` for the values a host can pass as a scope (BR-009). */
        fun isPresent(value: Any?): Boolean =
            when (value) {
                null, false -> false
                is CharSequence -> value.isNotBlank()
                is Collection<*> -> value.isNotEmpty()
                is Map<*, *> -> value.isNotEmpty()
                is Array<*> -> value.isNotEmpty()
                else -> true
            }
    }
}
