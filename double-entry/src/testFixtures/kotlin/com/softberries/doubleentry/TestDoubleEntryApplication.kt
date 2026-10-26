package com.softberries.doubleentry

import com.softberries.doubleentry.config.ConfigConfiguration
import com.softberries.doubleentry.testing.MutableClock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * Boots every feature configuration of the library for tests. Feature units add a `<Feature>Configuration` in their
 * package and never edit this class. M-014 replaces component scanning with the real `@AutoConfiguration`.
 */
@SpringBootApplication(scanBasePackages = ["com.softberries.doubleentry"])
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
    }
}
