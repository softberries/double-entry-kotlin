package com.softberries.doubleentry.locking

import com.softberries.doubleentry.locking.errors.LockWaitTimeout
import java.sql.SQLException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Classifies database errors by PostgreSQL SQLSTATE (D-008). Ruby matched the error message text
 * (`/deadlock/i`, `/duplicate/i`, `/lock wait timeout/i`, `locking_extensions.rb:20,48,63`, `locking.rb:46`); the
 * Kotlin port only looks at [SQLException.getSQLState], found anywhere in the cause chain, so Spring
 * `DataAccessException`s, JPA `PersistenceException`s and Hibernate `JDBCException`s all classify the same way.
 */
internal object SqlStateTranslator {
    /** `deadlock detected`. */
    const val DEADLOCK_DETECTED = "40P01"

    /** `unique_violation`. */
    const val UNIQUE_VIOLATION = "23505"

    /** `lock_not_available`, raised when `lock_timeout` expires (or by `NOWAIT`). */
    const val LOCK_NOT_AVAILABLE = "55P03"

    /**
     * The SQLSTATE of the first [SQLException] in the cause chain of [error] that has one (following
     * [SQLException.getNextException] too, for batch errors), or null if there is none.
     */
    fun sqlStateOf(error: Throwable): String? {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is SQLException) {
                generateSequence(current) { it.nextException }
                    .take(MAX_NEXT_EXCEPTIONS)
                    .firstNotNullOfOrNull { it.sqlState }
                    ?.let { return it }
            }
            current = current.cause
        }
        return null
    }

    fun isDeadlock(error: Throwable): Boolean = sqlStateOf(error) == DEADLOCK_DETECTED

    fun isUniqueViolation(error: Throwable): Boolean = sqlStateOf(error) == UNIQUE_VIOLATION

    fun isLockWaitTimeout(error: Throwable): Boolean = sqlStateOf(error) == LOCK_NOT_AVAILABLE

    /**
     * BR-068 (`locking.rb:45-51`): a lock wait timeout becomes [LockWaitTimeout] (message
     * `DoubleEntry::Locking::LockWaitTimeout`, the original error as cause); everything else is returned unchanged.
     */
    fun translate(error: Throwable): Throwable = if (isLockWaitTimeout(error)) LockWaitTimeout(cause = error) else error

    /** Runs [block], rethrowing a lock wait timeout as [LockWaitTimeout] and any other error unchanged (BR-068). */
    inline fun <T> translatingLockWaitTimeout(block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            // Exception, not RuntimeException: Kotlin code may throw a checked SQLException straight from JDBC.
            throw translate(e)
        }

    private const val MAX_NEXT_EXCEPTIONS = 16
}
