package com.softberries.doubleentry.validation

import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.balance.BalanceCalculator
import com.softberries.doubleentry.balance.cache.AccountBalanceRepository
import com.softberries.doubleentry.balance.cache.AccountBalanceUpdater
import com.softberries.doubleentry.config.ConfigConfiguration
import com.softberries.doubleentry.ledger.LineWriter
import com.softberries.doubleentry.locking.Locking
import jakarta.persistence.EntityManagerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock

/**
 * The validation feature: [LineCheckService] and [AccountFixer] (M-013). Scheduling is the host's job; there is no
 * scheduled job in the library, as in Ruby.
 */
@Configuration(proxyBeanMethods = false)
class ValidationConfiguration {
    @Bean
    fun doubleEntryAccountFixer(
        entityManagerFactory: EntityManagerFactory,
        locking: Locking,
        lineWriter: LineWriter,
        balanceUpdater: AccountBalanceUpdater,
    ): AccountFixer =
        AccountFixer(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory), locking, lineWriter, balanceUpdater)

    @Bean
    fun doubleEntryLineCheckService(
        entityManagerFactory: EntityManagerFactory,
        repository: LineCheckRepository,
        balances: AccountBalanceRepository,
        calculator: BalanceCalculator,
        locking: Locking,
        resolver: AccountResolver,
        transactionManager: PlatformTransactionManager,
        @Qualifier(ConfigConfiguration.DOUBLE_ENTRY_CLOCK) clock: Clock,
    ): LineCheckService =
        LineCheckService(
            SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory),
            repository,
            balances,
            calculator,
            locking,
            resolver,
            transactionManager,
            clock,
        )
}
