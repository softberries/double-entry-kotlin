package com.softberries.doubleentry.instrumentation

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.softberries.doubleentry.testing.MigrationSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import java.sql.SQLException

/**
 * BR-072 and D-012/X-10: events, log lines and counters. No Ruby spec targets the `LogSubscriber`
 * (`docs/80-tests.md` §3.1 gap list). Oracle: `lock_create_ignoring_duplicates_scoped.json` (`notifications`).
 */
class InstrumentationTest {
    private val logger = LoggerFactory.getLogger(InstrumentationLogListener.LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private var previousLevel: Level? = null

    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(InstrumentationConfiguration::class.java)
            .withBean(RecordingListener::class.java)

    @BeforeEach
    fun attachAppender() {
        previousLevel = logger.level
        logger.level = Level.DEBUG
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        logger.detachAppender(appender)
        logger.level = previousLevel
    }

    @ParameterizedTest
    @EnumSource(DoubleEntryEventType::class)
    fun `publishing by Ruby event name delivers the event, logs INFO and DEBUG and increments the counter`(type: DoubleEntryEventType) {
        val exception = SQLException("ERROR: deadlock detected", "40P01")

        contextRunner.withBean(MeterRegistry::class.java, { SimpleMeterRegistry() }).run { context ->
            context.getBean(DoubleEntryEventPublisher::class.java).publish(type.eventName, exception)

            val events = context.getBean(RecordingListener::class.java).events
            events shouldHaveSize 1
            events.single().name shouldBe type.eventName
            events.single().exception shouldBe exception
            events.single().shouldBeInstanceOf<ApplicationEvent>()

            assertLogLines(type, exception)
            context.getBean(MeterRegistry::class.java).counter("double_entry.${type.shortName}").count() shouldBe 1.0
        }
    }

    @Test
    fun `duplicate_ignore uses the exact Ruby texts`() {
        DoubleEntryEventType.DUPLICATE_IGNORE.eventName shouldBe "duplicate_ignore.double_entry"
        DoubleEntryEventType.DUPLICATE_IGNORE.logMessage shouldBe "Duplicate ignored"
        DoubleEntryEventType.DEADLOCK_RESTART.eventName shouldBe "deadlock_restart.double_entry"
        DoubleEntryEventType.DEADLOCK_RESTART.logMessage shouldBe "Deadlock causing restart"
        DoubleEntryEventType.DEADLOCK_RETRY.eventName shouldBe "deadlock_retry.double_entry"
        DoubleEntryEventType.DEADLOCK_RETRY.logMessage shouldBe "Deadlock causing retry"
        MicrometerInstrumentationListener.counterName(DoubleEntryEventType.DUPLICATE_IGNORE) shouldBe
            "double_entry.duplicate_ignore"
    }

    @Test
    fun `counters count each event separately`() {
        contextRunner.withBean(MeterRegistry::class.java, { SimpleMeterRegistry() }).run { context ->
            val publisher = context.getBean(DoubleEntryEventPublisher::class.java)
            repeat(2) { publisher.publish(DoubleEntryEventType.DUPLICATE_IGNORE, RuntimeException("dup")) }
            publisher.publish(DoubleEntryEventType.DEADLOCK_RETRY, RuntimeException("deadlock"))

            val registry = context.getBean(MeterRegistry::class.java)
            registry.counter("double_entry.duplicate_ignore").count() shouldBe 2.0
            registry.counter("double_entry.deadlock_retry").count() shouldBe 1.0
            registry.counter("double_entry.deadlock_restart").count() shouldBe 0.0
        }
    }

    @Test
    fun `without a MeterRegistry bean events and logs still work`() {
        val exception = RuntimeException("duplicate key value violates unique constraint")

        contextRunner.run { context ->
            context.getBeanProvider(MeterRegistry::class.java).ifAvailable shouldBe null

            context.getBean(DoubleEntryEventPublisher::class.java).publish("duplicate_ignore.double_entry", exception)

            context.getBean(RecordingListener::class.java).events.map { it.name } shouldContainExactly
                listOf("duplicate_ignore.double_entry")
            assertLogLines(DoubleEntryEventType.DUPLICATE_IGNORE, exception)
        }
    }

    @Test
    fun `DEBUG line is skipped when the logger is at INFO`() {
        logger.level = Level.INFO

        contextRunner.run { context ->
            context.getBean(DoubleEntryEventPublisher::class.java).publish(DoubleEntryEventType.DEADLOCK_RESTART, RuntimeException("x"))
        }

        appender.list.map { it.level to it.formattedMessage } shouldContainExactly listOf(Level.INFO to "Deadlock causing restart")
    }

    @Test
    fun `an unknown event name is rejected and nothing is published`() {
        contextRunner.run { context ->
            shouldThrow<IllegalArgumentException> {
                context.getBean(DoubleEntryEventPublisher::class.java).publish("unknown.double_entry", RuntimeException())
            }
            context.getBean(RecordingListener::class.java).events.shouldBeEmpty()
        }
        appender.list.shouldBeEmpty()
    }

    @Test
    fun `oracle notification name and payload key map to a DoubleEntryEvent`() {
        val notifications =
            jacksonObjectMapper()
                .readTree(MigrationSpec.read("oracle/api/json/lock_create_ignoring_duplicates_scoped.json"))["notifications"]
        notifications shouldHaveSize 1
        val notification = notifications.single()

        val type = DoubleEntryEventType.ofEventName(notification["event"].asText())

        type shouldBe DoubleEntryEventType.DUPLICATE_IGNORE
        notification.has("exception") shouldBe true
    }

    private fun assertLogLines(
        type: DoubleEntryEventType,
        exception: Throwable,
    ) {
        appender.list shouldHaveSize 2
        val (info, debug) = appender.list
        info.loggerName shouldBe "double_entry"
        info.level shouldBe Level.INFO
        info.formattedMessage shouldBe type.logMessage
        debug.loggerName shouldBe "double_entry"
        debug.level shouldBe Level.DEBUG
        debug.formattedMessage shouldBe "${exception.message} (${exception.javaClass.name})"
        debug.throwableProxy.className shouldBe exception.javaClass.name
    }

    class RecordingListener : ApplicationListener<DoubleEntryEvent> {
        val events = mutableListOf<DoubleEntryEvent>()

        override fun onApplicationEvent(event: DoubleEntryEvent) {
            events += event
        }
    }
}
