package com.softberries.doubleentry.parity.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.softberries.doubleentry.account.AccountDefinition
import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.account.ScopeIdentifier
import com.softberries.doubleentry.config.DefinitionsConfiguration
import com.softberries.doubleentry.config.DoubleEntryConfiguration
import com.softberries.doubleentry.config.DoubleEntryConfigurationBuilder
import com.softberries.doubleentry.config.DoubleEntryConfigurer
import com.softberries.doubleentry.config.DoubleEntryProperties
import com.softberries.doubleentry.config.doubleEntry
import com.softberries.doubleentry.instrumentation.DoubleEntryEvent
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.parity.Comparison
import com.softberries.doubleentry.parity.ExceptionComparator
import com.softberries.doubleentry.parity.FixtureAccount
import com.softberries.doubleentry.parity.FixtureBinder
import com.softberries.doubleentry.parity.FixtureTransfer
import com.softberries.doubleentry.parity.NotificationCapture
import com.softberries.doubleentry.parity.ObservedNotification
import com.softberries.doubleentry.parity.OracleException
import com.softberries.doubleentry.parity.OracleFixtureConfig
import com.softberries.doubleentry.parity.SqlCapture
import com.softberries.doubleentry.transfer.TransferDefinition
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Binds the oracle's `standard_config` ([OracleFixtureConfig], `tools/oracle.rb:43-80`) to the M-005 DSL. Scoped
 * accounts use [OracleFixtureConfig.userScope], which raises `RuntimeException("not a User")` like `USER_SCOPE`.
 */
class DslFixtureBinder(
    private val builder: DoubleEntryConfigurationBuilder,
) : FixtureBinder<DoubleEntryConfigurationBuilder> {
    override fun account(account: FixtureAccount) {
        val userScope: ScopeIdentifier = { OracleFixtureConfig.userScope(it) }
        builder.accounts {
            define(
                account.identifier,
                scopeIdentifier = userScope.takeIf { account.scoped },
                positiveOnly = account.positiveOnly,
                negativeOnly = account.negativeOnly,
                currency = account.currency,
            )
        }
    }

    override fun transfer(transfer: FixtureTransfer) {
        builder.transfers { define(transfer.from, transfer.to, transfer.code) }
    }

    override fun build(): DoubleEntryConfigurationBuilder = builder
}

/**
 * A configuration of its own for one scenario: the standard configuration, then [extra]. Ruby scenarios that change
 * the live configuration (`define_accounts` after `configure`, a max length set afterwards) are expressed this way,
 * because the Kotlin configuration is immutable once built (D-016, X-09).
 */
fun oracleConfiguration(
    properties: DoubleEntryProperties = DoubleEntryProperties.DEFAULTS,
    extra: DoubleEntryConfigurationBuilder.() -> Unit = {},
): DoubleEntryConfiguration =
    doubleEntry(properties) {
        OracleFixtureConfig.bind(DslFixtureBinder(this))
        extra()
    }

/** The resolver the auto-configuration builds from a configuration. */
fun DoubleEntryConfiguration.resolver(): AccountResolver = DefinitionsConfiguration().doubleEntryAccountResolver(this)

/** The Spring context of [ConfigAccountParityTest]: standard configuration, SQL and notification capture. */
@TestConfiguration(proxyBeanMethods = false)
class ConfigAccountParityConfiguration {
    @Bean
    fun oracleStandardConfig(): DoubleEntryConfigurer = DoubleEntryConfigurer { OracleFixtureConfig.bind(DslFixtureBinder(it)) }

    @Bean
    fun configAccountNotificationCapture(): NotificationCapture =
        NotificationCapture { event -> (event as? DoubleEntryEvent)?.let { ObservedNotification(it.name, it.exception.message) } }

    companion object {
        private val capture = SqlCapture()

        @Bean
        @JvmStatic
        fun configAccountSqlCapture(): SqlCapture = capture

        @Bean
        @JvmStatic
        fun configAccountSqlCapturePostProcessor(): BeanPostProcessor = capture.beanPostProcessor()
    }
}

/** What a Kotlin run of a scenario produced: the serialised return value or the thrown exception. */
data class KotlinOutcome(
    val result: JsonNode?,
    val exception: Throwable?,
) {
    companion object {
        fun of(block: () -> Any?): KotlinOutcome =
            try {
                KotlinOutcome(OracleSerializer.ser(block()), null)
            } catch (e: Exception) {
                KotlinOutcome(null, e)
            }
    }
}

