package com.softberries.doubleentry.autoconfigure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.softberries.doubleentry.DoubleEntry
import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.balance.BalanceCalculator
import com.softberries.doubleentry.balance.cache.AccountBalanceQueries
import com.softberries.doubleentry.balance.cache.AccountBalanceRepository
import com.softberries.doubleentry.config.ConfigConfiguration
import com.softberries.doubleentry.config.DoubleEntryConfiguration
import com.softberries.doubleentry.config.DoubleEntryConfigurer
import com.softberries.doubleentry.config.DoubleEntrySchemaMigrator
import com.softberries.doubleentry.config.MissingDoubleEntryConfigurationException
import com.softberries.doubleentry.config.doubleEntry
import com.softberries.doubleentry.ledger.LineRepository
import com.softberries.doubleentry.locking.Locking
import com.softberries.doubleentry.locking.LockingSettings
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.testing.DoubleEntryPostgres
import com.softberries.doubleentry.transfer.TransferService
import com.softberries.doubleentry.validation.AccountFixer
import com.softberries.doubleentry.validation.LineCheckService
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.diagnostics.FailureAnalyzer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Properties
import javax.sql.DataSource

/**
 * `DoubleEntryAutoConfiguration` replaces the Railtie and the generator initializer (BR-101, D-001, ID-3): a context
 * with only Boot's JDBC/JPA auto-configurations, the library auto-configuration and the host's definitions is a working
 * ledger. No component, entity or repository scanning of the library packages is involved.
 */
class AutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DataSourceAutoConfiguration::class.java,
                    HibernateJpaAutoConfiguration::class.java,
                    JpaRepositoriesAutoConfiguration::class.java,
                    TransactionAutoConfiguration::class.java,
                    DoubleEntryAutoConfiguration::class.java,
                ),
            ).withPropertyValues(
                "spring.datasource.url=$jdbcUrl",
                "spring.datasource.username=${DoubleEntryPostgres.container.username}",
                "spring.datasource.password=${DoubleEntryPostgres.container.password}",
                "spring.datasource.hikari.maximum-pool-size=2",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.open-in-view=false",
            )

    @Test
    fun `is registered as a Spring Boot auto-configuration`() {
        ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates shouldContain
            DoubleEntryAutoConfiguration::class.java.name
    }

    @Test
    fun `a host with only a DoubleEntryConfiguration bean gets the migrated schema and every library bean`() {
        contextRunner.withUserConfiguration(HostLedger::class.java).run { context ->
            context.startupFailure shouldBe null
            listOf(
                DoubleEntry::class.java,
                LineCheckService::class.java,
                AccountFixer::class.java,
                LineRepository::class.java,
                AccountBalanceRepository::class.java,
                DoubleEntrySchemaMigrator::class.java,
            ).forEach { context.getBeansOfType(it).size shouldBe 1 }
            // The repository and its query fragment implementation.
            context.getBeansOfType(AccountBalanceQueries::class.java).keys shouldContain "accountBalanceRepository"

            val jdbc = JdbcTemplate(context.getBean(DataSource::class.java))
            jdbc.queryForList(
                "SELECT version FROM double_entry_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                String::class.java,
            ) shouldContainExactly listOf("1", "2")

            // The wiring works end to end: a transfer through the facade and its lines through the repository.
            jdbc.execute("TRUNCATE double_entry_lines, double_entry_account_balances, double_entry_line_checks RESTART IDENTITY")
            val doubleEntry = context.getBean(DoubleEntry::class.java)
            doubleEntry.transfer(Money.ofMinor(300, CurrencyUnit.USD), doubleEntry.account("bank"), doubleEntry.account("cash"), "deposit")
            doubleEntry.balance("cash") shouldBe Money.ofMinor(300, CurrencyUnit.USD)
            context.getBean(LineRepository::class.java).findAllByOrderByIdAsc().map { it.id } shouldContainExactly listOf(1L, 2L)
        }
    }

    @Test
    fun `configurer beans are an alternative to a DoubleEntryConfiguration bean`() {
        contextRunner.withUserConfiguration(HostConfigurers::class.java).run { context ->
            context.getBean(DoubleEntryConfiguration::class.java).accounts.map { it.identifier } shouldBe listOf("wallet")
        }
    }

    @Test
    fun `without a DoubleEntryConfiguration bean startup fails with a clear message`() {
        contextRunner.run { context ->
            val failure = context.startupFailure!!
            val cause = generateSequence(failure) { it.cause }.filterIsInstance<MissingDoubleEntryConfigurationException>().single()
            cause.message shouldBe "No DoubleEntryConfiguration bean defined: declare one with doubleEntry { … }"

            val analysis = MissingDoubleEntryConfigurationFailureAnalyzer().analyze(failure)!!
            analysis.description shouldBe "No DoubleEntryConfiguration bean defined: declare one with doubleEntry { … }"
            analysis.action.shouldNotBeBlank()
        }
    }

    @Test
    fun `the failure analyzer is registered in spring factories`() {
        val factories =
            javaClass.classLoader
                .getResources("META-INF/spring.factories")
                .toList()
                .map { url -> Properties().also { props -> url.openStream().use(props::load) } }
        val analyzers = factories.flatMap { it.getProperty(FailureAnalyzer::class.java.name).orEmpty().split(",") }.map { it.trim() }

        analyzers shouldContain MissingDoubleEntryConfigurationFailureAnalyzer::class.java.name
    }

    @Test
    fun `backs off for the beans the host defines itself`() {
        contextRunner.withUserConfiguration(HostLedger::class.java, HostOverrides::class.java).run { context ->
            val overrides = context.getBean(HostOverrides::class.java)
            context.getBean(ConfigConfiguration.DOUBLE_ENTRY_CLOCK, Clock::class.java) shouldBeSameInstanceAs HostOverrides.clock
            context.getBean(LockingSettings::class.java) shouldBe LockingSettings(runningInsideTransactionalFixtures = true)
            context.getBeansOfType(DoubleEntrySchemaMigrator::class.java).keys shouldContainExactly setOf("hostMigrator")
            context.getBean(DoubleEntryConfiguration::class.java) shouldBeSameInstanceAs
                context.getBean(HostLedger::class.java).configuration
            context.getBean(DoubleEntry::class.java) shouldBeSameInstanceAs overrides.hostDoubleEntry
        }
    }

    @Test
    fun `double-entry flyway enabled=false removes the library migrator`() {
        contextRunner
            .withUserConfiguration(HostLedger::class.java)
            .withPropertyValues("double-entry.flyway.enabled=false")
            .run { context -> context.getBeansOfType(DoubleEntrySchemaMigrator::class.java).size shouldBe 0 }
    }

    @Test
    fun `configuration metadata lists the double-entry properties with descriptions`() {
        val metadata: JsonNode =
            javaClass.classLoader.getResourceAsStream("META-INF/spring-configuration-metadata.json").use { ObjectMapper().readTree(it) }
        val properties = metadata["properties"].associateBy { it["name"].asText() }

        properties.keys shouldContainExactlyInAnyOrder
            listOf(
                "double-entry.account-identifier-max-length",
                "double-entry.scope-identifier-max-length",
                "double-entry.code-max-length",
                "double-entry.default-currency",
                "double-entry.flyway.enabled",
            )
        properties.values.forEach { it["description"].asText().shouldNotBeBlank() }
        properties.getValue("double-entry.default-currency")["defaultValue"].asText() shouldBe "USD"
        properties.getValue("double-entry.flyway.enabled")["defaultValue"].asBoolean() shouldBe true
    }

    @Configuration(proxyBeanMethods = false)
    class HostLedger {
        val configuration =
            doubleEntry {
                accounts {
                    define("bank")
                    define("cash")
                }
                transfers { define(from = "bank", to = "cash", code = "deposit") }
            }

        @Bean
        fun hostDoubleEntryConfiguration(): DoubleEntryConfiguration = configuration
    }

    @Configuration(proxyBeanMethods = false)
    class HostConfigurers {
        @Bean
        fun walletConfigurer() = DoubleEntryConfigurer { it.accounts { define("wallet") } }
    }

    @Configuration(proxyBeanMethods = false)
    class HostOverrides {
        lateinit var hostDoubleEntry: DoubleEntry

        @Bean(ConfigConfiguration.DOUBLE_ENTRY_CLOCK)
        fun hostClock(): Clock = clock

        @Bean
        fun hostLockingSettings() = LockingSettings(runningInsideTransactionalFixtures = true)

        @Bean
        fun hostMigrator(dataSource: DataSource) = DoubleEntrySchemaMigrator(dataSource)

        @Bean
        fun hostDoubleEntry(
            resolver: AccountResolver,
            transfers: TransferService,
            calculator: BalanceCalculator,
            locking: Locking,
        ): DoubleEntry = DoubleEntry(resolver, transfers, calculator, locking).also { hostDoubleEntry = it }

        companion object {
            val clock: Clock = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC)
        }
    }

    companion object {
        private val jdbcUrl: String by lazy { DoubleEntryPostgres.createDatabase("double_entry_autoconfig") }
    }
}
