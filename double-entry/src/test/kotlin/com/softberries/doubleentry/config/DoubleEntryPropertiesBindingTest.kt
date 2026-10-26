package com.softberries.doubleentry.config

import com.softberries.doubleentry.errors.AccountIdentifierTooLongError
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.UnknownCurrencyException
import io.kotest.matchers.collections.shouldExist
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order

/** `double-entry.*` binding (`docs/70-security-and-config.md` §5) and the configuration bean built from configurers. */
class DoubleEntryPropertiesBindingTest {
    private val contextRunner = ApplicationContextRunner().withUserConfiguration(DefinitionsConfiguration::class.java)

    @Test
    fun `defaults are max lengths null, default currency USD`() {
        contextRunner.run { context ->
            val properties = context.getBean(DoubleEntryProperties::class.java)
            properties shouldBe DoubleEntryProperties(null, null, null, "USD")

            val config = context.getBean(DoubleEntryConfiguration::class.java)
            config.accountIdentifierMaxLength.shouldBeNull()
            config.scopeIdentifierMaxLength.shouldBeNull()
            config.codeMaxLength.shouldBeNull()
            config.defaultCurrency shouldBe CurrencyUnit.USD
            config.accounts.size shouldBe 0
            config.transfers.size shouldBe 0
        }
    }

    @Test
    fun `kebab-case properties bind and reach the configuration`() {
        contextRunner
            .withPropertyValues(
                "double-entry.account-identifier-max-length=9",
                "double-entry.scope-identifier-max-length=11",
                "double-entry.code-max-length=10",
                "double-entry.default-currency=btc",
            ).withUserConfiguration(HostDefinitions::class.java)
            .run { context ->
                val config = context.getBean(DoubleEntryConfiguration::class.java)
                config.accountIdentifierMaxLength shouldBe 9
                config.scopeIdentifierMaxLength shouldBe 11
                config.codeMaxLength shouldBe 10
                config.defaultCurrency shouldBe CurrencyUnit.BTC
                config.accounts.map { it.identifier } shouldBe listOf("bank", "cash")
                config.accounts.currencyOf("bank") shouldBe CurrencyUnit.BTC
                config.transfers.find("bank", "cash", "salary")!!.code shouldBe "salary"
            }
    }

    @Test
    fun `a definition error stops the context from starting`() {
        contextRunner
            .withPropertyValues("double-entry.account-identifier-max-length=3")
            .withUserConfiguration(HostDefinitions::class.java)
            .run { context ->
                causes(context.startupFailure).shouldExist { it is AccountIdentifierTooLongError }
            }
    }

    @Test
    fun `an unknown default currency stops the context from starting`() {
        contextRunner.withPropertyValues("double-entry.default-currency=XYZ").run { context ->
            causes(context.startupFailure).shouldExist { it is UnknownCurrencyException }
        }
    }

    private fun causes(failure: Throwable?): List<Throwable> = generateSequence(failure) { it.cause }.toList()

    @Configuration(proxyBeanMethods = false)
    class HostDefinitions {
        @Bean
        @Order(2)
        fun transfersConfigurer() = DoubleEntryConfigurer { it.transfers { define("bank", "cash", "salary") } }

        @Bean
        @Order(1)
        fun accountsConfigurer() =
            DoubleEntryConfigurer {
                it.accounts {
                    define("bank")
                    define("cash")
                }
            }
    }
}
