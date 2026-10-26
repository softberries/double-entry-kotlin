package com.softberries.doubleentry.transfer

import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.balance.cache.AccountBalanceUpdater
import com.softberries.doubleentry.config.ConfigConfiguration
import com.softberries.doubleentry.config.DoubleEntryConfiguration
import com.softberries.doubleentry.ledger.LineWriter
import com.softberries.doubleentry.locking.Locking
import jakarta.persistence.EntityManagerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.orm.jpa.SharedEntityManagerCreator
import java.time.Clock

/** The transfer feature: [TransferService] (M-012). The facade `DoubleEntry.transfer` follows in M-014. */
@Configuration(proxyBeanMethods = false)
class TransferConfiguration {
    @Bean
    fun doubleEntryAccountBalanceUpdater(
        entityManagerFactory: EntityManagerFactory,
        @Qualifier(ConfigConfiguration.DOUBLE_ENTRY_CLOCK) clock: Clock,
    ): AccountBalanceUpdater = AccountBalanceUpdater(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory), clock)

    @Bean
    fun doubleEntryTransferService(
        configuration: DoubleEntryConfiguration,
        locking: Locking,
        lineWriter: LineWriter,
        balanceUpdater: AccountBalanceUpdater,
        resolver: AccountResolver,
    ): TransferService = TransferService(configuration.transfers, locking, lineWriter, balanceUpdater, resolver)
}
