package com.softberries.doubleentry

import com.softberries.doubleentry.config.ConfigConfiguration
import com.softberries.doubleentry.config.DoubleEntryConfigurer
import com.softberries.doubleentry.testing.MutableClock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * Boots the library for tests the way a host does: through `DoubleEntryAutoConfiguration`, with no component
 * scanning of the library packages. Feature tests contribute their accounts and transfers with an `@Import`ed
 * `@TestConfiguration` that declares `DoubleEntryConfigurer` beans.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class TestDoubleEntryApplication {
    companion object {
        /** Primary because the same instance is also registered as `doubleEntryClock` (see below). */
        @Bean
        @Primary
        @JvmStatic
        fun mutableClock(): MutableClock = MutableClock()

        /** Replaces the `doubleEntryClock` bean with [MutableClock] so tests can freeze time (U-008). */
        @Bean
        @JvmStatic
        fun mutableDoubleEntryClockPostProcessor(clock: ObjectProvider<MutableClock>): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any = if (beanName == ConfigConfiguration.DOUBLE_ENTRY_CLOCK) clock.getObject() else bean
            }

        /**
         * Contributes nothing, so tests that need no definitions still get a (empty) configuration: a host without any
         * configuration fails to start (`AutoConfigurationTest`).
         */
        @Bean
        @JvmStatic
        fun emptyTestDefinitions(): DoubleEntryConfigurer = DoubleEntryConfigurer { }
    }
}
