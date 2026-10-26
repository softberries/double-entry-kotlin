package com.softberries.doubleentry.parity

import com.softberries.doubleentry.testing.DoubleEntryPostgres
import com.softberries.doubleentry.testing.PostgresIntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Connection
import java.sql.Types

/**
 * End-to-end self-test of the harness: the double_entry statements of an oracle `sql` list are replayed through JDBC
 * (wrapped by [SqlCapture]) against the Flyway schema. The JDBC capture then reduces to the same protocol steps as the
 * Ruby log, and [DbSnapshot] of the result matches the oracle `db_after` under [RowComparator].
 */
class SqlCaptureReplayTest : PostgresIntegrationTest() {
    @ParameterizedTest
    @ValueSource(strings = ["lock_order_sql", "transfer_scoped_basic", "transfer_with_metadata", "linecheck_line_error_with_fixer"])
    fun `replaying the Ruby SQL through JDBC gives the same protocol steps and rows`(id: String) {
        val scenario = OracleScenarios.load(id)
        val capture = SqlCapture()
        // stringtype=unspecified lets PostgreSQL type the Ruby bind strings (timestamps, jsonb) by column.
        val postgres = DoubleEntryPostgres.container
        val url = postgres.jdbcUrl + (if ("?" in postgres.jdbcUrl) "&" else "?") + "stringtype=unspecified"
        val target = DriverManagerDataSource(url, postgres.username, postgres.password)

        capture.wrap(target).connection.use { connection -> replay(connection, SqlCapture.rubyStatements(scenario.sql)) }

        capture.protocolSteps shouldContainExactly SqlCapture.protocol(SqlCapture.fromRubyLog(scenario.sql))
        RowComparator.compare(scenario.dbAfter, DbSnapshot.read(target)).assertMatches(id)
        capture.shapes
            .filter { it.forUpdate }
            .map { it.limit }
            .toSet() shouldBe
            SqlCapture
                .fromRubyLog(scenario.sql)
                .filter { it.forUpdate }
                .map { it.limit }
                .toSet()
    }

    private fun replay(
        connection: Connection,
        statements: List<RubyStatement>,
    ) {
        for (statement in statements) {
            val shape = SqlShape.parse(statement.sql, statement.binds)
            when {
                shape.verb == "BEGIN" -> connection.autoCommit = false
                shape.verb == "COMMIT" -> connection.commit().also { connection.autoCommit = true }
                shape.verb == "ROLLBACK" -> connection.rollback().also { connection.autoCommit = true }
                shape.table?.startsWith("double_entry_") == true ->
                    connection.prepareStatement(statement.sql.replace(Regex("\\$\\d+"), "?")).use { ps ->
                        statement.binds.forEachIndexed { i, value ->
                            if (value == null) ps.setNull(i + 1, Types.OTHER) else ps.setObject(i + 1, value)
                        }
                        ps.execute()
                    }
            }
        }
    }
}
