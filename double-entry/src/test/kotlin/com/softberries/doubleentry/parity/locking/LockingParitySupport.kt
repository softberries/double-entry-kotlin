package com.softberries.doubleentry.parity.locking

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.softberries.doubleentry.config.DoubleEntryTables
import com.softberries.doubleentry.parity.Comparison
import com.softberries.doubleentry.parity.NotificationCapture
import com.softberries.doubleentry.parity.ObservedNotification
import com.softberries.doubleentry.parity.OracleException
import com.softberries.doubleentry.parity.OracleNotification
import com.softberries.doubleentry.parity.SqlProtocolStep
import com.softberries.doubleentry.parity.config.KotlinOutcome
import com.softberries.doubleentry.testing.MigrationSpec
import kotlin.io.path.readLines

/** How a scenario is compared with the oracle. */
enum class Compared {
    /** Result or exception, every `db_after` row, the SQL protocol (D-017) and the notifications in order. */
    EXACT,

    /**
     * For the threaded scenarios, whose interleaving is random (issue note: assert final state only): result,
     * [FinalState] of the tables, and notifications as a set of (event, unique-violation key) that may occur. The
     * oracle's SQL log is truncated there (`sql_truncated: true`), so there is no protocol to compare.
     */
    FINAL_STATE,
}

/**
 * The Kotlin expectation of a deviation (from `docs/decisions.md`). Unset fields default to the oracle's value;
 * [check] replaces the result/exception comparison where the outcome is not an oracle-shaped value.
 */
data class LockingExpectation(
    val result: JsonNode? = null,
    val exception: OracleException? = null,
    val check: ((KotlinOutcome) -> Comparison)? = null,
    val dbAfter: Map<String, List<ObjectNode>>? = null,
    val sql: List<SqlProtocolStep>? = null,
    val finalState: FinalState? = null,
    val allowedNotifications: Set<ObservedNotificationKey>? = null,
)

/** A notification reduced to what is stable across concurrent runs: event name and PostgreSQL `Key (…)=(…)` detail. */
data class ObservedNotificationKey(
    val event: String,
    val key: String?,
) {
    companion object {
        private val PG_KEY = Regex("Key \\(([^)]*)\\)=\\(([^)]*)\\)")

        fun of(
            event: String,
            exception: String?,
        ) = ObservedNotificationKey(event, exception?.let { PG_KEY.find(it)?.value })

        fun of(notification: OracleNotification) = of(notification.event, notification.exception)

        fun of(notification: ObservedNotification) = of(notification.event, notification.exception)
    }
}

/** Line count and the balance rows as `account@scope=balance`, sorted: the end state of a threaded run. */
data class FinalState(
    val lines: Int,
    val balanceRows: List<String>,
) {
    companion object {
        fun of(db: Map<String, List<ObjectNode>>): FinalState =
            FinalState(
                db.getValue(DoubleEntryTables.LINES).size,
                db
                    .getValue(DoubleEntryTables.ACCOUNT_BALANCES)
                    .map { row ->
                        val scope = row["scope"].takeUnless { it.isNull }?.asText()
                        "${row["account"].asText()}@$scope=${row["balance"].asLong()}"
                    }.sorted(),
            )

        fun compare(
            expected: FinalState,
            actual: FinalState,
        ): Comparison =
            Comparison.of(
                "lines: expected ${expected.lines} but was ${actual.lines}".takeUnless { expected.lines == actual.lines },
                "balance rows: expected ${expected.balanceRows} but was ${actual.balanceRows}"
                    .takeUnless { expected.balanceRows == actual.balanceRows },
            )
    }
}

/** Notifications of a threaded run: every observed (event, key) must be one the oracle (or the expectation) allows. */
object NotificationSets {
    fun compare(
        allowed: Set<ObservedNotificationKey>,
        actual: List<ObservedNotification>,
    ): Comparison {
        val unexpected = actual.map(ObservedNotificationKey::of).toSet() - allowed
        return Comparison.of("notifications not in $allowed: $unexpected".takeUnless { unexpected.isEmpty() })
    }

