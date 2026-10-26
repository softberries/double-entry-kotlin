package com.softberries.doubleentry.publication

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.jar.JarFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * M-017: the Maven publication that replaces the gem package (`double_entry.gemspec`). The `test` task first runs
 * `publishMavenPublicationToBuildRepository`, i.e. the same publication as `publishToMavenLocal` and the release
 * workflow, into `build/publication-repo`; this test reads the POM and jars from there.
 */
class PublicationTest {
    private val group = System.getProperty("doubleentry.publication.group")
    private val version = System.getProperty("doubleentry.publication.version")
    private val versionDir =
        File(System.getProperty("doubleentry.publicationRepo"), "${group.replace('.', '/')}/$ARTIFACT/$version")

    private fun artefact(suffix: String): File {
        // Snapshots get a timestamped file name, releases `double-entry-<version><suffix>`; the repository is emptied
        // before every publication, so there is exactly one match.
        val matches = versionDir.listFiles().orEmpty().filter { it.name.endsWith(suffix) && it.name.startsWith("$ARTIFACT-") }
        val exact =
            if (suffix ==
                ".jar"
            ) {
                matches.filterNot { it.name.endsWith("-sources.jar") || it.name.endsWith("-javadoc.jar") }
            } else {
                matches
            }
        exact.shouldHaveSize(1)
        return exact.single()
    }

    private val pom: Document by lazy {
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(artefact(".pom"))
            .also { it.documentElement.normalize() }
    }

    private fun Element.child(name: String): Element? =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().firstOrNull { it.tagName == name }

    private fun Element.children(name: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == name }

    private fun Element.text(path: String): String? =
        path
            .split('/')
            .fold(this as Element?) { element, name -> element?.child(name) }
            ?.textContent
            ?.trim()

    private fun entries(file: File): Set<String> =
        JarFile(file).use { jar ->
            jar
                .entries()
                .asSequence()
                .map { it.name }
                .toSet()
        }

    private fun read(
        file: File,
        entry: String,
    ): String = JarFile(file).use { jar -> jar.getInputStream(jar.getJarEntry(entry)).bufferedReader().readText() }

    @Test
    fun `POM has the coordinates group, double-entry and the project version`() {
        val project = pom.documentElement
        project.text("groupId") shouldBe group
        project.text("artifactId") shouldBe ARTIFACT
        project.text("version") shouldBe version
        project.text("packaging").orEmpty().ifEmpty { "jar" } shouldBe "jar"
        // U-010 proposal: com.softberries, version line 3.x.
        group shouldBe "com.softberries"
        version.substringBefore('.') shouldBe "3"
    }

    @Test
    fun `POM has name, description, url, MIT license as the gem, SCM and developers`() {
        val project = pom.documentElement
        project.text("name") shouldBe ARTIFACT
        project.text("description").orEmpty() shouldContain "double_entry"
        project.text("url") shouldBe REPO_URL

        val licenses = project.child("licenses")!!.children("license")
        licenses.shouldHaveSize(1)
        licenses.single().text("name") shouldBe "MIT License"
        licenses.single().text("url") shouldBe "https://opensource.org/licenses/MIT"

        project.text("scm/url") shouldBe REPO_URL
        project.text("scm/connection") shouldBe "scm:git:$REPO_URL.git"
        project.text("scm/developerConnection") shouldBe "scm:git:ssh://git@github.com/softberries/double-entry-kotlin.git"
        project.text("scm/tag") shouldBe "v$version"
        project.text("issueManagement/url") shouldBe "$REPO_URL/issues"

        val developers = project.child("developers")!!.children("developer")
        developers.map { it.text("id") } shouldContainAll listOf("envato", "softberries")
        developers.first { it.text("id") == "envato" }.text("email") shouldBe "rubygems@envato.com"
    }

