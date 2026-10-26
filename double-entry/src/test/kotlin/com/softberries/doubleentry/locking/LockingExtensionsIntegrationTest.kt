package com.softberries.doubleentry.locking

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.softberries.doubleentry.balance.BalanceIntegrationTest
import com.softberries.doubleentry.balance.BalanceTestConfiguration
import com.softberries.doubleentry.balance.cache.AccountBalanceRepository
import com.softberries.doubleentry.config.ConfigConfiguration
import com.softberries.doubleentry.instrumentation.DoubleEntryEvent
import com.softberries.doubleentry.instrumentation.DoubleEntryEventPublisher
import com.softberries.doubleentry.instrumentation.DoubleEntryEventType
import com.softberries.doubleentry.instrumentation.InstrumentationLogListener
import com.softberries.doubleentry.locking.errors.LockWaitTimeout
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.parity.DbSnapshot
import com.softberries.doubleentry.parity.ExceptionComparator
import com.softberries.doubleentry.parity.NotificationCapture
import com.softberries.doubleentry.parity.ObservedNotification
import com.softberries.doubleentry.parity.OracleException
import com.softberries.doubleentry.parity.OracleScenarios
import com.softberries.doubleentry.parity.RowComparator
import com.softberries.doubleentry.testing.DoubleEntryPostgres
import com.softberries.doubleentry.testing.MutableClock
import com.softberries.doubleentry.testing.TestUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.CannotAcquireLockException
import org.springframework.jdbc.BadSqlGrammarException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Clock
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Records every [DoubleEntryEvent] published in the test context. */
class RecordedDoubleEntryEvents : ApplicationListener<DoubleEntryEvent> {
    private val recorded = CopyOnWriteArrayList<DoubleEntryEvent>()

    val events: List<DoubleEntryEvent> get() = recorded.toList()

    fun clear() = recorded.clear()

    override fun onApplicationEvent(event: DoubleEntryEvent) {
        recorded += event
    }
}

@TestConfiguration(proxyBeanMethods = false)
class LockingTestConfiguration {
    @Bean
    fun recordedDoubleEntryEvents(): RecordedDoubleEntryEvents = RecordedDoubleEntryEvents()
}

/**
 * M-010 on PostgreSQL 17: BR-067 (deadlock restart), BR-068 (lock wait timeout, X-04) and BR-069 (duplicate-tolerant
 * balance-row creation). Deadlocks cannot be produced through the public API because the lock order is consistent
 * (U-016), so they are injected with MockK; duplicates and the lock timeout are real.
 *
 * Ports `spec/active_record/locking_extensions_spec.rb` [1:1:1], [1:2:2:1:1], [1:2:2:1:2], [1:3:1], [1:3:2],
 * [1:3:4:1:1], [1:3:4:1:2] and `spec/double_entry/locking_spec.rb` [1:12:1:1], [1:12:2:1] (the latter with a real lock
 * timeout instead of a stubbed message). Not ported (X-01, D-002: PostgreSQL only): the mysql and sqlite variants
 * [1:2:1:1:1], [1:2:1:1:2], [1:2:3:1:1], [1:2:3:1:2], [1:3:3:1:1], [1:3:3:1:2], [1:3:5:1:1], [1:3:5:1:2].
 *
 * Oracle: `lock_create_ignoring_duplicates_scoped.json`, `lock_wait_timeout_pg.json` (deviation X-04).
 */
@Import(BalanceTestConfiguration::class, LockingTestConfiguration::class)
class LockingExtensionsIntegrationTest : BalanceIntegrationTest() {
    @Autowired
    private lateinit var restartable: RestartableTransaction

    @Autowired
    private lateinit var creator: BalanceRowCreator

    @Autowired
    private lateinit var recorded: RecordedDoubleEntryEvents

    @Autowired
    private lateinit var repository: AccountBalanceRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var publisher: DoubleEntryEventPublisher

    @Autowired
    @Qualifier(ConfigConfiguration.DOUBLE_ENTRY_CLOCK)
    private lateinit var libraryClock: Clock

