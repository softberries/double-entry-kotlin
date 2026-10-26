package com.softberries.doubleentry.locking

/**
 * Thrown inside a restartable transaction to run it again from the beginning
 * (`ActiveRecord::RestartTransaction`, `lib/active_record/locking_extensions.rb:76`). Internal control flow only,
 * never escapes the library, so no stack trace is captured.
 */
internal class RestartTransactionSignal(
    cause: Throwable? = null,
) : RuntimeException(RUBY_CLASS_NAME, cause, false, false) {
    companion object {
        const val RUBY_CLASS_NAME = "ActiveRecord::RestartTransaction"
    }
}
