package com.softberries.doubleentry.parity

import com.softberries.doubleentry.testing.MigrationSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines

/** The harness must compile with only M-001 merged: it imports nothing from the library's feature packages. */
class HarnessIndependenceTest {
    @Test
    fun `parity fixtures import only the parity and testing packages of the library`() {
        val sources =
            MigrationSpec.dir.parent
                .resolve("double-entry/src/testFixtures/kotlin/com/softberries/doubleentry/parity")
                .listDirectoryEntries("*.kt")
        sources.shouldNotBeEmpty()

        sources.forEach { file ->
            val featureImports =
                file
                    .readLines()
                    .filter { it.startsWith("import com.softberries.doubleentry.") }
                    .filterNot { it.startsWith("import com.softberries.doubleentry.parity.") }
                    .filterNot { it.startsWith("import com.softberries.doubleentry.testing.MigrationSpec") }
                    .filterNot { it.startsWith("import com.softberries.doubleentry.testing.TestUser") }
            withClue(file.name) { featureImports.shouldBeEmpty() }
        }
    }
}
