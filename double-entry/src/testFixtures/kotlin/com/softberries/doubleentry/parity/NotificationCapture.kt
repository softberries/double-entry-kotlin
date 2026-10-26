package com.softberries.doubleentry.parity

import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import java.util.concurrent.CopyOnWriteArrayList

/** A notification seen on the Kotlin side: Ruby event name and the message of the exception it carries. */
data class ObservedNotification(
    val event: String,
    val exception: String?,
)

/**
 * Collects the library's instrumentation events (`DoubleEntryEvent`, M-003) during a Kotlin run, to compare with an
 * oracle `notifications` list. [extractor] turns an event into an [ObservedNotification] (null = not a double_entry
 * event); the parity driver supplies `{ (it as? DoubleEntryEvent)?.let { e -> ObservedNotification(e.name, e.exception.message) } }`,
 * so this harness does not depend on the instrumentation package. Register an instance as a bean of the driver's context.
 */
class NotificationCapture(
    private val extractor: (ApplicationEvent) -> ObservedNotification?,
) : ApplicationListener<ApplicationEvent> {
    private val recorded = CopyOnWriteArrayList<ObservedNotification>()

    val notifications: List<ObservedNotification> get() = recorded.toList()

    fun clear() = recorded.clear()

    override fun onApplicationEvent(event: ApplicationEvent) {
        extractor(event)?.let(recorded::add)
    }

    companion object {
        /** `Key (scope, account)=(null, bank)` in a PostgreSQL unique-violation message (Ruby `PG::` or JDBC text). */
        private val PG_KEY = Regex("Key \\(([^)]*)\\)=\\(([^)]*)\\)")

        /**
         * Event names must be equal, in order. Exception messages differ between `PG::UniqueViolation` and the JDBC
         * driver, so only the PostgreSQL `Key (…)=(…)` details are compared, as a multiset (concurrent writers emit
         * them in any order).
         */
        fun compare(
            expected: List<OracleNotification>,
            actual: List<ObservedNotification>,
        ): Comparison {
            val expectedEvents = expected.map { it.event }
            val actualEvents = actual.map { it.event }
            val expectedKeys = expected.mapNotNull { e -> e.exception?.let { PG_KEY.find(it)?.value } }.sorted()
            val actualKeys = actual.mapNotNull { a -> a.exception?.let { PG_KEY.find(it)?.value } }.sorted()
            return Comparison.of(
                "events: expected $expectedEvents but was $actualEvents".takeUnless { expectedEvents == actualEvents },
                "unique-violation keys: expected $expectedKeys but was $actualKeys".takeUnless { expectedKeys == actualKeys },
            )
        }
    }
}
