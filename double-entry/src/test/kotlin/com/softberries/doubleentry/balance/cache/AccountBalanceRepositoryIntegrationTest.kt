package com.softberries.doubleentry.balance.cache

import com.softberries.doubleentry.balance.BalanceIntegrationTest
import com.softberries.doubleentry.config.DoubleEntryTables
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.parity.OracleScenarios
import com.softberries.doubleentry.parity.SqlCapture
import com.softberries.doubleentry.testing.DoubleEntryPostgres
import com.softberries.doubleentry.testing.OracleSample
import com.softberries.doubleentry.testing.TestUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.LocalDateTime

/**
 * M-008: `DoubleEntry::AccountBalance` on a real database. Ports `spec/double_entry/account_balance_spec.rb`
 * [1:2:1:1:1], [1:2:1:2:1], [1:2:1:3:1] and checks `balance_scopes_with_minimum_balance.json` (BR-050) and the
 * `find_by_account` SQL of `lock_order_sql.json`. [1:1:1] (table name) is the `@Table` name, validated by
 * `ddl-auto=validate`, plus `FlywayBaselineSchemaTest`.
 */
class AccountBalanceRepositoryIntegrationTest : BalanceIntegrationTest() {
    @Autowired
    private lateinit var repository: AccountBalanceRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private fun insertBalance(
        account: String,
        scope: String?,
        balance: Long,
    ): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "INSERT INTO ${DoubleEntryTables.ACCOUNT_BALANCES} (account, scope, balance, created_at, updated_at) " +
                    "VALUES (?, ?, ?, now(), now()) RETURNING id",
                Long::class.java,
                account,
                scope,
                balance,
            ),
        )

    // --- find_by_account -------------------------------------------------------------------------------------

    @Test
    fun `findByAccount with lock issues the ActiveRecord FOR UPDATE statement (lock_order_sql)`() {
        insertBalance("bank", null, 0)
        insertBalance("cash", null, 0)
        insertBalance("store", null, 0)
        val checkingId = insertBalance("checking", "1", 0)
        sqlCapture.clear()

        val found =
            transactionTemplate.execute {
                listOf("bank", "cash", "store").map { repository.findByAccount(account(it), lock = true) } +
                    repository.findByAccount(resolver.account("checking", scope = TestUser(1)), lock = true)
            }!!

        found.map { it?.accountIdentifier } shouldContainExactly listOf("bank", "cash", "store", "checking")
        found.last()!!.id shouldBe checkingId
        found.last()!!.scopeIdentity shouldBe "1"
        val oracleLocks =
            SqlCapture
                .fromRubyLog(OracleScenarios.load("lock_order_sql").sql)
                .filter { it.table == DoubleEntryTables.ACCOUNT_BALANCES && it.verb == "SELECT" }
                .take(4)
        val kotlinLocks = sqlCapture.shapes.filter { it.table == DoubleEntryTables.ACCOUNT_BALANCES }
        kotlinLocks shouldContainExactly oracleLocks
        kotlinLocks.last().whereColumns shouldContainExactly listOf("scope", "account")
        kotlinLocks.first().whereColumns shouldContainExactly listOf("scope IS NULL", "account")
        kotlinLocks.all { it.forUpdate && it.limit == 1L && it.orderBy == listOf("id ASC") } shouldBe true
    }

    @Test
    fun `findByAccount without lock has no FOR UPDATE and returns null for a missing row`() {
        insertBalance("checking", "1", 500)
        sqlCapture.clear()

        repository.findByAccount(account("checking", "1")).shouldNotBeNull().balanceMinorUnits shouldBe 500
        repository.findByAccount(account("checking", "2")).shouldBeNull()
        repository.findByAccount(account("bank")).shouldBeNull()

        val shapes = sqlCapture.shapes.filter { it.table == DoubleEntryTables.ACCOUNT_BALANCES }
        shapes.map { it.lock } shouldContainExactly listOf(null, null, null)
        shapes.map { it.whereColumns } shouldContainExactly
            listOf(listOf("scope", "account"), listOf("scope", "account"), listOf("scope IS NULL", "account"))
    }

    @Test
    fun `findByAccount with lock holds a row lock until the transaction ends`() {
        insertBalance("bank", null, 0)

        transactionTemplate.executeWithoutResult {
            repository.findByAccount(account("bank"), lock = true).shouldNotBeNull()
            DoubleEntryPostgres.connect(DoubleEntryPostgres.container.jdbcUrl).use { other ->
                val error =
                    shouldThrow<SQLException> {
                        other.createStatement().use {
                            it.executeQuery("SELECT id FROM double_entry_account_balances WHERE account = 'bank' FOR UPDATE NOWAIT")
                        }
                    }
                error.sqlState shouldBe "55P03"
            }
        }
    }

    @Test
    fun `an unscoped account does not match scoped rows of the same identifier`() {
        insertBalance("checking", "1", 100)

        // An unscoped lookup uses `scope IS NULL`, not "any scope".
        repository.findByAccount(account("bank")).shouldBeNull()
        insertBalance("bank", null, -100)
        repository
            .findByAccount(account("bank"))
            .shouldNotBeNull()
            .scopeIdentity
            .shouldBeNull()
    }

    // --- account / balance / currency accessors ----------------------------------------------------------------

    @Test
    fun `balance is Money in the account's currency (BTC)`() {
        insertBalance("btc_store", null, 10_000)

        val row = repository.findByAccount(account("btc_store")).shouldNotBeNull()

        row.currency(resolver) shouldBe CurrencyUnit.BTC
        row.balance(resolver) shouldBe Money(10_000, CurrencyUnit.BTC)
        row.balance(resolver).toString() shouldBe "0.00010000"
        row.account(resolver) shouldBe account("btc_store")
    }

    @Test
    fun `account reader rebuilds the scoped instance from the stored identity`() {
        insertBalance("checking", "7", 1)

        val account = repository.findByAccount(account("checking", "7")).shouldNotBeNull().account(resolver)

        account.identifier shouldBe "checking"
        account.scopeIdentity shouldBe "7"
        account.currency shouldBe CurrencyUnit.USD
    }

    @Test
    fun `account and balance writers store identifier, scope identity and minor units`() {
        val now = LocalDateTime.of(2026, 1, 15, 10, 0, 0, 123_456_000)
        val id =
            transactionTemplate.execute {
                val entity = AccountBalanceEntity(account("cash"), Money(1, CurrencyUnit.USD), now)
                entity.assignAccount(resolver.account("checking", scope = TestUser(42)))
                entity.assignBalance(Money(-250, CurrencyUnit.USD))
                entityManager.persist(entity)
                entityManager.flush()
                entity.id
            }!!

        val row =
            jdbcTemplate.queryForMap(
                "SELECT account, scope, balance, created_at::text AS created_at FROM double_entry_account_balances WHERE id = ?",
                id,
            )
        row["account"] shouldBe "checking"
        row["scope"] shouldBe "42"
        row["balance"] shouldBe -250L
        row["created_at"] shouldBe "2026-01-15 10:00:00.123456"
        repository.findById(id).shouldNotBeNull().balance(resolver) shouldBe Money(-250, CurrencyUnit.USD)
    }

    // --- scopes_with_minimum_balance_for_account (BR-050) --------------------------------------------------------

    @Test
    fun `BR-050 scopes with minimum balance match the oracle (balance_scopes_with_minimum_balance)`() {
        fixtures.load("balance_scopes_with_minimum_balance")
        val expected = OracleSample.result("balance_scopes_with_minimum_balance")

        fun expected(key: String): List<String?> = expected[key].map { if (it.isNull) null else it.asText() }

        repository.scopesWithMinimumBalanceForAccount(Money(100_00, CurrencyUnit.USD), "checking") shouldContainExactlyInAnyOrder
            expected("min_100")
        repository.scopesWithMinimumBalanceForAccount(Money(99_99, CurrencyUnit.USD), "checking") shouldContainExactlyInAnyOrder
            expected("min_99.99")
        // The currency of the minimum is ignored.
        repository.scopesWithMinimumBalanceForAccount(Money(100_00, CurrencyUnit.BTC), "checking") shouldContainExactlyInAnyOrder
            expected("min_100_BTC_money")
        repository.scopesWithMinimumBalanceForAccount(Money(-1_000_000_00, CurrencyUnit.USD), "bank") shouldContainExactlyInAnyOrder
            expected("unscoped_bank_min_-1000000")
        repository.scopesWithMinimumBalanceForAccount(Money(0, CurrencyUnit.USD), "checking") shouldContainExactlyInAnyOrder
            expected("string_identifier")

        expected("min_100") shouldContainExactly listOf("1")
        expected("min_99.99") shouldContainExactlyInAnyOrder listOf("1", "2")
        expected("unscoped_bank_min_-1000000") shouldContainExactly listOf(null)
        expected("string_identifier") shouldContainExactlyInAnyOrder listOf("1", "2", "3")
    }

    @Test
    fun `BR-050 query has the ActiveRecord shape (account, balance)`() {
        sqlCapture.clear()
        repository.scopesWithMinimumBalanceForAccount(Money(1, CurrencyUnit.USD), "checking")
        val oracle =
            SqlCapture
                .fromRubyLog(OracleScenarios.load("balance_scopes_with_minimum_balance").sql)
                .last { it.table == DoubleEntryTables.ACCOUNT_BALANCES }
        val kotlin = sqlCapture.shapes.single { it.table == DoubleEntryTables.ACCOUNT_BALANCES }
        kotlin.whereColumns shouldContainExactly oracle.whereColumns
        kotlin.lock.shouldBeNull()
    }

    // spec/double_entry/account_balance_spec.rb: a 'checking' account with balance $100 (user id 1)
    private fun checkingWith100(minimum: Long): List<String?> {
        insertBalance("checking", "1", 100_00)
        return repository.scopesWithMinimumBalanceForAccount(Money(minimum, CurrencyUnit.USD), "checking")
    }

    @Test
    fun `AB 1-2-1-1-1 when searching for balance $99 includes the user`() {
        checkingWith100(99_00) shouldContain "1"
    }

    @Test
    fun `AB 1-2-1-2-1 when searching for balance $100 includes the user`() {
        checkingWith100(100_00) shouldContain "1"
    }

    @Test
    fun `AB 1-2-1-3-1 when searching for balance $101 excludes the user`() {
        checkingWith100(101_00) shouldNotContain "1"
    }
}
