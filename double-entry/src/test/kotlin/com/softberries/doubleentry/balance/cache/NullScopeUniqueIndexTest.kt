package com.softberries.doubleentry.balance.cache

import com.softberries.doubleentry.config.DoubleEntryTables
import com.softberries.doubleentry.testing.OracleSample
import com.softberries.doubleentry.testing.PostgresIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import java.sql.SQLException

/**
 * BR-070 / X-03 (D-005): after V2 the unique index `(scope, account)` also covers NULL scope.
 * Ruby on the generator index keeps two `cash` rows (`lock_null_scope_duplicate_balance_rows.json`, result 2);
 * Kotlin keeps one and the second insert fails with SQLSTATE 23505 (`compat_nulls_not_distinct_index.json`).
 * The concurrent variant is in M-015.
 */
class NullScopeUniqueIndexTest : PostgresIntegrationTest() {
    private fun insert(
        account: String,
        scope: String?,
        balance: Long,
    ) {
        jdbcTemplate.update(
            "INSERT INTO ${DoubleEntryTables.ACCOUNT_BALANCES} (account, scope, balance, created_at, updated_at) VALUES (?, ?, ?, now(), now())",
            account,
            scope,
            balance,
        )
    }

    private fun count(account: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ${DoubleEntryTables.ACCOUNT_BALANCES} WHERE account = ?",
            Int::class.java,
            account,
        )!!

    private fun DataIntegrityViolationException.sqlState(): String? =
        generateSequence<Throwable>(this) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    @Test
    fun `a second unscoped row for the same account violates the unique index with 23505`() {
        insert("bank", null, -1000)

        val error = shouldThrow<DuplicateKeyException> { insert("bank", null, 0) }

        error.sqlState() shouldBe "23505"
        val rubyMessage = OracleSample.result("compat_nulls_not_distinct_index")["duplicate_insert_error"].asText()
        val expected = rubyMessage.substringAfter("ERROR:  ")
        expected shouldBe "duplicate key value violates unique constraint \"index_account_balances_on_scope_and_account\""
        error.mostSpecificCause.message!! shouldContain expected
        count("bank") shouldBe 1
    }

    @Test
    fun `lock_null_scope_duplicate_balance_rows keeps one row in Kotlin (Ruby 2, X-03)`() {
        val oracle = OracleSample.json("lock_null_scope_duplicate_balance_rows")
        oracle["result"].asInt() shouldBe 2

        insert("cash", null, 1)
        shouldThrow<DuplicateKeyException> { insert("cash", null, 2) }

        count("cash") shouldBe 1
    }

    @Test
    fun `scoped rows stay unique per scope and distinct scopes coexist`() {
        insert("checking", "1", 0)
        insert("checking", "2", 0)
        insert("checking", null, 0)

        shouldThrow<DuplicateKeyException> { insert("checking", "1", 5) }.sqlState() shouldBe "23505"
        shouldThrow<DuplicateKeyException> { insert("checking", null, 5) }.sqlState() shouldBe "23505"
        count("checking") shouldBe 3
    }
}
