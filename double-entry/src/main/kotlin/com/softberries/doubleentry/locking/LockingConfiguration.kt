package com.softberries.doubleentry.locking

import com.softberries.doubleentry.config.ConfigConfiguration
import com.softberries.doubleentry.instrumentation.DoubleEntryEventPublisher
import jakarta.persistence.EntityManagerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock

/** The locking feature: [RestartableTransaction] and [BalanceRowCreator] (M-010). */
@Configuration(proxyBeanMethods = false)
class LockingConfiguration {
    @Bean
    fun doubleEntryRestartableTransaction(
        transactionManager: PlatformTransactionManager,
        publisher: DoubleEntryEventPublisher,
    ): RestartableTransaction = RestartableTransaction(transactionManager, publisher)

    @Bean
    fun doubleEntryBalanceRowCreator(
        transactionManager: PlatformTransactionManager,
        entityManagerFactory: EntityManagerFactory,
        @Qualifier(ConfigConfiguration.DOUBLE_ENTRY_CLOCK) clock: Clock,
        publisher: DoubleEntryEventPublisher,
    ): BalanceRowCreator =
        BalanceRowCreator(
            transactionManager,
            BalanceRowInserter(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory)),
            clock,
            publisher,
        )
}