    private val logger = LoggerFactory.getLogger(InstrumentationLogListener.LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    private val checking1 get() = resolver.account("checking", scope = TestUser(1))

    private fun pgDeadlock() = CannotAcquireLockException("could not execute statement", SQLException("ERROR: deadlock detected", "40P01"))

    private fun balanceRows(): List<Map<String, Any?>> =
        jdbcTemplate.queryForList("SELECT account, scope, balance FROM double_entry_account_balances ORDER BY id")

    private fun infoLines(): List<String> = appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage }

    @BeforeEach
    fun setUp() {
        recorded.clear()
        appender.list.clear()
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
    }

    // --- restartable_transaction / with_restart_on_deadlock (BR-067) -----------------------------------------

    @Test
    fun `LE 1-1-1 run keeps running the block until no restart signal is thrown`() {
        val runs = AtomicInteger()

        val result =
            restartable.run {
                if (runs.incrementAndGet() < 3) throw RestartTransactionSignal()
                "third"
            }

        runs.get() shouldBe 3
        result shouldBe "third"
    }

    @Test
    fun `LE 1-2-2-1-1 a PostgreSQL deadlock while acquiring locks becomes a restart signal`() {
        val deadlock = pgDeadlock()

        val signal = shouldThrow<RestartTransactionSignal> { restartable.withRestartOnDeadlock { throw deadlock } }

        signal.cause shouldBeSameInstanceAs deadlock
    }

    @Test
    fun `LE 1-2-2-1-2 a deadlock publishes deadlock_restart with the exception`() {
        val deadlock = pgDeadlock()

        shouldThrow<RestartTransactionSignal> { restartable.withRestartOnDeadlock { throw deadlock } }

        recorded.events.map { it.name } shouldContainExactly listOf("deadlock_restart.double_entry")
        recorded.events.single().exception shouldBeSameInstanceAs deadlock
    }

    @Test
    fun `a deadlock twice then success runs the block 3 times in fresh transactions, with 2 events and 2 log lines`() {
        val runs = AtomicInteger()

        val result =
            restartable.run {
                // Written in every attempt; only the last attempt's row may survive the restarts.
                jdbcTemplate.update(
                    "INSERT INTO double_entry_account_balances (account, balance, created_at, updated_at) VALUES ('bank', ?, now(), now())",
                    runs.incrementAndGet(),
                )
                restartable.withRestartOnDeadlock {
                    if (runs.get() < 3) throw pgDeadlock()
                    "result ${runs.get()}"
                }
            }

        runs.get() shouldBe 3
        result shouldBe "result 3"
        balanceRows().map { it["balance"] } shouldContainExactly listOf(3L)
        recorded.events.map { it.type } shouldContainExactly List(2) { DoubleEntryEventType.DEADLOCK_RESTART }
        recorded.events.forEach { SqlStateTranslator.sqlStateOf(it.exception) shouldBe "40P01" }
        infoLines() shouldContainExactly List(2) { "Deadlock causing restart" }
    }

    @Test
    fun `a deadlock outside withRestartOnDeadlock is not restarted (BR-067)`() {
        val runs = AtomicInteger()
        val deadlock = pgDeadlock()

        val thrown =
            shouldThrow<CannotAcquireLockException> {
                restartable.run {
                    runs.incrementAndGet()
                    throw deadlock
                }
            }

        thrown shouldBeSameInstanceAs deadlock
        runs.get() shouldBe 1
        recorded.events.shouldBeEmpty()
    }

    @Test
    fun `LK 1-12-1-1 a non-deadlock SQL error (42P01) propagates unchanged without an event`() {
        val runs = AtomicInteger()
        var original: Throwable? = null

        val thrown =
            shouldThrow<BadSqlGrammarException> {
                SqlStateTranslator.translatingLockWaitTimeout {
                    restartable.run {
                        runs.incrementAndGet()
                        restartable.withRestartOnDeadlock {
                            try {
                                jdbcTemplate.queryForList("SELECT * FROM no_such_table")
                            } catch (e: BadSqlGrammarException) {
                                original = e
                                throw e
                            }
                        }
                    }
                }
            }

        thrown shouldBeSameInstanceAs original
        SqlStateTranslator.sqlStateOf(thrown) shouldBe "42P01"
        runs.get() shouldBe 1
        recorded.events.shouldBeEmpty()
        infoLines().shouldBeEmpty()
    }

