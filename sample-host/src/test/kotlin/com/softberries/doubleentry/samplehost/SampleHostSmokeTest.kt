package com.softberries.doubleentry.samplehost

import com.softberries.doubleentry.DoubleEntry
import com.softberries.doubleentry.balance.cache.AccountBalanceQueries
import com.softberries.doubleentry.balance.cache.AccountBalanceRepository
import com.softberries.doubleentry.ledger.LineRepository
import com.softberries.doubleentry.locking.errors.LockMustBeOutermostTransaction
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.transfer.TransferResult
import com.softberries.doubleentry.validation.AccountFixer
import com.softberries.doubleentry.validation.LineCheckService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer

/**
 * The sample host depends only on the library and declares a `DoubleEntryConfiguration` bean ([SampleHostLedger]); the
 * auto-configuration does the rest (BR-101): schema migration, the library beans and the `DoubleEntry` facade.
 */
@SpringBootTest
@Import(SampleHostSmokeTest.Payments::class)
class SampleHostSmokeTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var doubleEntry: DoubleEntry

    @Autowired
    private lateinit var payments: Payments

    private fun usd(minor: Long) = Money.ofMinor(minor, CurrencyUnit.USD)

    @Test
    fun `the library migrations are applied in double_entry_schema_history`() {
        jdbc.queryForList(
            "SELECT version FROM double_entry_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
            String::class.java,
        ) shouldContainExactly listOf("1", "2")
    }

    @Test
    fun `the library beans are present`() {
        listOf(
            DoubleEntry::class.java,
            LineCheckService::class.java,
            AccountFixer::class.java,
            LineRepository::class.java,
            AccountBalanceRepository::class.java,
        ).forEach { context.getBeansOfType(it).size shouldBe 1 }
        // The repository and its query fragment implementation.
        context.getBeansOfType(AccountBalanceQueries::class.java).keys shouldContain "accountBalanceRepository"
    }

    @Test
    fun `a transfer through the facade is visible in the balance`() {
        val customer = Customer(7)
        val checking = doubleEntry.account("checking", customer)
        val before = doubleEntry.balance(checking)

        val result: TransferResult = doubleEntry.transfer(usd(300), from = doubleEntry.account("bank"), to = checking, code = "deposit")

        result.debit.account shouldBe checking
        doubleEntry.balance("checking", scope = customer) shouldBe before + usd(300)
    }

    @Test
    fun `a host @Transactional service calling transfer violates the outermost-transaction rule (D-007)`() {
        shouldThrow<LockMustBeOutermostTransaction> { payments.depositInsideHostTransaction(Customer(8), usd(100)) }

        doubleEntry.balance("checking", scope = Customer(8)) shouldBe usd(0)
    }

    /** A host service that wrongly wraps the ledger call in its own transaction. */
    @Service
    class Payments(
        private val doubleEntry: DoubleEntry,
    ) {
        @Transactional
        fun depositInsideHostTransaction(
            customer: Customer,
            amount: Money,
        ): TransferResult =
            doubleEntry.transfer(
                amount,
                from = doubleEntry.account("bank"),
                to = doubleEntry.account("checking", customer),
                code = "deposit",
            )
    }

    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:17")
                .withDatabaseName("double_entry")
                .withUsername("double_entry")
                .withPassword("double_entry")
                .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }
}
