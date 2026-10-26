package com.softberries.doubleentry.balance

import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.orm.jpa.SharedEntityManagerCreator

/** The balance feature: [BalanceCalculator]. */
@Configuration(proxyBeanMethods = false)
class BalanceConfiguration {
    @Bean
    fun doubleEntryBalanceCalculator(entityManagerFactory: EntityManagerFactory): BalanceCalculator =
        BalanceCalculator(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory))
}
