package com.softberries.doubleentry.autoconfigure

import com.softberries.doubleentry.DoubleEntry
import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.balance.BalanceCalculator
import com.softberries.doubleentry.balance.BalanceConfiguration
import com.softberries.doubleentry.config.ConfigConfiguration
import com.softberries.doubleentry.config.DefinitionsConfiguration
import com.softberries.doubleentry.instrumentation.InstrumentationConfiguration
import com.softberries.doubleentry.ledger.LedgerConfiguration
import com.softberries.doubleentry.locking.Locking
import com.softberries.doubleentry.locking.LockingConfiguration
import com.softberries.doubleentry.transfer.TransferConfiguration
import com.softberries.doubleentry.transfer.TransferService
import com.softberries.doubleentry.validation.ValidationConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * Spring Boot auto-configuration of the library; replaces the Railtie and the install generator's initializer
 * (BR-101, D-001, ID-3). A host only adds the dependency and declares its accounts and transfers, either as a
 * `DoubleEntryConfiguration` bean (`@Bean fun ledger() = doubleEntry { … }`) or as `DoubleEntryConfigurer` beans
 * (these also see the `double-entry.*` properties). Without either, startup fails with
 * `No DoubleEntryConfiguration bean defined: declare one with doubleEntry { … }`.
 *
 * It imports every feature configuration, binds `double-entry.*`, provides the `doubleEntryClock`, runs the library's
 * Flyway migrations (`classpath:db/double_entry/migration`, history table `double_entry_schema_history`) and registers
 * the library's JPA entities and Spring Data repositories by adding `com.softberries.doubleentry` to the
 * auto-configuration packages. A host that sets `@EntityScan` or `@EnableJpaRepositories` itself must include that
 * package. Host beans of the same type win: `doubleEntryClock` (by name), `DoubleEntrySchemaMigrator`,
 * `DoubleEntryConfiguration`, `LockingSettings` and [DoubleEntry].
 *
 * Unlike Ruby there is no model-loading step to order: the json-metadata-only line model (D-004) is always present.
 */
@AutoConfiguration(
    after = [DataSourceAutoConfiguration::class],
    // The packages must be registered before Boot reads them for entity and repository scanning.
    before = [HibernateJpaAutoConfiguration::class, JpaRepositoriesAutoConfiguration::class],
)
@AutoConfigurationPackage(basePackages = ["com.softberries.doubleentry"])
@Import(
    ConfigConfiguration::class,
    DefinitionsConfiguration::class,
    InstrumentationConfiguration::class,
    LedgerConfiguration::class,
    BalanceConfiguration::class,
    LockingConfiguration::class,
    TransferConfiguration::class,
    ValidationConfiguration::class,
)
class DoubleEntryAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun doubleEntry(
        resolver: AccountResolver,
        transfers: TransferService,
        calculator: BalanceCalculator,
        locking: Locking,
    ): DoubleEntry = DoubleEntry(resolver, transfers, calculator, locking)
}
