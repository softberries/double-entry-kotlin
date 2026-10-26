package com.softberries.doubleentry.publication

import com.softberries.doubleentry.account.ScopeIdentifier
import com.softberries.doubleentry.config.DoubleEntryProperties
import com.softberries.doubleentry.config.doubleEntry
import com.softberries.doubleentry.money.CurrencyUnit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

/** M-017: `docs/host-guide.md` covers what the issue lists, and its configuration example works as documented. */
class HostGuideTest {
    private val guide = File(System.getProperty("doubleentry.repoRoot"), "docs/host-guide.md").readText()

    /** The `## N.` section of the guide, up to the next one. */
    private fun section(number: Int): String {
        val start = guide.indexOf("\n## $number. ")
        start shouldNotBe -1
        val end = guide.indexOf("\n## ${number + 1}. ", start).takeIf { it >= 0 } ?: guide.length
        return guide.substring(start, end)
    }

    @Nested
    inner class Coverage {
        @Test
        fun `1 Gradle dependency`() {
            section(1) shouldContain "implementation(\"com.softberries:double-entry:"
            section(1) shouldContain "https://maven.pkg.github.com/softberries/double-entry-kotlin"
        }

        @Test
        fun `2 doubleEntry DSL side by side with the Ruby initializer of README 203-251`() {
            val s = section(2)
            s shouldContain "README.md:203-251"
            s shouldContain "DoubleEntry.configure do |config|"
            s shouldContain "config.define_accounts do |accounts|"
            s shouldContain "transfers.define(from: :checking, to: :savings,"
            s shouldContain "doubleEntry(properties) {"
            s shouldContain "accounts {"
            s shouldContain "transfers {"
            s shouldContain "DoubleEntryConfigurer"
        }

        @Test
        fun `3 double-entry properties`() {
            val s = section(3)
            listOf(
                "double-entry.account-identifier-max-length",
                "double-entry.scope-identifier-max-length",
                "double-entry.code-max-length",
                "double-entry.default-currency",
                "double-entry.flyway.enabled",
            ).forEach { s shouldContain "`$it`" }
        }

        @Test
        fun `4 Flyway coexistence and baselineOnMigrate on existing databases`() {
            val s = section(4)
            s shouldContain "double_entry_schema_history"
            s shouldContain "spring.flyway.baseline-on-migrate=true"
            s shouldContain "baselineOnMigrate"
            s shouldContain "ddl-auto=validate"
        }

        @Test
        fun `5 outermost-transaction rule vs Transactional`() {
            val s = section(5)
            s shouldContain "LockMustBeOutermostTransaction"
            s shouldContain "@Transactional"
            s shouldContain "lockAccounts"
            s shouldContain "runningInsideTransactionalFixtures"
        }

        @Test
        fun `6 scheduling LineCheckService with Scheduled`() {
            val s = section(6)
            s shouldContain "@Scheduled("
            s shouldContain "lineCheck.perform(fixer)"
            s shouldContain "@EnableScheduling"
        }

        @Test
        fun `7 deviation list X-01 to X-11 in host terms`() {
            val s = section(7)
            (1..11).forEach { s shouldContain "| X-%02d".format(it) }
        }
    }

    @Nested
    inner class CutOverChecklist {
        private val checklist get() = section(8)

        @Test
        fun `U-004 legacy metadata table count must be 0 or the table absent`() {
            checklist shouldContain "U-004"
            checklist shouldContain "SELECT count(*) FROM double_entry_line_metadata"
            checklist shouldContain "SELECT to_regclass('double_entry_line_metadata')"
        }

        @Test
        fun `U-005 list the indexes of double_entry_lines`() {
            checklist shouldContain "U-005"
            checklist shouldContain "FROM pg_indexes WHERE tablename = 'double_entry_lines'"
        }

        @Test
        fun `U-014 default_timezone must be utc`() {
            checklist shouldContain "U-014"
            checklist shouldContain "`default_timezone` is `:utc`"
        }

        @Test
        fun `V2 pre-check duplicate NULL-scope rows`() {
            checklist shouldContain "V2 pre-check"
            checklist shouldContain "GROUP BY account, scope"
            checklist shouldContain "HAVING count(*) > 1"
        }

        @Test
        fun `PostgreSQL 15 or newer`() {
            checklist shouldContain "PostgreSQL 15 or newer"
        }

        @Test
        fun `U-003 host Rails 7_2 or newer for mixed writers`() {
            checklist shouldContain "U-003"
            checklist shouldContain "Rails 7.2 or newer"
        }
    }

    /** The Kotlin column of the side-by-side example in section 2, as code: it must build what the Ruby initializer defines. */
    @Nested
    inner class InitializerExample {
        private inner class User(
            val id: Long,
        )

        private val userScope: ScopeIdentifier = { user ->
            require(user is User) { "not a User" }
            user.id
        }

        private val configuration =
            doubleEntry(DoubleEntryProperties.DEFAULTS) {
                accounts {
                    define("savings", scopeIdentifier = userScope, positiveOnly = true)
                    define("checking", scopeIdentifier = userScope)
                }
                transfers {
                    define(from = "checking", to = "savings", code = "deposit")
                    define(from = "savings", to = "checking", code = "withdraw")
                }
            }

        @Test
        fun `defines scoped savings (positive only) and checking in the default currency`() {
            val savings = configuration.accounts.findWithoutScope("savings")!!
            savings.scoped shouldBe true
            savings.positiveOnly shouldBe true
            savings.currency shouldBe CurrencyUnit.of("USD")
            val checking = configuration.accounts.findWithoutScope("checking")!!
            checking.scoped shouldBe true
            checking.positiveOnly shouldBe false
        }

        @Test
        fun `allows exactly deposit and withdraw`() {
            configuration.transfers.map { listOf(it.from, it.to, it.code) } shouldBe
                listOf(listOf("checking", "savings", "deposit"), listOf("savings", "checking", "withdraw"))
        }

        @Test
        fun `user scope returns the id and rejects other objects like the Ruby lambda`() {
            userScope(User(42)) shouldBe 42L
            shouldThrow<IllegalArgumentException> { userScope("not a user") }.message shouldBe "not a User"
        }

        @Test
        fun `per-account currency as in the README AUD example`() {
            doubleEntry {
                accounts { define("savings", scopeIdentifier = userScope, currency = "AUD") }
            }.accounts.currencyOf("savings") shouldBe CurrencyUnit.of("AUD")
        }
    }
}