    fun exact(
        expected: List<OracleNotification>,
        actual: List<ObservedNotification>,
    ): Comparison = NotificationCapture.compare(expected, actual)
}

/**
 * The PostgreSQL error of a failed insert in a form both runtimes produce: `SQLSTATE <code>: <server message>`.
 * Ruby renders `ActiveRecord::RecordNotUnique: PG::UniqueViolation: ERROR:  <message>`; the `PG::` class names a
 * SQLSTATE (pg gem `PG::UniqueViolation` = `23505`), which is what the Kotlin side reports (D-008).
 */
object PgError {
    private val RUBY_PG_ERROR = Regex("^[\\w:]+: (PG::\\w+): ERROR:\\s+(.*)$")
    private val SQLSTATES = mapOf("PG::UniqueViolation" to "23505")

    fun render(
        sqlState: String?,
        serverMessage: String?,
    ): String = "SQLSTATE $sqlState: $serverMessage"

    /** Rewrites a Ruby rendering ([RUBY_PG_ERROR]) to [render]; anything else is returned unchanged. */
    fun fromRuby(node: JsonNode): JsonNode {
        val match = RUBY_PG_ERROR.find(node.asText()) ?: return node
        val sqlState = SQLSTATES[match.groupValues[1]] ?: return node
        return TextNode(render(sqlState, match.groupValues[2]))
    }
}

/**
 * The parts of a `script/jack_hammer` run (`oracle/concurrency/jack_hammer_*.txt`) that both runtimes can produce:
 * its shape (workers, mode, flushes, transfers) and the reconciliation output after `Reconciling...`.
 */
data class JackHammerOutcome(
    val workers: Int,
    val workerMode: String,
    val flushes: Int,
    val transfers: Int,
    val reconciliation: List<String>,
    val exitCode: Int?,
) {
    companion object {
        const val LINES_WRITTEN = "All the Line records were written, FTW!"
        const val LINES_MISSING = "Not enough Line records written. :("
        const val ALL_RECONCILED = "All accounts reconciled, FTW!"
        const val DONE = "Done successfully :)"

        private val SPAWNING = Regex("^Spawning (\\d+) processes\\.\\.\\.$")
        private val MODE = Regex("^Using (\\w+) as workers$")
        private val RUNNING = Regex("^Process \\d+ running (\\d+) transfers\\.\\.\\.$")
        private val EXIT = Regex("^\\S+ exit=(\\d+)$")

        fun load(name: String): JackHammerOutcome = parse(MigrationSpec.dir.resolve("oracle/concurrency/$name.txt").readLines())

        fun parse(lines: List<String>): JackHammerOutcome {
            val trimmed = lines.map(String::trim)
            val modes = trimmed.mapNotNull { MODE.find(it)?.groupValues?.get(1) }.distinct()
            val afterReconciling = trimmed.dropWhile { it != "Reconciling..." }.drop(1)
            return JackHammerOutcome(
                workers =
                    trimmed.firstNotNullOf {
                        SPAWNING
                            .find(it)
                            ?.groupValues
                            ?.get(1)
                            ?.toInt()
                    },
                workerMode = modes.single(),
                flushes = trimmed.count { it == "Flushing balances" },
                transfers =
                    trimmed.sumOf {
                        RUNNING
                            .find(it)
                            ?.groupValues
                            ?.get(1)
                            ?.toInt() ?: 0
                    },
                reconciliation = afterReconciling.takeWhile { it.isNotEmpty() },
                exitCode =
                    trimmed.firstNotNullOfOrNull {
                        EXIT
                            .find(it)
                            ?.groupValues
                            ?.get(1)
                            ?.toInt()
                    },
            )
        }
    }
}
