package com.softberries.doubleentry.testing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** An `oracle/api/json/<id>.json` sample (observed Ruby behaviour). */
object OracleSample {
    private val mapper = jacksonObjectMapper()

    fun json(id: String): JsonNode = mapper.readTree(MigrationSpec.read("oracle/api/json/$id.json"))

    /** The `exception` object of a sample; fails if the sample has none. */
    fun exception(id: String): JsonNode = json(id)["exception"].also { require(!it.isNull) { "$id has no exception" } }

    /** The `result` object of a sample. */
    fun result(id: String): JsonNode = json(id)["result"]
}
