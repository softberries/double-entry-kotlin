package com.softberries.doubleentry.config

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import javax.sql.DataSource

/**
 * Applies the library's own Flyway migrations (`classpath:db/double_entry/migration`) with its own history table
 * [HISTORY_TABLE], so it never interferes with a host application's Flyway (D-003, BR-100).
 *
 * - History table present: plain migrate.
 * - No history table but `double_entry_lines` exists: the schema was created by the Ruby install generator.
 *   It is baselined at version [BASELINE_VERSION] and V1 is not re-applied.
 * - Otherwise (empty schema, or a schema that only holds host tables): migrations run from V1.
 *
 * The explicit check replaces a bare `baselineOnMigrate=true`, which would also baseline a schema that holds only
 * host tables and so skip V1.
 */
class DoubleEntrySchemaMigrator(
    private val dataSource: DataSource,
) : InitializingBean {
    override fun afterPropertiesSet() {
        migrate()
    }

    fun migrate(): MigrateResult {
        val rubyCreated = !tableExists(HISTORY_TABLE) && tableExists(DoubleEntryTables.LINES)
        if (rubyCreated) {
            log.info("double_entry tables found without {}; baselining at version {}", HISTORY_TABLE, BASELINE_VERSION)
        }
        return flyway(baselineVersion = if (rubyCreated) BASELINE_VERSION else EMPTY_BASELINE_VERSION).migrate()
    }

    private fun flyway(baselineVersion: String): Flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations(LOCATION)
            .table(HISTORY_TABLE)
            .baselineOnMigrate(true)
            .baselineVersion(baselineVersion)
            .baselineDescription("double_entry baseline")
            .load()

    private fun tableExists(name: String): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { it.next() && it.getBoolean(1) }
            }
        }

    companion object {
        const val BEAN_NAME = "doubleEntrySchemaMigrator"
        const val LOCATION = "classpath:db/double_entry/migration"
        const val HISTORY_TABLE = "double_entry_schema_history"
        const val BASELINE_VERSION = "1"

        // Below V1: a baseline marker on a host-populated schema still lets V1 run.
        private const val EMPTY_BASELINE_VERSION = "0"
        private val log = LoggerFactory.getLogger(DoubleEntrySchemaMigrator::class.java)
    }
}
