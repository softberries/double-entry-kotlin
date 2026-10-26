package com.softberries.doubleentry.config

import com.softberries.doubleentry.account.AccountResolver
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Binds `double-entry.*` and builds the single, immutable [DoubleEntryConfiguration] from the host's configurers. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DoubleEntryProperties::class)
class DefinitionsConfiguration {
    @Bean
    fun doubleEntryConfiguration(
        properties: DoubleEntryProperties,
        configurers: ObjectProvider<DoubleEntryConfigurer>,
    ): DoubleEntryConfiguration = doubleEntry(properties) { configurers.orderedStream().forEach { it.configure(this) } }

    /** Account lookup (`DoubleEntry.account`) against the configured accounts and scope identifier max length. */
    @Bean
    fun doubleEntryAccountResolver(configuration: DoubleEntryConfiguration): AccountResolver =
        AccountResolver(configuration.accounts, configuration.scopeIdentifierMaxLength)
}
