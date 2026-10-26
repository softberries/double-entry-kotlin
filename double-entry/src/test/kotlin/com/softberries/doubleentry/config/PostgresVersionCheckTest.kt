package com.softberries.doubleentry.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DatabaseMetaData
import javax.sql.DataSource

/** The migrator fails fast below PostgreSQL 15, before any migration runs (V2 needs NULLS NOT DISTINCT; D-002). */
class PostgresVersionCheckTest {
    private fun dataSource(
        product: String,
        major: Int,
        version: String,
    ): Pair<DataSource, Connection> {
        val metaData =
            mockk<DatabaseMetaData> {
                every { databaseProductName } returns product
                every { databaseMajorVersion } returns major
                every { databaseProductVersion } returns version
            }
        val connection =
            mockk<Connection>(relaxed = true) {
                every { this@mockk.metaData } returns metaData
            }
        return mockk<DataSource> { every { this@mockk.connection } returns connection } to connection
    }

    @Test
    fun `PostgreSQL 14 is rejected with a clear message`() {
        val (dataSource, connection) = dataSource("PostgreSQL", 14, "14.10")

        val error = shouldThrow<IllegalStateException> { DoubleEntrySchemaMigrator(dataSource).migrate() }

        error.message shouldBe
            "double_entry requires PostgreSQL 15 or later (UNIQUE NULLS NOT DISTINCT); connected to PostgreSQL 14.10"
        verify(exactly = 0) { connection.prepareStatement(any()) }
    }

    @Test
    fun `a database other than PostgreSQL is rejected`() {
        val (dataSource, _) = dataSource("H2", 2, "2.3.232")

        shouldThrow<IllegalStateException> { DoubleEntrySchemaMigrator(dataSource).migrate() }.message shouldBe
            "double_entry requires PostgreSQL 15 or later (UNIQUE NULLS NOT DISTINCT); connected to H2 2.3.232"
    }
}
