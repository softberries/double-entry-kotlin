package com.softberries.doubleentry.transfer

import com.softberries.doubleentry.config.DoubleEntryDsl
import com.softberries.doubleentry.errors.DuplicateTransfer
import com.softberries.doubleentry.errors.RubyInspect
import com.softberries.doubleentry.errors.TransferCodeTooLongError
import com.softberries.doubleentry.errors.TransferNotAllowed
import java.util.Collections

/**
 * The transfer allowlist (`DoubleEntry::Transfer::Set`, `transfer.rb:26-64`), keyed by `[from, to, code]`, in
 * definition order. Immutable once built (D-016, X-09).
 */
class TransferSet private constructor(
    private val byKey: Map<TransferDefinition, TransferDefinition>,
) : Iterable<TransferDefinition> {
    /** All definitions in definition order (`Set#all`). Read-only. */
    val all: List<TransferDefinition> = Collections.unmodifiableList(byKey.values.toList())

    val size: Int get() = byKey.size

    override fun iterator(): Iterator<TransferDefinition> = all.iterator()

    /** The definition for these account identifiers and code, or null (`Set#find`). Codes compare exactly. */
    fun find(
        from: String,
        to: String,
        code: String,
    ): TransferDefinition? = byKey[TransferDefinition(from, to, code)]

    /**
     * Like [find] but throws when the transfer is not allowed (`Set#find!`, BR-020).
     *
     * @throws TransferNotAllowed with the Ruby `[:from, :to, :code].inspect` message.
     */
    fun findOrThrow(
        from: String,
        to: String,
        code: String,
    ): TransferDefinition = find(from, to, code) ?: throw TransferNotAllowed(RubyInspect.symbolArray(listOf(from, to, code)))

    override fun toString(): String = "TransferSet(${all.map { listOf(it.from, it.to, it.code) }})"

    /** Collects definitions (`Set#define`) and validates each one as it is defined. */
    @DoubleEntryDsl
    class Builder internal constructor(
        private val codeMaxLength: Int?,
    ) {
        private val definitions = LinkedHashMap<TransferDefinition, TransferDefinition>()

        /**
         * Allows transfers from [from] to [to] with [code]. Unknown account identifiers are accepted (BR-004).
         *
         * @throws TransferCodeTooLongError if [code] is longer than the configured maximum (BR-006).
         * @throws DuplicateTransfer if `[from, to, code]` is already defined (BR-003).
         */
        fun define(
            from: String,
            to: String,
            code: String,
        ): TransferDefinition {
            // BR-006: Ruby `String#length` counts characters, hence code points rather than UTF-16 units.
            if (codeMaxLength != null && code.codePointCount(0, code.length) > codeMaxLength) {
                throw TransferCodeTooLongError("transfer code '$code' is too long. Please limit it to $codeMaxLength characters.")
            }
            val definition = TransferDefinition(from, to, code)
            if (definition in definitions) throw DuplicateTransfer()
            definitions[definition] = definition
            return definition
        }

        internal fun build(): TransferSet = TransferSet(LinkedHashMap(definitions))
    }

    companion object {
        /** Builds a standalone set. */
        fun build(
            codeMaxLength: Int? = null,
            block: Builder.() -> Unit,
        ): TransferSet = Builder(codeMaxLength).apply(block).build()
    }
}
