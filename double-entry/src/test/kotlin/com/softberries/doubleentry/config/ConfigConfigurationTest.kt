package com.softberries.doubleentry.config

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock

class ConfigConfigurationTest {
    private val configuration = ConfigConfiguration()

    @Test
    fun `default doubleEntryClock is the UTC system clock`() {
        configuration.doubleEntryClock() shouldBe Clock.systemUTC()
    }

    @Test
    fun `Hibernate writes timestamps in UTC`() {
        val properties = mutableMapOf<String, Any>("hibernate.jdbc.time_zone" to "Europe/Warsaw")

        configuration.doubleEntryHibernatePropertiesCustomizer().customize(properties)

        properties["hibernate.jdbc.time_zone"] shouldBe "UTC"
    }
}
