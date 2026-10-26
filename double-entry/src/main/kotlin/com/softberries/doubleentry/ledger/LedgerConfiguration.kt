package com.softberries.doubleentry.ledger

import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.config.ConfigConfiguration
import jakarta.persistence.EntityManagerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.orm.jpa.SharedEntityManagerCreator
import java.time.Clock

/**
 * The ledger feature: [LineWriter] and [Lines]. [LineEntity] and [LineRepository] are found by the host's JPA and
 * Spring Data scanning of `com.softberries.doubleentry`; M-014's auto-configuration registers them for every host.
 */
@Configuration(proxyBeanMethods = false)
class LedgerConfiguration {
    @Bean
    fun doubleEntryLineWriter(
        entityManagerFactory: EntityManagerFactory,
        resolver: AccountResolver,
        @Qualifier(ConfigConfiguration.DOUBLE_ENTRY_CLOCK) clock: Clock,
    ): LineWriter = LineWriter(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory), resolver, clock)

    @Bean
    fun doubleEntryLines(
        repository: LineRepository,
        resolver: AccountResolver,
    ): Lines = Lines(repository, resolver)
}
