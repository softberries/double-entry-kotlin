plugins {
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kover)
}

description = "double_entry ledger library for Spring Boot (Kotlin port of the double_entry gem)"

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

dependencies {
    val bom = platform(libs.spring.boot.bom)
    api(bom)
    api(platform(libs.kotlin.bom))
    kapt(bom)
    testFixturesApi(platform(libs.testcontainers.bom))

    api(libs.spring.boot.starter.data.jpa)
    api(libs.micrometer.core)
    api(libs.slf4j.api)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    kapt(libs.spring.boot.configuration.processor)

    testFixturesApi(libs.spring.boot.starter.test)
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(libs.testcontainers.junit.jupiter)
    testFixturesApi(libs.kotest.assertions.core)
    testFixturesApi(libs.datasource.proxy)
    // Oracle replay harness (parity/): reads oracle/api/json with Jackson.
    testFixturesApi(libs.jackson.module.kotlin)
    testFixturesImplementation(libs.postgresql)

    testImplementation(libs.kotest.assertions.json)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
    testImplementation(libs.awaitility.kotlin)
    testImplementation(libs.flyway.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