/** The result or exception a scenario is expected to give: the oracle's, or the Kotlin expectation of a deviation. */
data class Expected(
    val result: JsonNode? = null,
    val exception: OracleException? = null,
)

/** Kotlin values in the oracle's `ser` format (`tools/oracle.rb:86-102`). */
object OracleSerializer {
    private val nodes = JsonNodeFactory.instance

    fun ser(value: Any?): JsonNode =
        when (value) {
            null -> nodes.nullNode()
            is Boolean -> nodes.booleanNode(value)
            is Int -> nodes.numberNode(value)
            is Long -> nodes.numberNode(value)
            is String -> nodes.textNode(value)
            is CurrencyUnit -> nodes.textNode(value.code)
            is AccountInstance ->
                obj(
                    "account_instance" to value.toString(),
                    "identifier" to value.identifier,
                    "scope_identity" to value.scopeIdentity,
                    "currency" to value.currency.code,
                )
            is AccountDefinition -> obj("account" to value.identifier, "scoped" to value.scoped, "currency" to value.currency.code)
            is TransferDefinition -> obj("transfer" to listOf(value.from, value.to, value.code))
            is Map<*, *> -> nodes.objectNode().apply { value.forEach { (k, v) -> set<JsonNode>(k.toString(), ser(v)) } }
            is Iterable<*> -> nodes.arrayNode().apply { value.forEach { add(ser(it)) } }
            else -> error("no oracle serialisation for ${value::class}")
        }

    fun obj(vararg entries: Pair<String, Any?>): ObjectNode = ser(linkedMapOf(*entries)) as ObjectNode
}

object OutcomeComparator {
    private val RUBY_USER = Regex("#<User:0x[0-9a-f]+>")

    /**
     * Replaces each Ruby `#<User:0x…>` address with the rendering of the oracle user it stands for. The oracle creates
     * users with ids 1, 2, … and every scenario renders user 1 before user 2, so the n-th distinct address in document
     * order is [OracleFixtureConfig.user]`(n)`.
     */
    fun normaliseUsers(node: JsonNode?): JsonNode? {
        if (node == null) return null
        val addresses = linkedSetOf<String>()

        fun collect(n: JsonNode) {
            if (n.isTextual) RUBY_USER.findAll(n.asText()).forEach { addresses += it.value }
            n.elements().forEach(::collect)
        }
        collect(node)
        if (addresses.isEmpty()) return node
        val rendering = addresses.withIndex().associate { (i, address) -> address to OracleFixtureConfig.user(i + 1).toString() }

        fun rewrite(n: JsonNode): JsonNode =
            when {
                n.isTextual -> TextNode(RUBY_USER.replace(n.asText()) { rendering.getValue(it.value) })
                n.isObject -> JsonNodeFactory.instance.objectNode().apply { n.fields().forEach { (k, v) -> set<JsonNode>(k, rewrite(v)) } }
                n.isArray -> JsonNodeFactory.instance.arrayNode().apply { n.forEach { add(rewrite(it)) } }
                else -> n
            }
        return rewrite(node)
    }

    /** Result (after [normaliseUsers]) or exception class and message (exactly, via [ExceptionComparator]). */
    fun compare(
        expected: Expected,
        actual: KotlinOutcome,
    ): Comparison {
        if (expected.exception != null) return ExceptionComparator.compare(expected.exception, actual.exception)
        if (actual.exception != null) {
            return Comparison.of("expected a result but ${actual.exception.javaClass.name} was thrown: ${actual.exception.message}")
        }
        val want = normaliseUsers(expected.result) ?: JsonNodeFactory.instance.nullNode()
        val got = actual.result ?: JsonNodeFactory.instance.nullNode()
        if (want == got) return Comparison.MATCH
        if (!want.isObject || !got.isObject) return Comparison.of("result: expected <$want> but was <$got>")
        val keys = (want.fieldNames().asSequence() + got.fieldNames().asSequence()).distinct()
        return Comparison(
            keys
                .filter { want[it] != got[it] }
                .map { "result.$it: expected <${want[it] ?: "(absent)"}> but was <${got[it] ?: "(absent)"}>" }
                .toList(),
        )
    }
}
