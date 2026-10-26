package com.softberries.doubleentry.balance.cache

import com.softberries.doubleentry.config.DoubleEntrySchemaMigrator
import com.softberries.doubleentry.testing.DoubleEntryPostgres
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.Connection

/**
 * Flyway V2 (`docs/50-data.md` §2, D-005, X-03): migrate to V1, seed duplicate NULL-scope rows as Ruby can leave
 * them (BR-070), migrate to V2. Only the lowest id per (account, scope) survives, whatever balance the others hold,
 * and the index is `UNIQUE NULLS NOT DISTINCT` under the generator's name.
 */
class V2DedupeMigrationTest {
    private fun flyway(
        url: String,
        target: String,
    ): Flyway =
        Flyway
            .configure()
            .dataSource(url, DoubleEntryPostgres.container.username, DoubleEntryPostgres.container.password)
            .locations(DoubleEntrySchemaMigrator.LOCATION)
            .table(DoubleEntrySchemaMigrator.HISTORY_TABLE)
            .target(target)
            .load()

    /** A database at V1 holding [rows] (id, account, scope, balance); returns its URL after migrating to V2. */
    private fun migrateWith(
        database: String,
        rows: List<Row>,
    ): String {
        val url = DoubleEntryPostgres.createDatabase(database)
        flyway(url, "1").migrate()
        DoubleEntryPostgres.connect(url).use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO double_entry_account_balances (id, account, scope, balance, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                ).use { statement ->
                    rows.forEach { row ->
                        statement.setLong(1, row.id)
                        statement.setString(2, row.account)
                        statement.setString(3, row.scope)
                        statement.setLong(4, row.balance)
                        statement.executeUpdate()
                    }
                }
        }
        flyway(url, "latest").migrate().migrationsExecuted shouldBe 1
        return url
    }

    @Test
    fun `V2 keeps only the lowest id of duplicate unscoped rows and leaves scoped rows untouched`() {
        val url =
            migrateWith(
                "v2_dedupe",
                listOf(
                    Row(1, "bank", null, -1000),
                    Row(2, "checking", "1", 500),
                    Row(3, "checking", "2", 0),
                    Row(5, "bank", null, 0),
                    Row(6, "cash", null, 0),
                    Row(7, "bank", null, 0),
                ),
            )

        DoubleEntryPostgres.connect(url).use { connection ->
            connection.rows() shouldContainExactly
                listOf(Row(1, "bank", null, -1000), Row(2, "checking", "1", 500), Row(3, "checking", "2", 0), Row(6, "cash", null, 0))
            connection.indexNullsNotDistinct() shouldBe true
            connection.indexDefinition() shouldBe
                "CREATE UNIQUE INDEX index_account_balances_on_scope_and_account ON public.double_entry_account_balances " +
                "USING btree (scope, account) NULLS NOT DISTINCT"
        }
    }

    @Test
    fun `V2 keeps the lowest id even when a higher-id duplicate holds a non-zero balance`() {
        val url = migrateWith("v2_dedupe_nonzero", listOf(Row(4, "cash", null, 0), Row(9, "cash", null, 2500)))

        DoubleEntryPostgres.connect(url).use { connection ->
            connection.rows() shouldContainExactly listOf(Row(4, "cash", null, 0))
        }
    }

    @Test
    fun `V2 on an empty table only rebuilds the index`() {
        val url = migrateWith("v2_empty", emptyList())

        DoubleEntryPostgres.connect(url).use { connection ->
            connection.rows() shouldContainExactly emptyList()
            connection.indexNullsNotDistinct() shouldBe true
        }
    }

    private data class Row(
        val id: Long,
        val account: String,
        val scope: String?,
        val balance: Long,
    )

    private fun Connection.rows(): List<Row> =
        createStatement().use { statement ->
            statement.executeQuery("SELECT id, account, scope, balance FROM double_entry_account_balances ORDER BY id").use { rs ->
                generateSequence {
                    if (rs.next()) Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)) else null
                }.toList()
            }
        }

    private fun Connection.indexNullsNotDistinct(): Boolean =
        createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT i.indisunique AND i.indnullsnotdistinct FROM pg_index i " +
                        "JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'index_account_balances_on_scope_and_account'",
                ).use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
        }

    private fun Connection.indexDefinition(): String =
        createStatement().use { statement ->
            val sql = "SELECT indexdef FROM pg_indexes WHERE indexname = 'index_account_balances_on_scope_and_account'"
            statement.executeQuery(sql).use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
}
