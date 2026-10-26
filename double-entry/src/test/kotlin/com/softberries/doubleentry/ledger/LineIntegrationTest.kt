package com.softberries.doubleentry.ledger

import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.account.AccountSet
import com.softberries.doubleentry.errors.AccountWouldBeSentNegative
import com.softberries.doubleentry.errors.AccountWouldBeSentPositiveError
import com.softberries.doubleentry.errors.UnknownAccount
import com.softberries.doubleentry.money.CurrencyUnit
import com.softberries.doubleentry.money.Money
import com.softberries.doubleentry.testing.MutableClock.Companion.ORACLE_T0
import com.softberries.doubleentry.testing.OracleSample
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OneToOne
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.TimeZone

/** Acceptance criteria of M-007 and the gap rules BR-035, BR-081, BR-082, BR-084 (`docs/80-tests.md` §3.1). */
class LineIntegrationTest : LedgerIntegrationTest() {
    private fun usd(minor: Long) = Money(minor, CurrencyUnit.USD)

    private fun newLine(
        account: String,
        balance: Long,
        scope: String? = null,
    ) = NewLine(account(account, scope), account("bank"), "x", usd(balance), usd(balance))

    @Test
    fun `the entity is validated against V1 by Hibernate and has no associations`() {
        // The context starts with spring.jpa.hibernate.ddl-auto=validate, so reaching this line means validate passed.
        val associations = listOf(ManyToOne::class.java, OneToOne::class.java, OneToMany::class.java, ManyToMany::class.java)
        LineEntity::class.java.declaredFields
            .filter { field -> associations.any { field.isAnnotationPresent(it) } }
            .shouldBeEmpty()
        repository.findAllByOrderByIdAsc().shouldBeEmpty()
    }

    @Test
    fun `BR-030 positive_only savings with balance -1 raises and inserts nothing (line_direct_create_positive_only_check)`() {
        val expected = OracleSample.exception("line_direct_create_positive_only_check")

        val error = shouldThrow<AccountWouldBeSentNegative> { writer.create(newLine("savings", -1, scope = "1")) }

        error.rubyClassName shouldBe expected["class"].asText()
        error.message shouldBe expected["message"].asText()
        error.message shouldBe "#{Account account: savings scope:  currency: USD}"
        lineCount() shouldBe 0
    }

    @Test
    fun `BR-030 negative_only liability with balance +1 raises AccountWouldBeSentPositiveError`() {
        val error = shouldThrow<AccountWouldBeSentPositiveError> { writer.create(newLine("liability", 1)) }

        error.message shouldBe "#{Account account: liability scope:  currency: USD}"
        lineCount() shouldBe 0
    }

    @Test
    fun `BR-030 a zero balance is fine on positive_only and negative_only accounts`() {
        writer.create(newLine("savings", 0, scope = "1"))
        writer.create(newLine("liability", 0))

        lineCount() shouldBe 2
    }

    @Test
    fun `BR-030 the guard also runs on updates`() {
        val id = writer.create(newLine("savings", 0, scope = "1")).id

        shouldThrow<AccountWouldBeSentNegative> {
            transactionTemplate.executeWithoutResult {
                val entity = repository.findById(id)!!
                entity.balance = -5
                writer.update(entity)
            }
        }

        jdbcTemplate.queryForObject("SELECT balance FROM double_entry_lines WHERE id = ?", Long::class.java, id) shouldBe 0L
    }

    @Test
    fun `BR-085 a null code violates NOT NULL with SQLSTATE 23502 (line_direct_create_missing_code)`() {
        val expected = OracleSample.exception("line_direct_create_missing_code")

        val error =
            shouldThrow<DataIntegrityViolationException> {
                jdbcTemplate.update(
                    "INSERT INTO double_entry_lines (account, amount, balance, partner_account, created_at, updated_at) " +
                        "VALUES ('bank', 1, 1, 'cash', now(), now())",
                )
            }

        val cause = error.mostSpecificCause.shouldBeInstanceOf<SQLException>()
        cause.sqlState shouldBe "23502"
        expected["message"].asText() shouldContain "null value in column \"code\" of relation \"double_entry_lines\""
        cause.message!! shouldContain "null value in column \"code\" of relation \"double_entry_lines\""
        lineCount() shouldBe 0
    }

    @Test
    fun `BR-080 amount and balance of a BTC line are BTC Money`() {
        val line = writer.create(NewLine(account("btc_store"), account("btc_store"), "x", usd(5), usd(7)))

        line.amount shouldBe Money(5, CurrencyUnit.BTC)
        line.balance shouldBe Money(7, CurrencyUnit.BTC)
    }

