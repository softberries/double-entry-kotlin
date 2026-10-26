package com.softberries.doubleentry.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.softberries.doubleentry.testing.DoubleEntryPostgres
import com.softberries.doubleentry.testing.MigrationSpec
import com.softberries.doubleentry.testing.PostgresIntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection

/**
 * The Flyway baseline V1 creates exactly the schema of the Ruby install generator (BR-100, BR-102, D-003):
 * the migrated test database is compared with the generator's pg_dump
 * (`migration-spec/inventory/structure_generated_json_metadata_true.sql`) loaded into a second database.
 *
 * Replaces `spec/generators/double_entry/install/install_generator_spec.rb`:
 * - [1:1:1], [1:2:1] "generates the expected migrations" (without arguments / `--json-metadata`): the three
 *   `create_table` statements, the `metadata` json(b) column and no `double_entry_line_metadata` table are asserted
 *   on the migrated schema.
 * - [1:1:2], [1:2:2] "generates the expected initializer": not ported; there is no initializer, configuration is
 *   Spring properties (M-005/M-014).
 * - [1:3:1], [1:3:2] `--no-json-metadata`: not ported, the legacy metadata table mode is out of scope (X-02, D-004).
 *
 * BR-102 table names, `line_spec.rb` [1:1], [1:2:4], `account_balance_spec.rb` [1:1:1], `line_check_spec.rb`
 * [1:2:3]: asserted on the schema here; entity `@Table` names follow in M-007, M-008 and M-013.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlywayBaselineSchemaTest : PostgresIntegrationTest() {
    private val referenceUrl = DoubleEntryPostgres.jdbcUrl(REFERENCE_DB)

    @BeforeAll
    fun loadRubyReferenceSchema() {
        DoubleEntryPostgres.createDatabase(REFERENCE_DB)
        MigrationSpec.applyRubyGeneratedSchema(referenceUrl)
    }

    @Test
    fun `columns match the Ruby generator schema`() {
        migrated { columns(it) } shouldContainExactly reference { columns(it) }
    }

    @Test
    fun `indexes match the Ruby generator schema except the V2 NULLS NOT DISTINCT index`() {
        val v2Index = "index_account_balances_on_scope_and_account"
        migrated { indexes(it) }.filterNot { it.name == v2Index } shouldContainExactly
            reference { indexes(it) }.filterNot { it.name == v2Index }
        // X-03 (D-005): V2 recreates the generator's index with NULLS NOT DISTINCT; same name and columns.
        migrated { indexes(it) }.single { it.name == v2Index }.definition shouldBe
            reference { indexes(it) }.single { it.name == v2Index }.definition + " NULLS NOT DISTINCT"
    }

    @Test
    fun `creates the three double_entry tables and no metadata table`() {
        migrated { tables(it) } shouldContainExactlyInAnyOrder DoubleEntryTables.ALL
        migrated { tables(it) }.contains("double_entry_line_metadata") shouldBe false
    }

    @Test
    fun `column types and nullability follow docs 50-data`() {
        val columns = migrated { columns(it) }.associateBy { it.table to it.column }

        fun column(
            table: String,
            name: String,
        ) = requireNotNull(columns[table to name]) { "$table.$name missing" }

        column(DoubleEntryTables.LINES, "metadata").let {
            it.type shouldBe "jsonb"
            it.nullable shouldBe true
        }
        DoubleEntryTables.ALL.forEach { table ->
            column(table, "id").let {
                it.type shouldBe "bigint"
                it.nullable shouldBe false
                it.default shouldBe "nextval('${table}_id_seq'::regclass)"
            }
            listOf("created_at", "updated_at").forEach { name ->
                column(table, name).let {
                    it.type shouldBe "timestamp without time zone"
                    it.precision shouldBe 6
                    it.nullable shouldBe false
                }
            }
        }
        listOf("amount", "balance", "partner_id", "detail_id").forEach { column(DoubleEntryTables.LINES, it).type shouldBe "bigint" }
        column(DoubleEntryTables.LINES, "partner_id").nullable shouldBe true
        column(DoubleEntryTables.LINES, "scope").nullable shouldBe true
        column(DoubleEntryTables.LINES, "partner_account").nullable shouldBe false
        column(DoubleEntryTables.ACCOUNT_BALANCES, "balance").type shouldBe "bigint"
        column(DoubleEntryTables.LINE_CHECKS, "last_line_id").nullable shouldBe false
        column(DoubleEntryTables.LINE_CHECKS, "errors_found").type shouldBe "boolean"
        column(DoubleEntryTables.LINE_CHECKS, "log").type shouldBe "text"
    }

    @Test
    fun `creates the generator indexes`() {
        val indexes = migrated { indexes(it) }.associate { it.name to it.definition }
        indexes.keys shouldContainExactlyInAnyOrder
            listOf(
                "double_entry_account_balances_pkey",
                "double_entry_line_checks_pkey",
                "double_entry_lines_pkey",
                "lines_scope_account_id_idx",
                "lines_scope_account_created_at_idx",
                "lines_account_created_at_idx",
                "lines_account_code_created_at_idx",
                "index_account_balances_on_account",
                "index_account_balances_on_scope_and_account",
                "line_checks_created_at_last_line_id_idx",
            )
        // V2 (M-008, X-03): UNIQUE NULLS NOT DISTINCT.
        indexes["index_account_balances_on_scope_and_account"] shouldBe
            "CREATE UNIQUE INDEX index_account_balances_on_scope_and_account ON double_entry_account_balances " +
            "USING btree (scope, account) NULLS NOT DISTINCT"
        indexes["lines_scope_account_id_idx"] shouldBe
            "CREATE INDEX lines_scope_account_id_idx ON double_entry_lines USING btree (scope, account, id)"
    }

    @Test
    fun `has no foreign keys, check constraints or triggers`() {
        migrated { connection ->
            connection.strings(
                """
                SELECT conname FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
                WHERE t.relname LIKE 'double\_entry\_%' AND c.contype IN ('f', 'c', 't', 'x')
                """,
            )
        }.shouldBeEmpty()
        migrated { connection ->
            connection.strings(
                """
                SELECT tgname FROM pg_trigger g JOIN pg_class t ON t.oid = g.tgrelid
                WHERE t.relname LIKE 'double\_entry\_%' AND NOT g.tgisinternal
                """,
            )
        }.shouldBeEmpty()
    }

    @Test
    fun `records V1 and V2 in the library history table`() {
        jdbcTemplate.queryForList(
            "SELECT version || ':' || type || ':' || success FROM ${DoubleEntrySchemaMigrator.HISTORY_TABLE} ORDER BY installed_rank",
            String::class.java,
        ) shouldContainExactly listOf("1:SQL:true", "2:SQL:true")
    }

    @Test
    fun `table names match the oracle config defaults`() {
        val oracle = jacksonObjectMapper().readTree(MigrationSpec.read("oracle/api/json/config_defaults.json"))["result"]
        oracle["tables"].map { it.asText() } shouldContainExactly DoubleEntryTables.ALL
        oracle["table_name_prefix"].asText() shouldBe DoubleEntryTables.PREFIX
        DoubleEntryTables.ALL.forEach { it shouldStartWith DoubleEntryTables.PREFIX }
    }

    private fun <T> migrated(block: (Connection) -> T): T = DoubleEntryPostgres.connect(DoubleEntryPostgres.container.jdbcUrl).use(block)

    private fun <T> reference(block: (Connection) -> T): T = DoubleEntryPostgres.connect(referenceUrl).use(block)

    private data class Column(
        val table: String,
        val column: String,
        val type: String,
        val precision: Int?,
        val nullable: Boolean,
        val default: String?,
    )

    private data class Index(
        val name: String,
        val definition: String,
    )

    private fun tables(connection: Connection): List<String> =
        connection.strings(
            """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name LIKE 'double\_entry\_%' AND table_name <> '${DoubleEntrySchemaMigrator.HISTORY_TABLE}'
            ORDER BY table_name
            """,
        )

    private fun columns(connection: Connection): List<Column> =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    """
                    SELECT table_name, column_name, data_type, datetime_precision, is_nullable, column_default
                    FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name LIKE 'double\_entry\_%'
                      AND table_name <> '${DoubleEntrySchemaMigrator.HISTORY_TABLE}'
                    ORDER BY table_name, ordinal_position
                    """.trimIndent(),
                ).use { rs ->
                    generateSequence {
                        if (rs.next()) {
                            Column(
                                table = rs.getString(1),
                                column = rs.getString(2),
                                type = rs.getString(3),
                                precision = rs.getObject(4) as Int?,
                                nullable = rs.getString(5) == "YES",
                                default = rs.getString(6)?.normalized(),
                            )
                        } else {
                            null
                        }
                    }.toList()
                }
        }

    private fun indexes(connection: Connection): List<Index> =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    """
                    SELECT indexname, indexdef FROM pg_indexes
                    WHERE schemaname = 'public' AND tablename LIKE 'double\_entry\_%'
                      AND tablename <> '${DoubleEntrySchemaMigrator.HISTORY_TABLE}'
                    ORDER BY indexname
                    """.trimIndent(),
                ).use { rs ->
                    generateSequence { if (rs.next()) Index(rs.getString(1), rs.getString(2).normalized()) else null }.toList()
                }
        }

    private fun Connection.strings(sql: String): List<String> =
        createStatement().use { statement ->
            statement.executeQuery(sql.trimIndent()).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }

    /** The dump qualifies names with `public.`, V1 does not; both resolve to the same objects. */
    private fun String.normalized(): String = replace("public.", "")

    private companion object {
        const val REFERENCE_DB = "ruby_generator_reference"
    }
}
