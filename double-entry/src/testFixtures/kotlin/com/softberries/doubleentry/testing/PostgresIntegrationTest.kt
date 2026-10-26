package com.softberries.doubleentry.testing

import com.softberries.doubleentry.TestDoubleEntryApplication
import com.softberries.doubleentry.config.DoubleEntryTables
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Base class for integration tests: the shared `postgres:17` container, migrated once by the library migrator when
 * the (cached) Spring context starts. Before each test the double_entry tables are truncated with
 * `RESTART IDENTITY`, so ids start at 1 as in the oracle (replaces DatabaseCleaner truncation,
 * `spec/support/database.rb:22-30`), and [clock] is unfrozen.
 */
@SpringBootTest(classes = [TestDoubleEntryApplication::class])
abstract class PostgresIntegrationTest {
    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    protected lateinit var clock: MutableClock

    @BeforeEach
    fun cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE ${DoubleEntryTables.ALL.joinToString(", ")} RESTART IDENTITY")
        clock.reset()
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            val postgres = DoubleEntryPostgres.container
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }
}
