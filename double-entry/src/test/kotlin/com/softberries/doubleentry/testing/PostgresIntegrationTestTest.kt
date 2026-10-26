package com.softberries.doubleentry.testing

import com.softberries.doubleentry.config.DoubleEntryTables
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.RepeatedTest

/** Every test starts with empty tables and ids at 1, as in the oracle (`oracle/README.md`). */
class PostgresIntegrationTestTest : PostgresIntegrationTest() {
    @RepeatedTest(2)
    fun `tables are truncated and identities restart before each test`() {
        DoubleEntryTables.ALL.forEach { table ->
            jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java) shouldBe 0L
        }

        val id =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO double_entry_account_balances (account, balance, created_at, updated_at)
                VALUES ('savings', 0, now(), now()) RETURNING id
                """.trimIndent(),
                Long::class.java,
            )

        id shouldBe 1L
    }
}
