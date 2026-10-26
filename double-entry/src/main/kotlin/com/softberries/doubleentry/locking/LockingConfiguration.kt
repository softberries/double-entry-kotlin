package com.softberries.doubleentry.locking

import com.softberries.doubleentry.balance.BalanceCalculator
import com.softberries.doubleentry.balance.cache.AccountBalanceRepository
import com.softberries.doubleentry.config.ConfigConfiguration
import com.softberries.doubleentry.instrumentation.DoubleEntryEventPublisher
import jakarta.persistence.EntityManagerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock

/** The locking feature: [RestartableTransaction] and [BalanceRowCreator] (M-010), [Locking] (M-011). */
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

    /** Defaults to the production setting; a host's test context may declare its own (D-007). */
    @Bean
    @ConditionalOnMissingBean
    fun doubleEntryLockingSettings(): LockingSettings = LockingSettings()

    @Bean
    fun doubleEntryLocking(
        restartable: RestartableTransaction,
        balances: AccountBalanceRepository,
        rowCreator: BalanceRowCreator,
        calculator: BalanceCalculator,
        settings: LockingSettings,
        transactionManager: PlatformTransactionManager,
    ): Locking {
        // Deliberately not a bean: Spring Boot would also register a TransactionExecutionListener bean on its
        // transaction manager (and create a dependency cycle); attached here, it is registered exactly once.
        val depthTracker = TransactionDepthTracker().also { it.attachTo(transactionManager) }
        return Locking(restartable, balances, rowCreator, calculator, settings, depthTracker)
    }
}
