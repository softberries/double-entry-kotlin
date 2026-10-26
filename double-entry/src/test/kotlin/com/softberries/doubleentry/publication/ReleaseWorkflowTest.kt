package com.softberries.doubleentry.publication

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * M-017: `.github/workflows/release.yml` replaces `rake release[remote]`. It runs on tags `v*` only and publishes to
 * GitHub Packages with `GITHUB_TOKEN` (U-010 proposal).
 */
@Suppress("UNCHECKED_CAST")
class ReleaseWorkflowTest {
    private val file = File(System.getProperty("doubleentry.repoRoot"), ".github/workflows/release.yml")
    private val workflow: Map<String, Any?> = Yaml().load(file.readText())

    // YAML 1.1 (snakeyaml) reads the bare key `on` as the boolean true.
    private val triggers: Map<String, Any?> = (workflow["on"] ?: workflow[true as Any]) as Map<String, Any?>
    private val job: Map<String, Any?> = (workflow["jobs"] as Map<String, Any?>)["publish"] as Map<String, Any?>
    private val steps: List<Map<String, Any?>> = job["steps"] as List<Map<String, Any?>>

    @Test
    fun `runs only for pushed tags v-star, never for branches or pull requests`() {
        triggers.keys shouldContainExactly setOf("push")
        val push = triggers["push"] as Map<String, Any?>
        push.keys shouldContainExactly setOf("tags")
        push["tags"] shouldBe listOf("v*")
        (push.keys.contains("branches")) shouldBe false
        job["if"] shouldBe "startsWith(github.ref, 'refs/tags/v')"
    }

    @Test
    fun `rejects tags that are not vX_Y_Z before publishing`() {
        val versionStep = steps.first { it["id"] == "version" }
        val script = versionStep["run"] as String
        script shouldContain "^v([0-9]+\\.[0-9]+\\.[0-9]+"
        script shouldContain "exit 1"
        steps.indexOf(versionStep) shouldBe 0
    }

    @Test
    fun `publishes to GitHub Packages with GITHUB_TOKEN and the version from the tag after a full build`() {
        (job["permissions"] as Map<String, Any?>)["packages"] shouldBe "write"
        (workflow["permissions"] as Map<String, Any?>)["contents"] shouldBe "read"
        val publish = steps.first { it["name"] == "Build and publish" }
        val env = publish["env"] as Map<String, Any?>
        (env.keys.contains("GITHUB_TOKEN")) shouldBe true
        env["GITHUB_TOKEN"] shouldBe "\${{ secrets.GITHUB_TOKEN }}"
        val command = publish["run"] as String
        command shouldContain "./gradlew build "
        command shouldContain ":double-entry:publishMavenPublicationToGitHubPackagesRepository"
        command shouldContain "-Pversion=\${{ steps.version.outputs.version }}"
        // Only the GitHub Packages repository; never the build-local test repository or Maven Local.
        command shouldNotContain "BuildRepository"
        command shouldNotContain "publishToMavenLocal"
    }

    @Test
    fun `the build declares the GitHub Packages repository of this repository`() {
        val build = File(System.getProperty("doubleentry.repoRoot"), "double-entry/build.gradle.kts").readText()
        build shouldContain "name = \"GitHubPackages\""
        build shouldContain "https://maven.pkg.github.com/\$githubRepository"
        build shouldContain "val githubRepository = \"softberries/double-entry-kotlin\""
        build shouldContain "System.getenv(\"GITHUB_TOKEN\")"
    }
}
