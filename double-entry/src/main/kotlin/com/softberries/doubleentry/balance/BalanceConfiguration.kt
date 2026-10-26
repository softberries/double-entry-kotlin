package com.softberries.doubleentry.balance

import com.softberries.doubleentry.balance.cache.AccountBalanceUpdater
import com.softberries.doubleentry.config.ConfigConfiguration
import jakarta.persistence.EntityManagerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.orm.jpa.SharedEntityManagerCreator
import java.time.Clock

/** The balance feature: [BalanceCalculator] and the cached-balance writer [AccountBalanceUpdater]. */
@Configuration(proxyBeanMethods = false)
class BalanceConfiguration {
    @Bean
    fun doubleEntryBalanceCalculator(entityManagerFactory: EntityManagerFactory): BalanceCalculator =
        BalanceCalculator(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory))

    @Bean
    fun doubleEntryAccountBalanceUpdater(
        entityManagerFactory: EntityManagerFactory,
        @Qualifier(ConfigConfiguration.DOUBLE_ENTRY_CLOCK) clock: Clock,
    ): AccountBalanceUpdater = AccountBalanceUpdater(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory), clock)
}
