package com.softberries.doubleentry.config

import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.errors.UnknownAccount
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.testing.PostgresIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** The library test application (Testcontainers PostgreSQL) exposes the configuration bean with the defaults. */
class DefinitionsConfigurationIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var config: DoubleEntryConfiguration

    @Autowired
    private lateinit var resolver: AccountResolver

    @Autowired
    private lateinit var properties: DoubleEntryProperties

    @Test
    fun `the configuration bean is present with default settings`() {
        properties shouldBe DoubleEntryProperties.DEFAULTS
        config.defaultCurrency shouldBe CurrencyUnit.USD
        config.accountIdentifierMaxLength.shouldBeNull()
        config.scopeIdentifierMaxLength.shouldBeNull()
        config.codeMaxLength.shouldBeNull()
    }

    @Test
    fun `the account resolver bean looks up the configured accounts`() {
        config.accounts.size shouldBe 0

        shouldThrow<UnknownAccount> { resolver.account("nope") }.message shouldBe "account: nope scoped?: false"
    }
}
