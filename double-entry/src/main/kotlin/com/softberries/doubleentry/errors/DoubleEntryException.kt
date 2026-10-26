package com.softberries.doubleentry.errors

/**
 * Base class of the library errors (`DoubleEntry::DoubleEntryError < RuntimeError`, `lib/double_entry/errors.rb:3`).
 *
 * The exception class and message are the library's error contract (`docs/30-http-api.md` §3). Where Ruby raises
 * without a message, the message is the Ruby class name (e.g. `DoubleEntry::TransferIsNegative`), so consumers that
 * only look at the message see the same text as with the gem.
 */
open class DoubleEntryException internal constructor(
    /** Fully qualified Ruby class name this exception corresponds to; used for parity comparisons. */
    val rubyClassName: String,
    message: String?,
    cause: Throwable?,
) : RuntimeException(message ?: rubyClassName, cause) {
    constructor(message: String? = null, cause: Throwable? = null) : this("DoubleEntry::DoubleEntryError", message, cause)
}

/** `account: <identifier> scoped?: <true|false>` (`account.rb:49`). */
class UnknownAccount(
    message: String? = null,
    cause: Throwable? = null,
) : DoubleEntryException("DoubleEntry::UnknownAccount", message, cause)

/** `account identifier '<id>' is too long. Please limit it to <n> characters.` (`account.rb:150`). */
class AccountIdentifierTooLongError(
    message: String? = null,
    cause: Throwable? = null,
) : DoubleEntryException("DoubleEntry::AccountIdentifierTooLongError", message, cause)

/** `scope identifier '<identity>' is too long. Please limit it to <n> characters.` (`account.rb:135`). */
class ScopeIdentifierTooLongError(
    message: String? = null,
    cause: Throwable? = null,
) : DoubleEntryException("DoubleEntry::ScopeIdentifierTooLongError", message, cause)

/**
 * `[<from>, <to>, <code>]` rendered with [RubyInspect.array], or `from account and to account are identical`
 * (`transfer.rb:47,86`).
 */
class TransferNotAllowed(
    message: String? = null,
    cause: Throwable? = null,
) : DoubleEntryException("DoubleEntry::TransferNotAllowed", message, cause)

/** Raised without a message (`transfer.rb:17`). */
class TransferIsNegative(
    message: String? = null,
    cause: Throwable? = null,
) : DoubleEntryException("DoubleEntry::TransferIsNegative", message, cause)

/** `transfer code '<code>' is too long. Please limit it to <n> characters.` (`transfer.rb:73`). */
class TransferCodeTooLongError(
    message: String? = null,
    cause: Throwable? = null,
) : DoubleEntryException("DoubleEntry::TransferCodeTooLongError", message, cause)

/** Raised without a message (`account.rb:36`). */
class DuplicateAccount(
    message: String? = null,
    cause: Throwable? = null,
) : DoubleEntryException("DoubleEntry::DuplicateAccount", message, cause)

/** Raised without a message (`transfer.rb:34`). */
class DuplicateTransfer(
    message: String? = null,
    cause: Throwable? = null,
) : DoubleEntryException("DoubleEntry::DuplicateTransfer", message, cause)

/** Message is `account.to_s`, e.g. `#{Account account: savings scope:  currency: USD}` (`line.rb:158`). */
class AccountWouldBeSentNegative(
    message: String? = null,
    cause: Throwable? = null,
) : DoubleEntryException("DoubleEntry::AccountWouldBeSentNegative", message, cause)

/** Message is `account.to_s` (`line.rb:161`). Keeps the Ruby `Error` suffix so parity comparators map 1:1. */
class AccountWouldBeSentPositiveError(
    message: String? = null,
    cause: Throwable? = null,
) : DoubleEntryException("DoubleEntry::AccountWouldBeSentPositiveError", message, cause)

/** `Mismatched currency (<to currency> <> <from currency>)` (`transfer.rb:89`). */
class MismatchedCurrencies(
    message: String? = null,
    cause: Throwable? = null,
) : DoubleEntryException("DoubleEntry::MismatchedCurrencies", message, cause)

/** Defined but never raised by the gem (`errors.rb:15`); kept for API parity. */
class MissingAccountError(
    message: String? = null,
    cause: Throwable? = null,
) : DoubleEntryException("DoubleEntry::MissingAccountError", message, cause)
