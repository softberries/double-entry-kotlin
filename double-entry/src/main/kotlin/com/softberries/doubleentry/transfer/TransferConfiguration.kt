package com.softberries.doubleentry.transfer

import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.balance.cache.AccountBalanceUpdater
import com.softberries.doubleentry.config.DoubleEntryConfiguration
import com.softberries.doubleentry.ledger.LineWriter
import com.softberries.doubleentry.locking.Locking
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The transfer feature: [TransferService] (M-012). The facade `DoubleEntry.transfer` follows in M-014. */
@Configuration(proxyBeanMethods = false)
class TransferConfiguration {
    @Bean
    fun doubleEntryTransferService(
        configuration: DoubleEntryConfiguration,
        locking: Locking,
        lineWriter: LineWriter,
        balanceUpdater: AccountBalanceUpdater,
        resolver: AccountResolver,
    ): TransferService = TransferService(configuration.transfers, locking, lineWriter, balanceUpdater, resolver)
}