    @Test
    fun `BR-080 reading a line whose account is no longer configured raises UnknownAccount (line_account_unknown_after_config_change)`() {
        val expected = OracleSample.result("line_account_unknown_after_config_change")
        transferLines(1_00, account("bank"), account("cash"), "salary")
        // `DoubleEntry.config.accounts = DoubleEntry::Account::Set.new`: read the first line against an empty configuration.
        val emptyConfig = AccountResolver(AccountSet.build {})
        val line = Line.of(repository.findAllByOrderByIdAsc().first(), emptyConfig)

        line.accountIdentifier shouldBe expected["raw_account"].asText()
        val error = shouldThrow<UnknownAccount> { line.amount }
        "${error.rubyClassName}: ${error.message}" shouldBe expected["amount"].asText()
        error.message shouldBe "account: bank scoped?: false"
    }

    @Test
    fun `BR-080 account and partner account are rebuilt from the stored identifier and scope`() {
        val line = writer.create(NewLine(account("test", "54"), account("test", "91"), "x", usd(0), usd(0)))
        val read = lines.find(line.id)!!

        read.account.identifier shouldBe "test"
        read.account.scopeIdentity shouldBe "54"
        read.partnerAccount.identifier shouldBe "test"
        read.partnerAccount.scopeIdentity shouldBe "91"
    }

    @Test
    fun `BR-081 BR-082 credits, debits and withIdGreaterThan (line_scopes)`() {
        val expected = OracleSample.result("line_scopes")
        val (credit, debit) = transferLines(5_00, account("bank"), account("cash"), "salary")

        credit.id shouldBe expected["credit_line_id(from, negative)"].asLong()
        debit.id shouldBe expected["debit_line_id(to, positive)"].asLong()
        lines.credits().map { it.id } shouldContainExactly expected["Line.credits.ids"].map { it.asLong() }
        lines.debits().map { it.id } shouldContainExactly expected["Line.debits.ids"].map { it.asLong() }
        lines.withIdGreaterThan(1).map { it.id } shouldContainExactly expected["with_id_greater_than(1)"].map { it.asLong() }
        lines.credits().map { it.id } shouldContainExactly listOf(2L)
        lines.debits().map { it.id } shouldContainExactly listOf(1L)
    }

    @Test
    fun `BR-083 partner and pair put the negative line first`() {
        val (line1, line2) = transferLines(5_00, account("bank"), account("cash"), "salary")

        lines.partner(line1) shouldBe line2
        lines.partner(line2) shouldBe line1
        lines.pair(line1) shouldBe (line1 to line2)
        lines.pair(line2) shouldBe (line1 to line2)
        line1.isDecrease shouldBe true
        line2.isIncrease shouldBe true
    }

    @Test
    fun `BR-083 for a zero amount pair returns partner, self`() {
        val (line1, line2) = transferLines(0, account("bank"), account("cash"), "salary")

        lines.pair(line1) shouldBe (line2 to line1)
        lines.pair(line2) shouldBe (line1 to line2)
    }

    @Test
    fun `BR-083 partner of a line without partner id throws`() {
        val line = writer.create(newLine("bank", 0))

        shouldThrow<NoSuchElementException> { lines.partner(line) }
    }

    @Test
    fun `BR-084 findIdAndCreatedAt with typed criteria (line_find_id_and_created_at)`() {
        val expected = OracleSample.result("line_find_id_and_created_at")
        clock.set(ORACLE_T0)
        transferLines(5_00, account("bank"), account("cash"), "salary")

        val rows = lines.findIdAndCreatedAt(LineCriteria(account = "cash", createdAtFrom = ORACLE_T0))

        rows shouldContainExactly listOf(IdAndCreatedAt(2, Instant.parse("2026-01-15T10:00:00Z")))
        rows.map { listOf(it.id, it.createdAt) } shouldContainExactly
            expected.map { listOf(it[0].asLong(), Instant.parse(it[1].asText())) }
    }

