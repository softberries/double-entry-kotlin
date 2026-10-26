package com.softberries.doubleentry.parity

/**
 * Parses the `inspect` form of the values ActiveRecord logs as binds: arrays, double-quoted strings (with Ruby
 * escapes such as `\#`, `\e`, `é`), integers, floats, `true`, `false` and `nil`. It is not JSON: `nil` may
 * appear inside strings and `\#` is not a JSON escape, so a textual JSON conversion would corrupt values.
 */
class RubyLiteral private constructor(
    private val text: String,
) {
    private var pos = 0

    private fun value(): Any? {
        skipSpaces()
        val c = text.getOrNull(pos) ?: error("unexpected end of Ruby literal")
        return when {
            c == '[' -> array()
            c == '"' -> string()
            text.startsWith("nil", pos) -> null.also { pos += 3 }
            text.startsWith("true", pos) -> true.also { pos += 4 }
            text.startsWith("false", pos) -> false.also { pos += 5 }
            c == '-' || c.isDigit() -> number()
            else -> error("unsupported Ruby literal at $pos: ${text.substring(pos).take(20)}")
        }
    }

    private fun array(): List<Any?> {
        pos++
        val items = mutableListOf<Any?>()
        skipSpaces()
        if (text[pos] == ']') return items.also { pos++ }
        while (true) {
            items += value()
            skipSpaces()
            when (text[pos++]) {
                ',' -> continue
                ']' -> return items
                else -> error("expected , or ] at ${pos - 1}")
            }
        }
    }

    private fun string(): String {
        pos++
        val out = StringBuilder()
        while (true) {
            val c = text[pos++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> out.append(escape(text[pos++]))
                else -> out.append(c)
            }
        }
    }

    private fun escape(c: Char): String =
        when (c) {
            'n' -> "\n"
            't' -> "\t"
            'r' -> "\r"
            'e' -> "\u001b"
            '0' -> "\u0000"
            'u' ->
                text
                    .substring(pos, pos + 4)
                    .toInt(16)
                    .toChar()
                    .toString()
                    .also { pos += 4 }
            'x' ->
                text
                    .substring(pos, pos + 2)
                    .toInt(16)
                    .toChar()
                    .toString()
                    .also { pos += 2 }
            else -> c.toString()
        }

    private fun number(): Number {
        val start = pos
        pos++
        while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-_")) pos++
        val literal = text.substring(start, pos).replace("_", "")
        return literal.toLongOrNull() ?: literal.toDouble()
    }

    private fun skipSpaces() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    companion object {
        fun parse(text: String): Any? =
            RubyLiteral(text).run {
                value().also {
                    skipSpaces()
                    check(pos == text.length) { "trailing input in Ruby literal at $pos" }
                }
            }
    }
}
