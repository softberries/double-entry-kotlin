package com.softberries.doubleentry.parity

/**
 * Compares an oracle `exception` (Ruby class name and message) with a Kotlin [Throwable].
 *
 * Class: the Ruby class name is mapped to a Kotlin simple class name: the last `::` segment
 * (`DoubleEntry::Locking::LockNotHeld` → `LockNotHeld`) unless [RUBY_TO_KOTLIN] says otherwise. Ruby classes that
 * cannot occur in Kotlin (`TypeError`, `Money::Bank::UnknownRate`, …) map to names no Kotlin class has; such
 * scenarios belong in a deviation file (X-07). The comparison is by name, so the harness needs no feature packages.
 *
 * Message: compared exactly, after replacing the Ruby object address of a scope object (`#<User:0x00007d7f46645508>`)
 * with `toString()` of the Kotlin scope (`TestUser`) passed as [scope].
 */
object ExceptionComparator {
    val RUBY_TO_KOTLIN: Map<String, String> =
        mapOf(
            "RuntimeError" to "RuntimeException",
            "ArgumentError" to "IllegalArgumentException",
        )

    private val RUBY_USER = Regex("#<User:0x[0-9a-f]+>")

    fun kotlinClassName(rubyClass: String): String = RUBY_TO_KOTLIN[rubyClass] ?: rubyClass.substringAfterLast("::")

    /** The oracle message with the Ruby `#<User:0x…>` placeholder replaced by [scope]`.toString()`. */
    fun normalisedMessage(
        expected: OracleException,
        scope: Any? = null,
    ): String = if (scope == null) expected.message else RUBY_USER.replace(expected.message) { scope.toString() }

    fun compare(
        expected: OracleException,
        actual: Throwable?,
        scope: Any? = null,
    ): Comparison {
        if (actual == null) return Comparison.of("expected ${expected.`class`} (${expected.message}) but nothing was thrown")
        val expectedClass = kotlinClassName(expected.`class`)
        val actualClass = actual.javaClass.simpleName
        val expectedMessage = normalisedMessage(expected, scope)
        return Comparison.of(
            "class: expected <$expectedClass> (Ruby ${expected.`class`}) but was <${actual.javaClass.name}>"
                .takeUnless { expectedClass == actualClass },
            "message: expected <$expectedMessage> but was <${actual.message}>".takeUnless { expectedMessage == actual.message },
        )
    }
}
