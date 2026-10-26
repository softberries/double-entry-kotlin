package com.softberries.doubleentry.autoconfigure

import com.softberries.doubleentry.config.MissingDoubleEntryConfigurationException
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis

/** Turns a missing host configuration into a readable startup report instead of a bean-creation stack trace. */
class MissingDoubleEntryConfigurationFailureAnalyzer : AbstractFailureAnalyzer<MissingDoubleEntryConfigurationException>() {
    override fun analyze(
        rootFailure: Throwable,
        cause: MissingDoubleEntryConfigurationException,
    ): FailureAnalysis =
        FailureAnalysis(
            cause.message,
            "Declare the ledger's accounts and transfers, e.g.\n\n" +
                "    @Bean\n" +
                "    fun doubleEntryConfiguration() = doubleEntry {\n" +
                "        accounts { define(\"cash\") }\n" +
                "        transfers { define(from = \"bank\", to = \"cash\", code = \"deposit\") }\n" +
                "    }\n\n" +
                "or one or more DoubleEntryConfigurer beans.",
            cause,
        )
}
