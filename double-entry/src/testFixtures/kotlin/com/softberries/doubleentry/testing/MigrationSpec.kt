package com.softberries.doubleentry.testing

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText

/** Access to the verbatim specification copy in `migration-spec/` (docs, inventory, oracle). */
object MigrationSpec {
    val dir: Path =
        Paths.get(
            requireNotNull(System.getProperty("doubleentry.migrationSpecDir")) {
                "system property doubleentry.migrationSpecDir is set by the Gradle test task"
            },
        )

    fun read(relative: String): String = dir.resolve(relative).readText()

    /**
     * Applies the pg_dump of the schema the Ruby install generator creates
     * (`inventory/structure_generated_json_metadata_true.sql`) to the database at [jdbcUrl], i.e. produces a
     * "database created by the Ruby generator". psql meta commands (`\restrict`) are dropped.
     */
    fun applyRubyGeneratedSchema(jdbcUrl: String) {
        val sql =
            read("inventory/structure_generated_json_metadata_true.sql")
                .lineSequence()
                .filterNot { it.startsWith("\\") }
                .joinToString("\n")
        DoubleEntryPostgres.connect(jdbcUrl).use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }
}
