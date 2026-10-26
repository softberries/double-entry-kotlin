plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

description = "Minimal Spring Boot host for the double-entry library (health endpoint, integration and parity runs)"

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.kotlin.bom))
    implementation(project(":double-entry"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.assertions.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}