    // --- lock wait timeout (BR-068, X-04) --------------------------------------------------------------------

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `LK 1-12-2-1 a real PostgreSQL lock timeout becomes LockWaitTimeout (lock_wait_timeout_pg, X-04)`() {
        creator.createIgnoringDuplicates(account("bank"), Money.zero(account("bank").currency)).shouldNotBeNull()
        val oracle = OracleScenarios.load("lock_wait_timeout_pg")

        // Session A holds the row lock.
        DoubleEntryPostgres.connect(DoubleEntryPostgres.container.jdbcUrl).use { sessionA ->
            sessionA.autoCommit = false
            sessionA.createStatement().use {
                it.executeQuery("SELECT id FROM double_entry_account_balances WHERE account = 'bank' FOR UPDATE").use { rows ->
                    rows.next()
                }
            }

            // Session B: the library's connection, with a short lock_timeout for this transaction only.
            val thrown =
                shouldThrow<LockWaitTimeout> {
                    SqlStateTranslator.translatingLockWaitTimeout {
                        TransactionTemplate(transactionManager).execute {
                            entityManager.createNativeQuery("SET LOCAL lock_timeout = '100ms'").executeUpdate()
                            repository.findByAccount(account("bank"), lock = true)
                        }
                    }
                }

            thrown.message shouldBe "DoubleEntry::Locking::LockWaitTimeout"
            SqlStateTranslator.sqlStateOf(thrown.cause!!) shouldBe "55P03"
            sessionA.rollback()
        }

        // X-04: Ruby raised ActiveRecord::LockWaitTimeout; the Kotlin class is the library's LockWaitTimeout.
        oracle.exception!!.`class` shouldBe "ActiveRecord::LockWaitTimeout"
        ExceptionComparator
            .compare(OracleException("DoubleEntry::Locking::LockWaitTimeout", "DoubleEntry::Locking::LockWaitTimeout"), LockWaitTimeout())
            .assertMatches("X-04 Kotlin exception")
        recorded.events.map { it.name } shouldContainExactly oracle.notifications.map { it.event }
        RowComparator.compare(oracle.dbAfter, DbSnapshot.read(jdbcTemplate.dataSource!!)).assertMatches(oracle.id)
    }

    // --- create_ignoring_duplicates! (BR-069) ----------------------------------------------------------------

    @Test
    fun `LE 1-3-1 and 1-3-2 a scoped duplicate is ignored and published (lock_create_ignoring_duplicates_scoped)`() {
        val oracle = OracleScenarios.load("lock_create_ignoring_duplicates_scoped")
        val currency = checking1.currency

        creator.createIgnoringDuplicates(checking1, Money.ofMinor(1, currency)).shouldNotBeNull()
        val second = creator.createIgnoringDuplicates(checking1, Money.ofMinor(2, currency))

        second.shouldBeNull()
        val count =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM double_entry_account_balances WHERE account = 'checking'",
                Long::class.java,
            )
        count shouldBe oracle.result!!.asLong()
        RowComparator.compare(oracle.dbAfter, DbSnapshot.read(jdbcTemplate.dataSource!!)).assertMatches(oracle.id)

