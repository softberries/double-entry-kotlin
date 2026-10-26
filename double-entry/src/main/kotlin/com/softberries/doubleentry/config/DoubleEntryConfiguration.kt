package com.softberries.doubleentry.config

import com.softberries.doubleentry.account.AccountSet
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.transfer.TransferSet

/**
 * The library configuration (`DoubleEntry.config`, `configuration.rb`): account definitions, the transfer allowlist and
 * the max lengths. Unlike Ruby it is immutable once built (D-016, X-09), so build it once at startup with [doubleEntry].
 */
class DoubleEntryConfiguration internal constructor(
    val accounts: AccountSet,
    val transfers: TransferSet,
    val accountIdentifierMaxLength: Int?,
    val scopeIdentifierMaxLength: Int?,
    val codeMaxLength: Int?,
    val defaultCurrency: CurrencyUnit,
) {
    override fun toString(): String =
        "DoubleEntryConfiguration(accounts=$accounts, transfers=$transfers, " +
            "accountIdentifierMaxLength=$accountIdentifierMaxLength, scopeIdentifierMaxLength=$scopeIdentifierMaxLength, " +
            "codeMaxLength=$codeMaxLength, defaultCurrency=$defaultCurrency)"
}

/**
 * Collects the settings and definitions of a [DoubleEntryConfiguration]. Settings start from [DoubleEntryProperties].
 * The `accounts`/`transfers` blocks run when the configuration is built, after all settings are known, so a max length
 * applies to every definition regardless of where it is set in the block.
 */
@DoubleEntryDsl
class DoubleEntryConfigurationBuilder internal constructor(
    properties: DoubleEntryProperties,
) {
    var accountIdentifierMaxLength: Int? = properties.accountIdentifierMaxLength
    var scopeIdentifierMaxLength: Int? = properties.scopeIdentifierMaxLength
    var codeMaxLength: Int? = properties.codeMaxLength
    var defaultCurrency: CurrencyUnit = CurrencyUnit.of(properties.defaultCurrency)

    private val accountBlocks = mutableListOf<AccountSet.Builder.() -> Unit>()
    private val transferBlocks = mutableListOf<TransferSet.Builder.() -> Unit>()

    /** `config.define_accounts { |accounts| … }`; may be called more than once. */
    fun accounts(block: AccountSet.Builder.() -> Unit) {
        accountBlocks += block
    }

    /** `config.define_transfers { |transfers| … }`; may be called more than once. */
    fun transfers(block: TransferSet.Builder.() -> Unit) {
        transferBlocks += block
    }

    internal fun build(): DoubleEntryConfiguration {
        val accountBlocks = accountBlocks.toList()
        val transferBlocks = transferBlocks.toList()
        return DoubleEntryConfiguration(
            accounts = AccountSet.build(accountIdentifierMaxLength, defaultCurrency) { accountBlocks.forEach { it() } },
            transfers = TransferSet.build(codeMaxLength) { transferBlocks.forEach { it() } },
            accountIdentifierMaxLength = accountIdentifierMaxLength,
            scopeIdentifierMaxLength = scopeIdentifierMaxLength,
            codeMaxLength = codeMaxLength,
            defaultCurrency = defaultCurrency,
        )
    }
}

/**
 * Builds the configuration (`DoubleEntry.configure { |config| … }`):
 * ```
 * doubleEntry {
 *     accounts { define("savings", scopeIdentifier = { (it as User).id }, positiveOnly = true) }
 *     transfers { define(from = "bank", to = "savings", code = "deposit") }
 * }
 * ```
 * Definition errors (BR-002, BR-003, BR-005, BR-006) are thrown from this call.
 */
fun doubleEntry(
    properties: DoubleEntryProperties = DoubleEntryProperties.DEFAULTS,
    block: DoubleEntryConfigurationBuilder.() -> Unit = {},
): DoubleEntryConfiguration = DoubleEntryConfigurationBuilder(properties).apply(block).build()

/** Host hook: every [DoubleEntryConfigurer] bean contributes to the configuration bean, in `@Order` order. */
fun interface DoubleEntryConfigurer {
    fun configure(builder: DoubleEntryConfigurationBuilder)
}
