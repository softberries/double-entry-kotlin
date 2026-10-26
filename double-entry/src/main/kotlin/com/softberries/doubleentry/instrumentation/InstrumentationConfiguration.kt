package com.softberries.doubleentry.instrumentation

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Instrumentation (D-012): the event publisher, the log listener (BR-072) and the Micrometer counters (X-10). */
@Configuration(proxyBeanMethods = false)
class InstrumentationConfiguration {
    @Bean
    fun doubleEntryEventPublisher(applicationEventPublisher: ApplicationEventPublisher): DoubleEntryEventPublisher =
        DoubleEntryEventPublisher(applicationEventPublisher)

    @Bean
    fun doubleEntryInstrumentationLogListener(): InstrumentationLogListener = InstrumentationLogListener()

    @Bean
    fun doubleEntryMicrometerInstrumentationListener(meterRegistry: ObjectProvider<MeterRegistry>): MicrometerInstrumentationListener =
        MicrometerInstrumentationListener(meterRegistry)
}
