package com.softberries.doubleentry.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * `double-entry.*` settings (`docs/70-security-and-config.md` §5). The max lengths default to null, i.e. no limit
 * (BR-008); [defaultCurrency] replaces `Money.default_currency` (USD, as in `spec/support/money.rb:2`).
 *
 * The constructor deliberately has no Kotlin default values: with them Kotlin adds a second, parameterless constructor
 * and `spring-boot-configuration-processor` can no longer tell which one binds, so it would list no properties. Binding
 * defaults come from `@DefaultValue`; in code start from [DEFAULTS], e.g. `DEFAULTS.copy(defaultCurrency = "EUR")`.
 */
@ConfigurationProperties(prefix = "double-entry")
data class DoubleEntryProperties(
    /** Maximum length of an account identifier, in characters (BR-005). Unset means no limit. */
    val accountIdentifierMaxLength: Int?,
    /** Maximum length of a scope identity, in characters, checked when an account instance is created (BR-007). Unset means no limit. */
    val scopeIdentifierMaxLength: Int?,
    /** Maximum length of a transfer code, in characters (BR-006). Unset means no limit. */
    val codeMaxLength: Int?,
    /** Currency code of accounts defined without a currency (BR-001). */
    @DefaultValue(DEFAULT_CURRENCY) val defaultCurrency: String,
    /** The library's own Flyway migrations. */
    @DefaultValue val flyway: Flyway,
) {
    data class Flyway(
        /**
         * Whether the library applies its migrations (`classpath:db/double_entry/migration`, history table
         * `double_entry_schema_history`) at startup. Disable to manage the schema yourself.
         */
        @DefaultValue("true") val enabled: Boolean,
    )

    companion object {
        const val DEFAULT_CURRENCY = "USD"

        /** The values of an application that sets no `double-entry.*` property. */
        val DEFAULTS =
            DoubleEntryProperties(
                accountIdentifierMaxLength = null,
                scopeIdentifierMaxLength = null,
                codeMaxLength = null,
                defaultCurrency = DEFAULT_CURRENCY,
                flyway = Flyway(enabled = true),
            )
    }
}
