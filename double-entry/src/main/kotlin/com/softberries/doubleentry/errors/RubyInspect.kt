package com.softberries.doubleentry.errors

/**
 * Renders values the way Ruby's `#inspect` does, for error messages that embed Ruby inspect output, e.g.
 * `TransferNotAllowed`: `[:bank, :cash, "salary"]` (`transfer.rb:47`, oracle `transfer_string_code.json`).
 *
 * Kotlin has no Symbol type, so the caller says whether a value was a Symbol or a String in Ruby via [RubyValue].
 */
object RubyInspect {
    /** A value as Ruby would hold it. */
    sealed interface RubyValue {
        /** A Ruby Symbol, rendered `:bank` (or `:"foo bar"` when it is not a plain identifier). */
        data class Sym(
            val name: String,
        ) : RubyValue

        /** A Ruby String, rendered `"salary"`. */
        data class Str(
            val value: String,
        ) : RubyValue

        /** Ruby `nil`. */
        data object Nil : RubyValue

        companion object {
            /** A Symbol, or `nil` for null. */
            fun sym(name: String?): RubyValue = name?.let(::Sym) ?: Nil

            /** A String, or `nil` for null. */
            fun str(value: String?): RubyValue = value?.let(::Str) ?: Nil
        }
    }

    /** `Array#inspect`: `[:bank, :cash, nil]`. */
    fun array(values: List<RubyValue>): String = values.joinToString(prefix = "[", postfix = "]", separator = ", ") { inspect(it) }

    /** `Array#inspect` of values that were all Symbols (or nil): `symbolArray(listOf("bank", "cash", null))` → `[:bank, :cash, nil]`. */
    fun symbolArray(values: List<String?>): String = array(values.map(RubyValue::sym))

    fun inspect(value: RubyValue): String =
        when (value) {
            is RubyValue.Sym -> symbol(value.name)
            is RubyValue.Str -> string(value.value)
            RubyValue.Nil -> "nil"
        }

    /** `Symbol#inspect`. */
    fun symbol(name: String): String = if (isPlainSymbol(name)) ":$name" else ":" + string(name)

    /** `String#inspect` for a UTF-8 string. */
    fun string(value: String): String =
        buildString {
            append('"')
            value.forEachIndexed { i, c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\u000C' -> append("\\f")
                    '\u000B' -> append("\\v")
                    '\b' -> append("\\b")
                    '\u0007' -> append("\\a")
                    '\u001B' -> append("\\e")
                    // Ruby escapes `#` only where it would start an interpolation: `#{`, `#$`, `#@`.
                    '#' -> append(if (value.getOrNull(i + 1) in INTERPOLATION_STARTS) "\\#" else "#")
                    else -> if (c < ' ' || c == '\u007F') append("\\u%04X".format(c.code)) else append(c)
                }
            }
            append('"')
        }

    private val INTERPOLATION_STARTS = setOf('{', '$', '@')

    private const val IDENT = "[A-Za-z_\\u0080-\\uFFFF][A-Za-z0-9_\\u0080-\\uFFFF]*"
    private val PLAIN_SYMBOL =
        Regex("$IDENT[?!=]?|@@?$IDENT|\\$$IDENT|\\$[0-9]+")
    private val OPERATOR_SYMBOLS =
        setOf(
            "+",
            "-",
            "*",
            "/",
            "%",
            "**",
            "==",
            "===",
            "!=",
            "!~",
            "=~",
            "<",
            "<=",
            ">",
            ">=",
            "<=>",
            "<<",
            ">>",
            "!",
            "~",
            "+@",
            "-@",
            "[]",
            "[]=",
            "^",
            "&",
            "|",
            "`",
        )

    private fun isPlainSymbol(name: String): Boolean = name in OPERATOR_SYMBOLS || PLAIN_SYMBOL.matches(name)
}
