package com.softberries.doubleentry.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import javax.sql.DataSource

/**
 * Infrastructure shared by every feature: the library clock, the schema migrator and Hibernate settings.
 * M-014 moves the wiring into the real `@AutoConfiguration`.
 */
@Configuration(proxyBeanMethods = false)
class ConfigConfiguration {
    /** Source of `created_at`/`updated_at` (replaces Timecop in the Ruby specs, U-008). */
    @Bean(DOUBLE_ENTRY_CLOCK)
    fun doubleEntryClock(): Clock = Clock.systemUTC()

    /** The library writes timestamps in UTC (BR-035, U-014). */
    @Bean
    fun doubleEntryHibernatePropertiesCustomizer(): HibernatePropertiesCustomizer =
        HibernatePropertiesCustomizer { properties -> properties[HIBERNATE_JDBC_TIME_ZONE] = "UTC" }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "double-entry.flyway", name = ["enabled"], matchIfMissing = true)
    class SchemaMigration {
        @Bean(DoubleEntrySchemaMigrator.BEAN_NAME)
        fun doubleEntrySchemaMigrator(dataSource: DataSource): DoubleEntrySchemaMigrator = DoubleEntrySchemaMigrator(dataSource)

        companion object {
            /** Hibernate validates the schema (`ddl-auto=validate`), so the migration must run first. */
            @Bean
            @JvmStatic
            fun doubleEntryEntityManagerFactoryDependsOnMigrator(): EntityManagerFactoryDependsOnPostProcessor =
                EntityManagerFactoryDependsOnPostProcessor(DoubleEntrySchemaMigrator.BEAN_NAME)
        }
    }

    companion object {
        const val DOUBLE_ENTRY_CLOCK = "doubleEntryClock"
        private const val HIBERNATE_JDBC_TIME_ZONE = "hibernate.jdbc.time_zone"
    }
}
