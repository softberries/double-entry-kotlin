package com.softberries.doubleentry.parity

import com.softberries.doubleentry.instrumentation.DoubleEntryEvent
import com.softberries.doubleentry.instrumentation.DoubleEntryEventType
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEvent
import java.sql.SQLException

class NotificationCaptureTest {
    private val capture =
        NotificationCapture { event -> (event as? DoubleEntryEvent)?.let { ObservedNotification(it.name, it.exception.message) } }

    private fun jdbcUniqueViolation(key: String) =
        SQLException(
            "ERROR: duplicate key value violates unique constraint \"index_account_balances_on_scope_and_account\"\n" +
                "  Detail: Key (scope, account)=($key) already exists.",
            "23505",
        )

    @Test
    fun `collects DoubleEntryEvents and matches the oracle notifications`() {
        capture.onApplicationEvent(DoubleEntryEvent(this, DoubleEntryEventType.DUPLICATE_IGNORE, jdbcUniqueViolation("1, checking")))
        capture.onApplicationEvent(object : ApplicationEvent(this) {})

        capture.notifications shouldHaveSize 1
        val expected = OracleScenarios.load("lock_create_ignoring_duplicates_scoped").notifications
        NotificationCapture.compare(expected, capture.notifications).matches shouldBe true

        capture.clear()
        capture.onApplicationEvent(DoubleEntryEvent(this, DoubleEntryEventType.DUPLICATE_IGNORE, jdbcUniqueViolation("1, savings")))
        NotificationCapture.compare(expected, capture.notifications).differences.single() shouldBe
            "unique-violation keys: expected [Key (scope, account)=(1, checking)] but was [Key (scope, account)=(1, savings)]"
    }

    @Test
    fun `compat_nulls_not_distinct_index matches itself in any key order, not with a missing event`() {
        val expected = OracleScenarios.load("compat_nulls_not_distinct_index").notifications
        val observed = expected.reversed().map { ObservedNotification(it.event, it.exception) }

        NotificationCapture.compare(expected, observed).matches shouldBe true
        NotificationCapture.compare(expected, observed.drop(1)).matches shouldBe false
        NotificationCapture.compare(emptyList(), emptyList()).matches shouldBe true
    }
}
