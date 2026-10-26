package com.softberries.doubleentry.locking.errors

/**
 * Locking errors (`lib/double_entry/locking.rb:174-188`). As in Ruby they extend `RuntimeError` directly and are
 * **not** [com.softberries.doubleentry.errors.DoubleEntryException]s. Without an explicit message the message is the
 * Ruby class name (`docs/30-http-api.md` §3).
 */
sealed class LockingException(
    /** Fully qualified Ruby class name this exception corresponds to; used for parity comparisons. */
    val rubyClassName: String,
    message: String?,
    cause: Throwable?,
) : RuntimeException(message ?: rubyClassName, cause)

/** `lock_accounts` called inside an existing transaction (`locking.rb:118`). */
class LockMustBeOutermostTransaction(
    message: String? = null,
    cause: Throwable? = null,
) : LockingException("DoubleEntry::Locking::LockMustBeOutermostTransaction", message, cause)

/** Transfer on an account that is not locked: `No lock held for account: <identifier>, scope <scope>` (`locking.rb:84`). */
class LockNotHeld(
    message: String? = null,
    cause: Throwable? = null,
) : LockingException("DoubleEntry::Locking::LockNotHeld", message, cause)

/** "Should never happen" (`locking.rb:72`). */
class LockDisaster(
    message: String? = null,
    cause: Throwable? = null,
) : LockingException("DoubleEntry::Locking::LockDisaster", message, cause)

/** Waiting for locks timed out (`locking.rb:47`). */
class LockWaitTimeout(
    message: String? = null,
    cause: Throwable? = null,
) : LockingException("DoubleEntry::Locking::LockWaitTimeout", message, cause)
