package com.softberries.doubleentry.instrumentation

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.event.EventListener

/**
 * Counts every [DoubleEntryEvent] in `double_entry.<event>`, e.g. `double_entry.duplicate_ignore`. This is an
 * addition over the gem (X-10, D-012). Without a [MeterRegistry] bean nothing is counted.
 */
class MicrometerInstrumentationListener(
    private val meterRegistry: ObjectProvider<MeterRegistry>,
) {
    @EventListener
    fun on(event: DoubleEntryEvent) {
        meterRegistry.ifAvailable { registry -> registry.counter(counterName(event.type)).increment() }
    }

    companion object {
        fun counterName(type: DoubleEntryEventType): String = "double_entry.${type.shortName}"
    }
}
