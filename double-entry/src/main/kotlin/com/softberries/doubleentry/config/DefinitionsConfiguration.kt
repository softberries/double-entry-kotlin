package com.softberries.doubleentry.config

import com.softberries.doubleentry.account.AccountResolver
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Binds `double-entry.*` and provides the single, immutable [DoubleEntryConfiguration]: the host's own bean if it
 * declares one, else one built from the host's [DoubleEntryConfigurer]s. With neither, startup fails
 * ([MissingDoubleEntryConfigurationException]); an empty ledger is never assumed.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DoubleEntryProperties::class)
class DefinitionsConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun doubleEntryConfiguration(
        properties: DoubleEntryProperties,
        configurers: ObjectProvider<DoubleEntryConfigurer>,
    ): DoubleEntryConfiguration {
        val ordered = configurers.orderedStream().toList()
        if (ordered.isEmpty()) throw MissingDoubleEntryConfigurationException()
        return doubleEntry(properties) { ordered.forEach { it.configure(this) } }
    }

    /** Account lookup (`DoubleEntry.account`) against the configured accounts and scope identifier max length. */
    @Bean
    fun doubleEntryAccountResolver(configuration: DoubleEntryConfiguration): AccountResolver =
        AccountResolver(configuration.accounts, configuration.scopeIdentifierMaxLength)
}

/** The host declared neither a [DoubleEntryConfiguration] nor a [DoubleEntryConfigurer] bean. */
class MissingDoubleEntryConfigurationException :
    IllegalStateException("No DoubleEntryConfiguration bean defined: declare one with doubleEntry { … }")
