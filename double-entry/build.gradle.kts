plugins {
    `java-library`
    `java-test-fixtures`
    `maven-publish`
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

// --- Publication (M-017; replaces the gem's `rake build` / `rake release`, double_entry.gemspec) ---
// Coordinates are `group` and `version` in gradle.properties (U-010 proposal: com.softberries:double-entry:3.x on
// GitHub Packages). The release workflow passes `-Pversion=X.Y.Z` from the tag `vX.Y.Z`.

java {
    withSourcesJar()
    // The library is Kotlin-only, so the javadoc jar carries no generated pages; it exists because Maven repositories
    // (and many consumers) expect one next to every jar.
    withJavadocJar()
}

// Test fixtures (Testcontainers base classes) are for this repository's tests only and are not published.
(components["java"] as AdhocComponentWithVariants).apply {
    withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
    withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
}

tasks.withType<Jar>().configureEach {
    // MIT requires the copyright notice in every copy; the port is a derivative of the gem (LICENSE.md in the gem).
    from(rootProject.file("LICENSE")) { into("META-INF") }
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version.toString(),
            "Implementation-Vendor" to "softberries",
        )
    }
}

val githubRepository = "softberries/double-entry-kotlin"

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "double-entry"
            from(components["java"])
            pom {
                name.set("double-entry")
                description.set(project.description)
                url.set("https://github.com/$githubRepository")
                inceptionYear.set("2014")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("envato")
                        name.set("Envato")
                        email.set("rubygems@envato.com")
                        organization.set("Envato (original double_entry gem)")
                        organizationUrl.set("https://github.com/envato/double_entry")
                    }
                    developer {
                        id.set("softberries")
                        name.set("softberries")
                        organization.set("softberries (Kotlin port)")
                        organizationUrl.set("https://github.com/softberries")
                    }
                }
                scm {
                    url.set("https://github.com/$githubRepository")
                    connection.set("scm:git:https://github.com/$githubRepository.git")
                    developerConnection.set("scm:git:ssh://git@github.com/$githubRepository.git")
                    tag.set("v${project.version}")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/$githubRepository/issues")
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/$githubRepository")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
        // Build-local repository read by PublicationTest; never leaves the build directory.
        maven {
            name = "build"
            url = uri(layout.buildDirectory.dir("publication-repo"))
        }
    }
}

val publicationRepo = layout.buildDirectory.dir("publication-repo")

// Start from an empty repository so PublicationTest never sees artefacts of an earlier version or snapshot.
tasks.named("publishMavenPublicationToBuildRepository") {
    doFirst { delete(publicationRepo) }
}

tasks.named<Test>("test") {
    dependsOn("publishMavenPublicationToBuildRepository")
    inputs.dir(publicationRepo).withPropertyName("publicationRepo").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("doubleentry.publicationRepo", publicationRepo.get().asFile.absolutePath)
    systemProperty("doubleentry.publication.group", project.group.toString())
    systemProperty("doubleentry.publication.version", project.version.toString())
    // ReleaseWorkflowTest and HostGuideTest read files outside this module.
    systemProperty("doubleentry.repoRoot", rootProject.projectDir.absolutePath)
    inputs
        .file(
            rootProject.file(".github/workflows/release.yml"),
        ).withPropertyName("releaseWorkflow")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("docs/host-guide.md")).withPropertyName("hostGuide").withPathSensitivity(PathSensitivity.RELATIVE)
}
