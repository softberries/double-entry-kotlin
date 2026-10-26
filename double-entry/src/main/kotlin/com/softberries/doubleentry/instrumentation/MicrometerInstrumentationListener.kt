package com.softberries.doubleentry.instrumentation

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.event.EventListener

/**
 * Counts every [DoubleEntryEvent] in `double_entry.<event>`, e.g. `double_entry.duplicate_ignore`. This is an
 * addition over the gem (X-10, D-012). Without a [MeterRegistry] bean nothing is counted.
 *
 * Listeners run on the publishing thread inside the locking code, so a failing registry must not break the operation:
 * the failure is logged and swallowed, as `ActiveSupport::LogSubscriber#call` does for Ruby subscribers.
 */
class MicrometerInstrumentationListener(
    private val meterRegistry: ObjectProvider<MeterRegistry>,
) {
    @EventListener
    fun on(event: DoubleEntryEvent) {
        val name = counterName(event.type)
        try {
            meterRegistry.ifAvailable { registry -> registry.counter(name).increment() }
        } catch (e: RuntimeException) {
            log.warn("Could not count {} event in {}", event.name, name, e)
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(InstrumentationLogListener.LOGGER_NAME)

        fun counterName(type: DoubleEntryEventType): String = "double_entry.${type.shortName}"
    }
}