        recorded.events shouldHaveSize 1
        val event = recorded.events.single()
        event.type shouldBe DoubleEntryEventType.DUPLICATE_IGNORE
        SqlStateTranslator.sqlStateOf(event.exception) shouldBe "23505"
        NotificationCapture
            .compare(oracle.notifications, recorded.events.map { ObservedNotification(it.name, it.exception.message) })
            .assertMatches("${oracle.id} notifications")
        infoLines() shouldContainExactly listOf("Duplicate ignored")
    }

    @Test
    fun `an unscoped duplicate is ignored too after V2 (X-03)`() {
        val bank = account("bank")

        creator.createIgnoringDuplicates(bank, Money.ofMinor(5, bank.currency)).shouldNotBeNull()
        creator.createIgnoringDuplicates(bank, Money.ofMinor(7, bank.currency)).shouldBeNull()

        balanceRows() shouldContainExactly listOf(mapOf("account" to "bank", "scope" to null, "balance" to 5L))
        recorded.events.map { it.type } shouldContainExactly listOf(DoubleEntryEventType.DUPLICATE_IGNORE)
        SqlStateTranslator.sqlStateOf(recorded.events.single().exception) shouldBe "23505"
    }

    @Test
    fun `the created row has the account, scope, balance and library clock timestamps`() {
        clock.set(MutableClock.ORACLE_T0)

        val row = creator.createIgnoringDuplicates(checking1, Money.ofMinor(42, checking1.currency)).shouldNotBeNull()

        row.id.shouldNotBeNull()
        val stored = repository.findById(row.id!!).shouldNotBeNull()
        stored.accountIdentifier shouldBe "checking"
        stored.scopeIdentity shouldBe "1"
        stored.balanceMinorUnits shouldBe 42L
        stored.createdAt shouldBe LocalDateTime.of(2026, 1, 15, 10, 0)
        stored.updatedAt shouldBe stored.createdAt
        recorded.events.shouldBeEmpty()
    }

    @Test
    fun `LE 1-3-4-1-1 a deadlock on insert is retried and the row created`() {
        val inserter = spyk(BalanceRowInserter(entityManager))
        every { inserter.insert(any()) } throws pgDeadlock() andThenAnswer { callOriginal() }

        val row = creatorWith(inserter).createIgnoringDuplicates(checking1, Money.ofMinor(1, checking1.currency))

        row.shouldNotBeNull()
        verify(exactly = 2) { inserter.insert(any()) }
        balanceRows() shouldContainExactly listOf(mapOf("account" to "checking", "scope" to "1", "balance" to 1L))
        recorded.events.map { it.name } shouldContainExactly listOf("deadlock_retry.double_entry")
        infoLines() shouldContainExactly listOf("Deadlock causing retry")
    }

    @Test
    fun `LE 1-3-4-1-2 every deadlock retry publishes a notification`() {
        val deadlock = pgDeadlock()
        val inserter = spyk(BalanceRowInserter(entityManager))
        every { inserter.insert(any()) } throws deadlock andThenThrows deadlock andThenAnswer { callOriginal() }

        creatorWith(inserter).createIgnoringDuplicates(checking1, Money.ofMinor(1, checking1.currency)).shouldNotBeNull()

        verify(exactly = 3) { inserter.insert(any()) }
        recorded.events.map { it.type } shouldContainExactly List(2) { DoubleEntryEventType.DEADLOCK_RETRY }
        recorded.events.forEach { it.exception shouldBeSameInstanceAs deadlock }
        balanceRows() shouldHaveSize 1
    }

    @Test
    fun `any other insert error propagates unchanged without an event`() {
        val error = BadSqlGrammarException("insert", "INSERT", SQLException("relation does not exist", "42P01"))
        val inserter = spyk(BalanceRowInserter(entityManager))
        every { inserter.insert(any()) } throws error

        val thrown =
            shouldThrow<BadSqlGrammarException> {
                creatorWith(inserter).createIgnoringDuplicates(checking1, Money.ofMinor(1, checking1.currency))
            }

        thrown shouldBeSameInstanceAs error
        verify(exactly = 1) { inserter.insert(any()) }
        recorded.events.shouldBeEmpty()
        balanceRows().shouldBeEmpty()
    }

    @Test
    fun `the row is committed in its own transaction even when the caller's transaction rolls back`() {
        TransactionTemplate(transactionManager).executeWithoutResult { status ->
            creator.createIgnoringDuplicates(account("cash"), Money.zero(account("cash").currency)).shouldNotBeNull()
            status.setRollbackOnly()
        }

        balanceRows().map { it["account"] } shouldContainExactly listOf("cash")
    }

    private fun creatorWith(inserter: BalanceRowInserter) = BalanceRowCreator(transactionManager, inserter, libraryClock, publisher)
}
