package com.softberries.doubleentry.config

import com.softberries.doubleentry.TestDoubleEntryApplication
import com.softberries.doubleentry.testing.DoubleEntryPostgres
import com.softberries.doubleentry.testing.MigrationSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.sql.Connection

/**
 * Coexistence with databases created by the Ruby install generator (D-003): the library baselines them at version 1
 * in its own history table and re-applies nothing. Each test boots the library context against its own database.
 */
class BaselineOnExistingRubyDbTest {
    @Test
    fun `a Ruby-created database is baselined at version 1 and V1 is not re-applied`() {
        val url = DoubleEntryPostgres.createDatabase("ruby_created")
        MigrationSpec.applyRubyGeneratedSchema(url)
        DoubleEntryPostgres.connect(url).use { it.update(INSERT_RUBY_LINE) }

        boot(url)

        DoubleEntryPostgres.connect(url).use { connection ->
            connection.history() shouldContainExactly listOf("1:BASELINE:true")
            connection.single("SELECT count(*) FROM double_entry_lines") shouldBe "1"
        }
    }

    @Test
    fun `booting twice keeps the single baseline entry`() {
        val url = DoubleEntryPostgres.createDatabase("ruby_created_twice")
        MigrationSpec.applyRubyGeneratedSchema(url)

        boot(url)
        boot(url)

        DoubleEntryPostgres.connect(url).use { it.history() shouldContainExactly listOf("1:BASELINE:true") }
    }

    @Test
    fun `a schema holding only host tables gets V1 applied`() {
        val url = DoubleEntryPostgres.createDatabase("host_only")
        DoubleEntryPostgres.connect(url).use { it.update("CREATE TABLE host_orders (id bigint PRIMARY KEY)") }

        boot(url)

        DoubleEntryPostgres.connect(url).use { connection ->
            connection.history() shouldContainExactly listOf("0:BASELINE:true", "1:SQL:true")
            connection.single("SELECT to_regclass('double_entry_lines') IS NOT NULL") shouldBe "t"
        }
    }

    private fun boot(url: String) {
        SpringApplicationBuilder(TestDoubleEntryApplication::class.java)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=$url",
                "--spring.datasource.username=${DoubleEntryPostgres.container.username}",
                "--spring.datasource.password=${DoubleEntryPostgres.container.password}",
            ).close()
    }

    private fun Connection.history(): List<String> {
        val sql =
            "SELECT version || ':' || type || ':' || success FROM ${DoubleEntrySchemaMigrator.HISTORY_TABLE} ORDER BY installed_rank"
        return createStatement().use { s ->
            s.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }
    }

    private fun Connection.single(sql: String): String =
        createStatement().use { s ->
            s.executeQuery(sql).use { rs ->
                rs.next()
                rs.getString(1)
            }
        }

    private fun Connection.update(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private companion object {
        const val INSERT_RUBY_LINE =
            """
            INSERT INTO double_entry_lines (account, code, amount, balance, partner_account, created_at, updated_at)
            VALUES ('savings', 'deposit', 100, 100, 'checking', now(), now())
            """
    }
}