    @Test
    fun `POM declares the runtime dependencies and imports the Spring Boot BOM, without test dependencies`() {
        val project = pom.documentElement
        val dependencies =
            project.child("dependencies")!!.children("dependency").map {
                "${it.text("groupId")}:${it.text("artifactId")}:${it.text("scope")}"
            }
        dependencies shouldContain "org.springframework.boot:spring-boot-starter-data-jpa:compile"
        dependencies shouldContain "io.micrometer:micrometer-core:compile"
        dependencies shouldContain "org.flywaydb:flyway-core:runtime"
        dependencies shouldContain "org.postgresql:postgresql:runtime"

        val managed =
            project.child("dependencyManagement")!!.child("dependencies")!!.children("dependency").map {
                "${it.text("groupId")}:${it.text("artifactId")}:${it.text("scope")}"
            }
        managed shouldContain "org.springframework.boot:spring-boot-dependencies:import"

        val pomText = artefact(".pom").readText()
        listOf("testcontainers", "kotest", "mockk", "spring-boot-starter-test", "datasource-proxy").forEach {
            pomText shouldNotContain it
        }
    }

    @Test
    fun `Gradle module metadata publishes no test-fixtures variant`() {
        val module = artefact(".module").readText()
        module shouldContain "\"apiElements\""
        module shouldContain "\"runtimeElements\""
        module shouldContain "\"sourcesElements\""
        module shouldContain "\"javadocElements\""
        module shouldNotContain "testFixtures"
    }

    @Test
    fun `library jar contains the auto-configuration imports file, configuration metadata, migrations and the license`() {
        val jar = artefact(".jar")
        val entries = entries(jar)
        entries shouldContain AUTO_CONFIGURATION_IMPORTS
        read(jar, AUTO_CONFIGURATION_IMPORTS).lines().map { it.trim() } shouldContain
            "com.softberries.doubleentry.autoconfigure.DoubleEntryAutoConfiguration"
        entries shouldContain "com/softberries/doubleentry/autoconfigure/DoubleEntryAutoConfiguration.class"
        entries shouldContain "com/softberries/doubleentry/DoubleEntry.class"
        entries shouldContain "META-INF/spring.factories"

        entries shouldContain "META-INF/spring-configuration-metadata.json"
        val metadata = read(jar, "META-INF/spring-configuration-metadata.json")
        listOf(
            "double-entry.account-identifier-max-length",
            "double-entry.scope-identifier-max-length",
            "double-entry.code-max-length",
            "double-entry.default-currency",
            "double-entry.flyway.enabled",
        ).forEach { metadata shouldContain "\"$it\"" }

        entries shouldContain "db/double_entry/migration/V1__double_entry_baseline.sql"
        entries shouldContain "db/double_entry/migration/V2__account_balances_unique_nulls_not_distinct.sql"
        entries shouldContain "money/currencies.json"

        entries shouldContain "META-INF/LICENSE"
        read(jar, "META-INF/LICENSE") shouldContain "Copyright © 2014 Envato Pty Ltd"
        JarFile(jar).use { it.manifest.mainAttributes.getValue("Implementation-Version") } shouldBe version

        // Test fixtures stay out of the library jar.
        entries.filter { it.startsWith("com/softberries/doubleentry/testing/") } shouldBe emptyList()
    }

    @Test
    fun `sources and javadoc jars are published next to the library jar`() {
        val sources = entries(artefact("-sources.jar"))
        sources shouldContain "com/softberries/doubleentry/DoubleEntry.kt"
        sources shouldContain "com/softberries/doubleentry/autoconfigure/DoubleEntryAutoConfiguration.kt"
        sources shouldContain "META-INF/LICENSE"

        val javadoc = entries(artefact("-javadoc.jar"))
        javadoc.shouldNotBeEmpty()
        javadoc shouldContain "META-INF/MANIFEST.MF"
    }

    private companion object {
        const val ARTIFACT = "double-entry"
        const val REPO_URL = "https://github.com/softberries/double-entry-kotlin"
        const val AUTO_CONFIGURATION_IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
    }
}
