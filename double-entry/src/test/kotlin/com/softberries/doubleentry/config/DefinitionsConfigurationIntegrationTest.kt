package com.softberries.doubleentry.config

import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.testing.PostgresIntegrationTest
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** The library test application (Testcontainers PostgreSQL) exposes the configuration bean with the defaults. */
class DefinitionsConfigurationIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var config: DoubleEntryConfiguration

    @Autowired
    private lateinit var properties: DoubleEntryProperties

    @Test
    fun `the configuration bean is present with default settings`() {
        properties shouldBe DoubleEntryProperties()
        config.defaultCurrency shouldBe CurrencyUnit.USD
        config.accountIdentifierMaxLength.shouldBeNull()
        config.scopeIdentifierMaxLength.shouldBeNull()
        config.codeMaxLength.shouldBeNull()
    }
}
