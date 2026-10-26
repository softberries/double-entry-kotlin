package com.softberries.doubleentry.samplehost

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Minimal host application for the double-entry library: exposes `GET /actuator/health` and hosts integration and
 * parity runs. The library itself never exposes endpoints (D-001). It is wired by the library's auto-configuration
 * alone; the host only declares its ledger definitions ([SampleHostLedger]).
 */
@SpringBootApplication
class SampleHostApplication

fun main(args: Array<String>) {
    runApplication<SampleHostApplication>(*args)
}
