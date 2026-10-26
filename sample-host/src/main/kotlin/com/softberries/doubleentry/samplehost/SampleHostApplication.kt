package com.softberries.doubleentry.samplehost

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Minimal host application for the double-entry library: exposes `GET /actuator/health` and hosts later
 * integration and parity runs. The library itself never exposes endpoints (D-001).
 * Component scanning of the library packages is replaced by its `@AutoConfiguration` in M-014.
 */
@SpringBootApplication(scanBasePackages = ["com.softberries.doubleentry"])
class SampleHostApplication

fun main(args: Array<String>) {
    runApplication<SampleHostApplication>(*args)
}
