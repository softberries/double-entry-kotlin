package com.softberries.doubleentry.parity.transfer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.softberries.doubleentry.DoubleEntry
import com.softberries.doubleentry.ledger.IdAndCreatedAt
import com.softberries.doubleentry.ledger.Line
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.parity.Comparison
import com.softberries.doubleentry.parity.DbSnapshot
import com.softberries.doubleentry.parity.ExceptionComparator
import com.softberries.doubleentry.parity.OracleException
import com.softberries.doubleentry.parity.OracleScenarios
import com.softberries.doubleentry.parity.RowComparator
import com.softberries.doubleentry.parity.SqlProtocolStep
import com.softberries.doubleentry.parity.config.KotlinOutcome
import com.softberries.doubleentry.parity.config.OracleSerializer
import com.softberries.doubleentry.parity.config.OutcomeComparator
import com.softberries.doubleentry.transfer.TransferResult
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.reflect.full.valueParameters

/**
 * A call the oracle makes that does not compile in Kotlin (D-013): the parameters [DoubleEntry.transfer] requires as
 * non-null values, checked by reflection when the scenario runs, so the claim is verified rather than asserted.
 */
data class NotExpressible(
    val reason: String,
) {
    companion object {
        fun transferRequires(
            vararg parameters: String,
            reason: String,
        ): NotExpressible {
            val byName = DoubleEntry::transfer.valueParameters.associateBy { it.name }
            parameters.forEach { name ->
                val parameter = checkNotNull(byName[name]) { "DoubleEntry.transfer has no parameter $name" }
                check(!parameter.isOptional && !parameter.type.isMarkedNullable) { "$name is optional or nullable: ${parameter.type}" }
            }
            check(byName.getValue("amount").type.classifier == Money::class) { "amount is not Money" }
            return NotExpressible(reason)
        }
    }
}

/**
 * The Kotlin expectation of a deviation (from `docs/decisions.md`). [check] replaces the result/exception comparison
 * where the Kotlin outcome is not an oracle-shaped value; [dbAfter] and [sql] default to the oracle's.
 */
data class KotlinExpectation(
    val result: JsonNode? = null,
    val exception: OracleException? = null,
    val check: ((KotlinOutcome) -> Comparison)? = null,
    val dbAfter: Map<String, List<ObjectNode>>? = null,
    val sql: List<SqlProtocolStep>? = null,
)

/** Kotlin values in the oracle's `ser` format (`tools/oracle.rb:86-102`), extending [OracleSerializer] with ledger types. */
object TransferLineSerializer {
    private val nodes = JsonNodeFactory.instance

    fun outcome(block: () -> Any?): KotlinOutcome =
        try {
            KotlinOutcome(ser(block()), null)
        } catch (e: Exception) {
            KotlinOutcome(null, e)
        }

    fun ser(value: Any?): JsonNode =
        when (value) {
            is Money ->
                obj("money" to linkedMapOf("fractional" to value.minorUnits, "currency" to value.currency.code, "to_s" to value.toString()))
            is Instant -> nodes.textNode(DbSnapshot.formatInstant(value))
            is TransferResult -> ser(listOf(value.credit, value.debit))
            is IdAndCreatedAt -> ser(listOf(value.id, value.createdAt))
            is NotExpressible -> obj("not_expressible" to value.reason)
            is Line -> obj("record" to "DoubleEntry::Line", "attributes" to attributes(value))
            is Map<*, *> -> nodes.objectNode().apply { value.forEach { (k, v) -> set<JsonNode>(k.toString(), ser(v)) } }
            is Iterable<*> -> nodes.arrayNode().apply { value.forEach { add(ser(it)) } }
            else -> OracleSerializer.ser(value)
        }

    private fun obj(vararg entries: Pair<String, Any?>): ObjectNode = ser(linkedMapOf(*entries)) as ObjectNode

    /** The row as ActiveRecord `attributes` (raw minor units, as stored). */
    private fun attributes(line: Line): Map<String, Any?> =
        linkedMapOf(
            "id" to line.id,
            "account" to line.accountIdentifier,
            "scope" to line.scopeIdentity,
            "code" to line.code,
            "amount" to line.amountMinorUnits,
            "balance" to line.balanceMinorUnits,
            "partner_id" to line.partnerId,
            "partner_account" to line.partnerAccountIdentifier,
            "partner_scope" to line.partnerScopeIdentity,
            "detail_type" to line.detail?.type,
            "detail_id" to line.detail?.id,
            "metadata" to line.metadata,
            "created_at" to line.createdAt,
            "updated_at" to line.updatedAt,
        )
}

object TransferLineOutcomes {
    private const val WALL_CLOCK = "<wall-clock>"

    /**
     * Makes a result comparable across runs: drops money `format` (`Money#format` is not library API, see
     * [com.softberries.doubleentry.parity.MoneyComparator]), replaces wall-clock `created_at`/`updated_at` values with a
     * placeholder (their pattern is checked on `db_after` by [RowComparator]; frozen-time values stay exact) and
     * renders Ruby `#<User:0x…>` addresses as [com.softberries.doubleentry.testing.TestUser]s.
     */
    fun normalise(node: JsonNode?): JsonNode? {
        fun rewrite(n: JsonNode): JsonNode =
            when {
                n.isObject ->
                    JsonNodeFactory.instance.objectNode().apply {
                        n.fields().forEach { (k, v) ->
                            when {
                                k == "format" && n.has("fractional") -> Unit
                                k in RowComparator.TIMESTAMP_COLUMNS && isWallClock(v) -> set<JsonNode>(k, TextNode(WALL_CLOCK))
                                else -> set<JsonNode>(k, rewrite(v))
                            }
                        }
                    }
                n.isArray -> JsonNodeFactory.instance.arrayNode().apply { n.forEach { add(rewrite(it)) } }
                else -> n
            }
        return OutcomeComparator.normaliseUsers(node)?.let(::rewrite)
    }

    private fun isWallClock(node: JsonNode): Boolean {
        if (!node.isTextual) return false
        val instant =
            try {
                Instant.parse(node.asText())
            } catch (e: DateTimeParseException) {
                return false
            }
        return Duration.between(RowComparator.T0, instant).abs() > RowComparator.FROZEN_WINDOW
    }

    /** Exception class and message exactly ([ExceptionComparator]), or the normalised result semantically. */
    fun compare(
        result: JsonNode?,
        exception: OracleException?,
        actual: KotlinOutcome,
    ): Comparison {
        if (exception != null) return ExceptionComparator.compare(exception, actual.exception)
        if (actual.exception != null) {
            return Comparison.of("expected a result but ${actual.exception.javaClass.name} was thrown: ${actual.exception.message}")
        }
        val want = normalise(result) ?: JsonNodeFactory.instance.nullNode()
        val got = normalise(actual.result) ?: JsonNodeFactory.instance.nullNode()
        return Comparison.of("result: expected <$want> but was <$got>".takeUnless { RowComparator.semanticEquals(want, got) })
    }

    /** The oracle `db_after` of [id] with [tables] emptied (Kotlin wrote nothing there). */
    fun dbAfterWithout(
        id: String,
        vararg tables: String,
    ): Map<String, List<ObjectNode>> =
        OracleScenarios.load(id).dbAfter.mapValues { (table, rows) -> if (table in tables) emptyList() else rows }
}
