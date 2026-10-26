package com.softberries.doubleentry.samplehost

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * `GET /actuator/health` on the sample host: 200 UP with the database, 503 DOWN once the database is stopped.
 * Uses its own container (not the shared one) because the last test stops it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.datasource.hikari.connection-timeout=2000", "spring.datasource.hikari.validation-timeout=1000"],
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SampleHostHealthTest {
    @Autowired
    private lateinit var rest: TestRestTemplate

    @Autowired
    private lateinit var healthEndpoint: HealthEndpoint

    @Test
    @Order(1)
    fun `health is UP with the database component UP`() {
        val health = rest.getForEntity("/actuator/health", String::class.java)
        health.statusCode shouldBe HttpStatus.OK
        health.body!! shouldEqualJson """{"status":"UP"}"""

        // Components are not shown over HTTP (show-components=never), so the DB component is read from the endpoint.
        healthEndpoint.healthForPath("db")?.status shouldBe Status.UP
    }

    @Test
    @Order(2)
    fun `health is DOWN with 503 when the database is stopped`() {
        postgres.stop()

        val health = rest.getForEntity("/actuator/health", String::class.java)
        health.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
        health.body!! shouldEqualJson """{"status":"DOWN"}"""
        healthEndpoint.healthForPath("db")?.status shouldBe Status.DOWN
    }

    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:17")
                .withDatabaseName("double_entry")
                .withUsername("double_entry")
                .withPassword("double_entry")
                .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }
}
