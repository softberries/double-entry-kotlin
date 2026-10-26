package com.softberries.doubleentry.samplehost

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * Minimal host application for the double-entry library: exposes `GET /actuator/health` and hosts later
 * integration and parity runs. The library itself never exposes endpoints (D-001).
 * Component, entity and repository scanning of the library packages is replaced by its `@AutoConfiguration` in M-014.
 */
@SpringBootApplication(scanBasePackages = ["com.softberries.doubleentry"])
@EntityScan(basePackages = ["com.softberries.doubleentry"])
@EnableJpaRepositories(basePackages = ["com.softberries.doubleentry"])
class SampleHostApplication

fun main(args: Array<String>) {
    runApplication<SampleHostApplication>(*args)
}
