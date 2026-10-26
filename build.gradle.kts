import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover) apply false
}

val catalog = libs

ktlint {
    version.set(catalog.versions.ktlint.cli)
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(catalog.versions.ktlint.cli)
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
                freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
            }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxParallelForks = 1
        // Tests share one PostgreSQL container per JVM (PostgresIntegrationTest).
        systemProperty("doubleentry.migrationSpecDir", rootProject.file("migration-spec").absolutePath)
        // Headless pods: Testcontainers reads TESTCONTAINERS_HOST_OVERRIDE / TESTCONTAINERS_RYUK_DISABLED itself.
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
