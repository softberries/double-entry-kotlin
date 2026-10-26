package com.softberries.doubleentry.instrumentation

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener

/**
 * Port of `ActiveRecord::LockingExtensions::LogSubscriber` (`log_subscriber.rb:7-29`, BR-072): one INFO line with
 * the event message and the exception at DEBUG, on logger `double_entry`.
 */
class InstrumentationLogListener {
    @EventListener
    fun on(event: DoubleEntryEvent) {
        log.info(event.type.logMessage)
        if (log.isDebugEnabled) {
            // Ruby's Logger formats an exception as "<message> (<class>)" followed by the backtrace.
            val exception = event.exception
            log.debug("${exception.message} (${exception.javaClass.name})", exception)
        }
    }

    companion object {
        const val LOGGER_NAME = "double_entry"
        private val log: Logger = LoggerFactory.getLogger(LOGGER_NAME)
    }
}
