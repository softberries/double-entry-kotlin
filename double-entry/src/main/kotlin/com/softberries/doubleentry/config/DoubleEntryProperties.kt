package com.softberries.doubleentry.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `double-entry.*` settings (`docs/70-security-and-config.md` §5). The max lengths default to null, i.e. no limit
 * (BR-008); [defaultCurrency] replaces `Money.default_currency` (USD, as in `spec/support/money.rb:2`).
 */
@ConfigurationProperties(prefix = "double-entry")
data class DoubleEntryProperties(
    /** `account_identifier_max_length` (BR-005). */
    val accountIdentifierMaxLength: Int? = null,
    /** `scope_identifier_max_length` (BR-007); checked when an account instance is created. */
    val scopeIdentifierMaxLength: Int? = null,
    /** `code_max_length` (BR-006). */
    val codeMaxLength: Int? = null,
    /** Currency code of accounts defined without a currency (BR-001). */
    val defaultCurrency: String = "USD",
)
