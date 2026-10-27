package com.softberries.doubleentry.mixed

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.dockerjava.api.command.WaitContainerResultCallback
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.output.OutputFrame
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The Ruby side of [MixedWritersTest]: containers built from `src/test/resources/ruby-writer` (the double_entry gem at
 * the pinned commit, `writer.rb`). The image is tagged with a hash of its inputs and kept after the run
 * (`deleteOnExit = false`), so later runs reuse Docker's layer cache instead of reinstalling the gems (~2–3 min).
 */
object RubyWriter {
    private const val RESOURCE_DIR = "ruby-writer"
    private val FILES = listOf("Dockerfile", "Gemfile", "Gemfile.investigator", "writer.rb")
    private val mapper = jacksonObjectMapper()

    private val image: ImageFromDockerfile by lazy {
        FILES.fold(ImageFromDockerfile("double-entry-ruby-writer:${inputsHash()}", false)) { image, file ->
            image.withFileFromClasspath(file, "$RESOURCE_DIR/$file")
        }
    }

    /** A `writer.rb` container on [network]; [env] is added to `DATABASE_URL` and the time zone. */
    fun container(
        network: Network,
        databaseUrl: String,
        env: Map<String, String>,
    ): GenericContainer<*> =
        GenericContainer(image)
            .withNetwork(network)
            .withEnv("DATABASE_URL", databaseUrl)
            // Not UTC on purpose: both writers must store UTC wall-clock timestamps whatever the process zone is (BR-035).
            .withEnv("TZ", NON_UTC_ZONE)
            .withEnv(env)
            .waitingFor(Wait.forLogMessage("READY\\s*", 1).withStartupTimeout(Duration.ofMinutes(10)))

    /**
     * Waits until the started [container] exits, then returns its `SUMMARY` line. Fails with the container's output
     * if the writer exits non-zero or prints no summary.
     */
    fun awaitSummary(
        container: GenericContainer<*>,
        timeout: Duration,
    ): RubySummary {
        val exitCode =
            container.dockerClient
                .waitContainerCmd(container.containerId)
                .exec(WaitContainerResultCallback())
                .awaitStatusCode(timeout.toMillis(), TimeUnit.MILLISECONDS)
        val stdout = container.getLogs(OutputFrame.OutputType.STDOUT)
        val summary = stdout.lineSequence().lastOrNull { it.startsWith("SUMMARY ") }
        check(exitCode == 0 && summary != null) {
            "Ruby writer exited with $exitCode\n--- stdout ---\n$stdout\n--- stderr ---\n" +
                container.getLogs(OutputFrame.OutputType.STDERR).takeLast(8000)
        }
        return RubySummary(mapper.readTree(summary.removePrefix("SUMMARY ")))
    }

    private fun inputsHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in FILES) {
            val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("$RESOURCE_DIR/$file")) { "missing $RESOURCE_DIR/$file" }
            stream.use { digest.update(it.readBytes()) }
        }
        return digest.digest().take(6).joinToString("") { "%02x".format(it) }
    }

    const val NON_UTC_ZONE = "Australia/Melbourne"
}

/** The `SUMMARY {json}` line printed by `writer.rb`. */
class RubySummary(
    val json: JsonNode,
) {
    val errors: List<String> get() = json["errors"]?.map { it.asText() }.orEmpty()

    /** `{at_lines, deleted}` per balance flush. */
    val flushes: List<Pair<Long, Long>> get() = json["flushes"]?.map { it["at_lines"].asLong() to it["deleted"].asLong() }.orEmpty()

    val events: Map<String, Long> get() = json["events"]?.properties()?.associate { (k, v) -> k to v.asLong() }.orEmpty()

    /** `LineCheck.perform!` result in check mode; null if the check visited no line. */
    val lineCheck: JsonNode? get() = json["line_check"]?.takeUnless { it.isNull }

    override fun toString(): String = json.toString()
}