    @Test
    fun `BR-084 every criterion narrows the result`() {
        clock.set(ORACLE_T0)
        transferLines(5_00, account("bank"), account("cash"), "salary")
        clock.advance(Duration.ofHours(1))
        transferLines(1_00, account("checking", "7"), account("cash"), "deposit")

        fun ids(criteria: LineCriteria) = lines.findIdAndCreatedAt(criteria).map { it.id }
        ids(LineCriteria()) shouldContainExactly listOf(1L, 2L, 3L, 4L)
        ids(LineCriteria(account = "cash")) shouldContainExactly listOf(2L, 4L)
        ids(LineCriteria(scope = "7")) shouldContainExactly listOf(3L)
        ids(LineCriteria(code = "deposit")) shouldContainExactly listOf(3L, 4L)
        ids(LineCriteria(createdAtFrom = ORACLE_T0.plusSeconds(1))) shouldContainExactly listOf(3L, 4L)
        ids(LineCriteria(createdAtTo = ORACLE_T0)) shouldContainExactly listOf(1L, 2L)
        ids(LineCriteria(idFrom = 2, idTo = 3)) shouldContainExactly listOf(2L, 3L)
        ids(LineCriteria(account = "cash'; DROP TABLE double_entry_lines; --")).shouldBeEmpty()
    }

    @Test
    fun `BR-035 timestamps come from the clock in UTC with microsecond precision`() {
        clock.set(ORACLE_T0)
        val first = writer.create(newLine("bank", 0))
        clock.set(ORACLE_T0.plusNanos(1_000))
        val second = writer.create(newLine("bank", 0))

        storedTimestamp(first.id, "created_at") shouldBe "2026-01-15 10:00:00.000000"
        storedTimestamp(second.id, "created_at") shouldBe "2026-01-15 10:00:00.000001"
        storedTimestamp(second.id, "updated_at") shouldBe "2026-01-15 10:00:00.000001"
        lines.find(second.id)!!.createdAt shouldBe Instant.parse("2026-01-15T10:00:00.000001Z")
    }

    @Test
    fun `BR-035 timestamps are UTC regardless of the JVM time zone, and updates bump updated_at`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            clock.set(ORACLE_T0)
            val id = writer.create(newLine("bank", 0)).id
            clock.advance(Duration.ofMinutes(5))
            transactionTemplate.executeWithoutResult {
                val entity = repository.findById(id)!!
                entity.partnerId = 99
                writer.update(entity)
            }

            storedTimestamp(id, "created_at") shouldBe "2026-01-15 10:00:00.000000"
            storedTimestamp(id, "updated_at") shouldBe "2026-01-15 10:05:00.000000"
            lines.find(id)!!.updatedAt shouldBe Instant.parse("2026-01-15T10:05:00Z")
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `metadata round-trips as jsonb with the same JSON types`() {
        val metadata = mapOf("n" to 1, "tax" to listOf("GST", "VAT"), "nested" to mapOf("a" to 1), "country" to "AU")
        val withMetadata = writer.create(newLine("bank", 0).copy(metadata = metadata))
        val empty = writer.create(newLine("bank", 0).copy(metadata = emptyMap()))
        val none = writer.create(newLine("bank", 0))

        lines.find(withMetadata.id)!!.metadata shouldBe metadata
        storedMetadata(withMetadata.id) shouldBe """{"n": 1, "tax": ["GST", "VAT"], "nested": {"a": 1}, "country": "AU"}"""
        jdbcTemplate.queryForObject(
            "SELECT jsonb_typeof(metadata->'n') || ',' || jsonb_typeof(metadata->'tax') || ',' || jsonb_typeof(metadata->'nested') " +
                "FROM double_entry_lines WHERE id = ?",
            String::class.java,
            withMetadata.id,
        ) shouldBe "number,array,object"
        lines.find(empty.id)!!.metadata shouldBe emptyMap()
        storedMetadata(empty.id) shouldBe "{}"
        lines.find(none.id)!!.metadata shouldBe null
        jdbcTemplate.queryForObject(
            "SELECT metadata IS NULL FROM double_entry_lines WHERE id = ?",
            Boolean::class.java,
            none.id,
        ) shouldBe true
    }

    @Test
    fun `detail is stored as detail_type and detail_id`() {
        val line = writer.create(newLine("bank", 0).copy(detail = DetailRef("Order", 42)))

        lines.find(line.id)!!.detail shouldBe DetailRef("Order", 42)
        jdbcTemplate.queryForMap("SELECT detail_type, detail_id FROM double_entry_lines WHERE id = ?", line.id) shouldBe
            mapOf("detail_type" to "Order", "detail_id" to 42L)
    }

    private fun storedTimestamp(
        id: Long,
        column: String,
    ): String =
        jdbcTemplate.queryForObject(
            "SELECT to_char($column, 'YYYY-MM-DD HH24:MI:SS.US') FROM double_entry_lines WHERE id = ?",
            String::class.java,
            id,
        )!!

    private fun storedMetadata(id: Long): String =
        jdbcTemplate.queryForObject("SELECT metadata::text FROM double_entry_lines WHERE id = ?", String::class.java, id)!!
}
