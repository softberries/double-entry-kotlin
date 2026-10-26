package com.softberries.doubleentry.instrumentation

import org.springframework.context.ApplicationEventPublisher

/**
 * Replaces `ActiveSupport::Notifications.publish('<event>.double_entry', exception: e)`. Listeners run synchronously
 * on the publishing thread, as Ruby subscribers do.
 */
class DoubleEntryEventPublisher(
    private val applicationEventPublisher: ApplicationEventPublisher,
) {
    fun publish(
        type: DoubleEntryEventType,
        exception: Throwable,
    ) {
        applicationEventPublisher.publishEvent(DoubleEntryEvent(this, type, exception))
    }

    /** Publishes by Ruby notification name, e.g. `duplicate_ignore.double_entry`; unknown names are rejected. */
    fun publish(
        eventName: String,
        exception: Throwable,
    ) = publish(DoubleEntryEventType.ofEventName(eventName), exception)
}
