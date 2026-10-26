package com.softberries.doubleentry.parity

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.softberries.doubleentry.testing.MigrationSpec
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/** `exception` of an oracle scenario: Ruby class name and message (`tools/oracle.rb` `scenario`). */
data class OracleException(
    val `class`: String,
    val message: String,
    val ancestors: List<String> = emptyList(),
)

/** One `notifications` entry: ActiveSupport event name and the message of its `exception` payload. */
data class OracleNotification(
    val event: String,
    val exception: String?,
)

/**
 * One `migration-spec/oracle/api/json/<id>.json` file: observed Ruby behaviour of one in-process API call.
 * [result] is the serialised return value (`tools/oracle.rb` `ser`), [dbAfter] all rows of the double_entry tables
 * after the call (table → rows ordered by id), [sql] the ActiveRecord log lines.
 */
data class OracleScenario(
    val id: String,
    val description: String,
    val rules: List<String>,
    val result: JsonNode?,
    val exception: OracleException?,
    val dbAfter: Map<String, List<ObjectNode>>,
    val notifications: List<OracleNotification>,
    val sql: List<String>,
    val sqlTruncated: Boolean,
) {
    val raises: Boolean get() = exception != null
}

/** One entry of `oracle/api/json/_index.json`. */
data class OracleIndexEntry(
    val id: String,
    val description: String,
    val rules: List<String>,
    val outcome: String,
    val file: String,
)

/**
 * Loads the json-mode oracle (`migration-spec/oracle/api/json`). The legacy table mode (`oracle/api/table`) is
 * excluded (X-02, D-004). Has no dependency on the library's feature packages.
 */
object OracleScenarios {
    const val DIR = "oracle/api/json"
    private const val INDEX_FILE = "_index.json"

    val mapper: ObjectMapper =
        JsonMapper
            .builder()
            .addModule(kotlinModule())
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

    /** For the harness' own files (deviation lists), whose keys are camelCase. */
    val mapperCamelCase: ObjectMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    /** All scenario ids (file names without `.json`), sorted. */
    val ids: List<String> by lazy {
        MigrationSpec.dir
            .resolve(DIR)
            .listDirectoryEntries("*.json")
            .map { it.name.removeSuffix(".json") }
            .filterNot { it == INDEX_FILE.removeSuffix(".json") }
            .sorted()
    }

    val index: List<OracleIndexEntry> by lazy { mapper.readValue(MigrationSpec.read("$DIR/$INDEX_FILE")) }

    /** Scenarios are cached and shared: `deepCopy()` [OracleScenario.dbAfter] rows before mutating them. */
    private val cache = ConcurrentHashMap<String, OracleScenario>()

    fun load(id: String): OracleScenario =
        cache.computeIfAbsent(id) {
            val text = MigrationSpec.dir.resolve("$DIR/$id.json").readText()
            mapper.readValue<OracleScenario>(text).also { check(it.id == id) { "$id.json declares id ${it.id}" } }
        }

    fun all(): List<OracleScenario> = ids.map(::load)
}
