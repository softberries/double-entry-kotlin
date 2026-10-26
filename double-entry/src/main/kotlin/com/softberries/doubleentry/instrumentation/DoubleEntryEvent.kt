package com.softberries.doubleentry.instrumentation

import org.springframework.context.ApplicationEvent

/**
 * The notifications the gem publishes on `ActiveSupport::Notifications` (`docs/60-async-and-integrations.md` §3).
 * [eventName] is the Ruby notification name, [logMessage] the INFO line of the Ruby `LogSubscriber` (BR-072).
 */
enum class DoubleEntryEventType(
    val eventName: String,
    val logMessage: String,
) {
    /** Deadlock while grabbing locks; the whole lock transaction is restarted (`locking_extensions.rb:21`). */
    DEADLOCK_RESTART("deadlock_restart.double_entry", "Deadlock causing restart"),

    /** Deadlock while creating a balance row; the creation is retried (`locking_extensions.rb:66`). */
    DEADLOCK_RETRY("deadlock_retry.double_entry", "Deadlock causing retry"),

    /** Unique violation while creating a balance row; it is ignored (`locking_extensions.rb:49`). */
    DUPLICATE_IGNORE("duplicate_ignore.double_entry", "Duplicate ignored"),
    ;

    /** The event name without the `.double_entry` namespace, e.g. `duplicate_ignore`. */
    val shortName: String get() = eventName.substringBefore('.')

    companion object {
        fun ofEventName(eventName: String): DoubleEntryEventType =
            requireNotNull(entries.find { it.eventName == eventName }) {
                "Unknown double_entry event '$eventName'; expected one of ${entries.map { it.eventName }}"
            }
    }
}

/**
 * Spring counterpart of an `ActiveSupport::Notifications` event of the gem (D-012): same [name] and `exception` payload.
 */
class DoubleEntryEvent(
    source: Any,
    val type: DoubleEntryEventType,
    val exception: Throwable,
) : ApplicationEvent(source) {
    /** The Ruby notification name, e.g. `duplicate_ignore.double_entry`. */
    val name: String get() = type.eventName

    override fun toString(): String = "DoubleEntryEvent(name=$name, exception=$exception)"
}
