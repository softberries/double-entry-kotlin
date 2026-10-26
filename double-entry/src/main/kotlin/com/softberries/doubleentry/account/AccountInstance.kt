package com.softberries.doubleentry.account

import com.softberries.doubleentry.errors.ScopeIdentifierTooLongError
import com.softberries.doubleentry.money.CurrencyUnit

/**
 * A concrete account: definition plus scope (`DoubleEntry::Account::Instance`, `account.rb:68-139`). This is the handle
 * passed to transfer, balance and lock. Obtain one through [AccountResolver.account].
 *
 * Equality and [hashCode] use the identifier and [scopeIdentity]. The natural order is the lock order (BR-012, BR-060).
 */
class AccountInstance internal constructor(
    val account: AccountDefinition,
    /** The original scope object (e.g. a `User`), or null. Rendered by [toString] (BR-012, BR-063). */
    val scope: Any?,
    scopeIdentity: String?,
    scopeIdentifierMaxLength: Int?,
) : Comparable<AccountInstance> {
    /**
     * The given `scopeIdentity`, else the resolver result as a String for scoped accounts, else null (BR-010).
     * Always a String, unlike Ruby which keeps a non-String `scope_identity:` as given (X-08, D-014).
     */
    val scopeIdentity: String? = scopeIdentity ?: resolveScopeIdentity()

    val identifier: String get() = account.identifier
    val scopeIdentifier: ScopeIdentifier? get() = account.scopeIdentifier
    val scoped: Boolean get() = account.scoped
    val positiveOnly: Boolean get() = account.positiveOnly
    val negativeOnly: Boolean get() = account.negativeOnly
    val currency: CurrencyUnit get() = account.currency

    init {
        val identity = this.scopeIdentity
        // BR-007: Ruby `String#length` counts characters, hence code points.
        if (identity != null && scopeIdentifierMaxLength != null && identity.codePointLength() > scopeIdentifierMaxLength) {
            throw ScopeIdentifierTooLongError(
                "scope identifier '$identity' is too long. Please limit it to $scopeIdentifierMaxLength characters.",
            )
        }
    }

    /**
     * `call_scope_identifier`: exceptions from the host's resolver propagate unchanged (BR-011); a null result gives
     * `""` (Ruby `nil.to_s`). A scoped instance without a scope object resolves to `""` as Ruby's identity lambda does.
     */
    private fun resolveScopeIdentity(): String? {
        val resolver = account.scopeIdentifier ?: return null
        return scope?.let(resolver)?.toString() ?: ""
    }

    override fun equals(other: Any?): Boolean =
        other is AccountInstance && identifier == other.identifier && scopeIdentity == other.scopeIdentity

    override fun hashCode(): Int = 31 * identifier.hashCode() + (scopeIdentity?.hashCode() ?: 0)

    /**
     * Ruby `[scope_identity.to_s, identifier.to_s] <=>` (BR-012). Ruby compares strings byte-wise (UTF-8), which equals
     * code point order, so the lock order matches Ruby writers even for non-BMP characters (D-017).
     */
    override fun compareTo(other: AccountInstance): Int {
        val byScope = compareCodePoints(scopeIdentity.orEmpty(), other.scopeIdentity.orEmpty())
        return if (byScope != 0) byScope else compareCodePoints(identifier, other.identifier)
    }

    /** `#{Account account: <identifier> scope: <scope> currency: <currency>}` (BR-012); appears in error messages. */
    override fun toString(): String = "#{Account account: $identifier scope: ${scope?.toString().orEmpty()} currency: $currency}"

    private companion object {
        fun String.codePointLength(): Int = codePointCount(0, length)

        fun compareCodePoints(
            a: String,
            b: String,
        ): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a.codePointAt(i)
                val cb = b.codePointAt(j)
                if (ca != cb) return ca.compareTo(cb)
                i += Character.charCount(ca)
                j += Character.charCount(cb)
            }
            return (a.length - i).compareTo(b.length - j)
        }
    }
}
