package com.softberries.doubleentry.samplehost

import com.softberries.doubleentry.config.DoubleEntryConfiguration
import com.softberries.doubleentry.config.DoubleEntryProperties
import com.softberries.doubleentry.config.doubleEntry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** A customer of the sample host; the scope of its per-customer accounts. */
data class Customer(
    val id: Long,
)

/**
 * The sample host's accounts and transfers (the Ruby app's `config/initializers/double_entry.rb`). Passing the bound
 * [DoubleEntryProperties] keeps the `double-entry.*` max lengths and default currency in effect.
 */
@Configuration(proxyBeanMethods = false)
class SampleHostLedger {
    @Bean
    fun doubleEntryConfiguration(properties: DoubleEntryProperties): DoubleEntryConfiguration =
        doubleEntry(properties) {
            accounts {
                define("bank")
                define("checking", scopeIdentifier = { (it as Customer).id })
                define("savings", scopeIdentifier = { (it as Customer).id }, positiveOnly = true)
            }
            transfers {
                define(from = "bank", to = "checking", code = "deposit")
                define(from = "checking", to = "savings", code = "save")
                define(from = "savings", to = "checking", code = "withdraw")
            }
        }
}
