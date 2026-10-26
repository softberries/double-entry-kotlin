package com.softberries.doubleentry.testing

import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/**
 * One `postgres:17` container per JVM, shared by every integration test. Testcontainers itself honours
 * `TESTCONTAINERS_HOST_OVERRIDE` and `TESTCONTAINERS_RYUK_DISABLED` (headless pods).
 */
object DoubleEntryPostgres {
    const val IMAGE = "postgres:17"

    val container: PostgreSQLContainer<*> by lazy { newContainer().also { it.start() } }

    /** A fresh, not yet started container, for tests that must stop their database. */
    fun newContainer(): PostgreSQLContainer<*> =
        PostgreSQLContainer(IMAGE)
            .withDatabaseName("double_entry_test")
            .withUsername("double_entry")
            .withPassword("double_entry")

    /** Creates (or recreates) an empty database in the shared container and returns its JDBC URL. */
    fun createDatabase(name: String): String {
        require(name.matches(Regex("[a-z_][a-z0-9_]*"))) { "invalid database name: $name" }
        connect(container.jdbcUrl).use { connection ->
            connection.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS $name")
                it.execute("CREATE DATABASE $name")
            }
        }
        return jdbcUrl(name)
    }

    fun jdbcUrl(database: String): String = container.jdbcUrl.replace("/${container.databaseName}", "/$database")

    fun connect(jdbcUrl: String): Connection = DriverManager.getConnection(jdbcUrl, container.username, container.password)
}
