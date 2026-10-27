package com.softberries.doubleentry.parity

import net.ttddyy.dsproxy.ExecutionInfo
import net.ttddyy.dsproxy.QueryInfo
import net.ttddyy.dsproxy.listener.MethodExecutionContext
import net.ttddyy.dsproxy.listener.MethodExecutionListener
import net.ttddyy.dsproxy.listener.QueryExecutionListener
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder
import org.springframework.beans.factory.config.BeanPostProcessor
import java.sql.Connection
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

/** One statement of an ActiveRecord log: SQL text (`$n` placeholders) and the bound values in order. */
data class RubyStatement(
    val sql: String,
    val binds: List<Any?>,
)

/** A statement a Kotlin run sent: its [shape] and the bound values in parameter order (none for transaction control). */
data class CapturedStatement(
    val shape: SqlShape,
    val binds: List<Any?>,
)

/**
 * Records the statements a Kotlin run sends over JDBC (datasource-proxy) and reduces them to [SqlShape]s, the same
 * reduction [fromRubyLog] applies to an oracle `sql` list. Transaction boundaries come from the connection calls:
 * `setAutoCommit(false)` → `BEGIN`, `commit()` → `COMMIT`, `rollback()` → `ROLLBACK`.
 *
 * Wire it with [wrap] for a plain [DataSource] or [beanPostProcessor] for the Spring context of a parity driver.
 */
class SqlCapture {
    private val recorded = CopyOnWriteArrayList<CapturedStatement>()

    /** Every recorded statement, in execution order. */
    val shapes: List<SqlShape> get() = recorded.map { it.shape }

    /** [shapes] with their bound values, e.g. to check which account each `FOR UPDATE` locked. */
    val statements: List<CapturedStatement> get() = recorded.toList()

    /** The protocol view of [shapes]: see [protocol]. */
    val protocolSteps: List<SqlProtocolStep> get() = protocol(shapes)

    fun clear() = recorded.clear()

    fun wrap(dataSource: DataSource): DataSource =
        ProxyDataSourceBuilder
            .create(dataSource)
            .name("parity-sql-capture")
            .listener(queryListener)
            .methodListener(connectionListener)
            .build()

    /** Wraps every [DataSource] bean of a Spring context with this capture. */
    fun beanPostProcessor(): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(
                bean: Any,
                beanName: String,
            ): Any = if (bean is DataSource) wrap(bean) else bean
        }

    private val queryListener =
        object : QueryExecutionListener {
            override fun beforeQuery(
                execInfo: ExecutionInfo,
                queryInfoList: List<QueryInfo>,
            ) = Unit

            override fun afterQuery(
                execInfo: ExecutionInfo,
                queryInfoList: List<QueryInfo>,
            ) {
                for (query in queryInfoList) {
                    val parameterSets = query.parametersList.ifEmpty { listOf(emptyList()) }
                    for (parameters in parameterSets) {
                        val binds =
                            parameters
                                .sortedBy { (it.args[0] as? Int) ?: 0 }
                                .map { if (it.method.name == "setNull") null else it.args.getOrNull(1) }
                        recorded += CapturedStatement(SqlShape.parse(query.query, binds), binds)
                    }
                }
            }
        }

    private val connectionListener =
        object : MethodExecutionListener {
            override fun beforeMethod(executionContext: MethodExecutionContext) = Unit

            override fun afterMethod(executionContext: MethodExecutionContext) {
                if (executionContext.target !is Connection || executionContext.thrown != null) return
                val args = executionContext.methodArgs ?: emptyArray()
                val verb =
                    when (executionContext.method.name) {
                        "setAutoCommit" -> "BEGIN".takeIf { args.firstOrNull() == false }
                        "commit" -> "COMMIT"
                        "rollback" -> if (args.isEmpty()) "ROLLBACK" else "ROLLBACK TO SAVEPOINT"
                        "setSavepoint" -> "SAVEPOINT"
                        "releaseSavepoint" -> "RELEASE SAVEPOINT"
                        else -> null
                    }
                if (verb != null) recorded += CapturedStatement(SqlShape(table = null, verb = verb), emptyList())
            }
        }

    companion object {
        private val LOG_LINE = Regex("^(.*?)\\s*\\(([\\d.]+)ms\\)\\s{2}(.*)$")
        private val TRAILING_BINDS = Regex("^(.*?)\\s{2}(\\[\\[.*]])$")

        /**
         * Splits the ActiveRecord log lines of an oracle `sql` list (`<Model> <Op> (1.2ms)  <SQL>  [[binds]]`) into
         * statements with their bound values. Lines without the `(…ms)` header continue the previous statement
         * (multi-line raw SQL).
         */
        fun rubyStatements(lines: List<String>): List<RubyStatement> {
            val statements = mutableListOf<StringBuilder>()
            for (line in lines.map(String::trim).filter(String::isNotEmpty)) {
                val header = LOG_LINE.find(line)
                if (header != null) {
                    statements += StringBuilder(header.groupValues[3])
                } else {
                    statements.lastOrNull()?.append(' ')?.append(line)
                }
            }
            return statements.map { statement ->
                val text = statement.toString()
                TRAILING_BINDS.find(text)?.let { RubyStatement(it.groupValues[1], rubyBinds(it.groupValues[2])) }
                    ?: RubyStatement(text, emptyList())
            }
        }

        /** Reduces an oracle `sql` list to shapes, see [rubyStatements]. */
        fun fromRubyLog(lines: List<String>): List<SqlShape> = rubyStatements(lines).map { SqlShape.parse(it.sql, it.binds) }

        /** `[["account", "bank"], ["LIMIT", 1]]` → `["bank", 1]`; unparsable bind lists give no binds. */
        private fun rubyBinds(text: String): List<Any?> =
            runCatching { (RubyLiteral.parse(text) as List<*>).map { (it as List<*>).last() } }.getOrDefault(emptyList())

        /**
         * Keeps the statements on double_entry tables and the transaction control around them; drops other tables
         * (the oracle's test-only `users`) and transactions left empty by that filter. This is the sequence D-017
         * treats as protocol: lock order, `FOR UPDATE`, the insert/update sequence.
         */
        fun protocol(shapes: List<SqlShape>): List<SqlProtocolStep> {
            val kept = shapes.filter { it.isTransactionControl || it.table?.startsWith("double_entry_") == true }
            val result = mutableListOf<SqlShape>()
            for (shape in kept) {
                val previous = result.lastOrNull()
                if (shape.verb in setOf("COMMIT", "ROLLBACK") && previous?.verb == "BEGIN") {
                    result.removeAt(result.lastIndex)
                } else {
                    result += shape
                }
            }
            return result.map { it.step }
        }

        /**
         * Removes the `BEGIN`/`COMMIT` around transactions that contain only plain `SELECT`s (no `FOR UPDATE`). Spring
         * Data runs repository reads (e.g. `Lines.find`) in a read-only transaction where ActiveRecord reads in
         * autocommit; the wrapper changes neither lock order, `FOR UPDATE` nor the write sequence (D-017). Opt-in for
         * drivers whose scenarios read lines after writing them; apply it to both sides.
         */
        fun withoutReadOnlyTransactions(steps: List<SqlProtocolStep>): List<SqlProtocolStep> {
            val result = mutableListOf<SqlProtocolStep>()
            var i = 0
            while (i < steps.size) {
                val step = steps[i]
                val end = steps.indexOfFirstFrom(i + 1) { it.verb in setOf("COMMIT", "ROLLBACK", "BEGIN") }
                val body = if (step.verb == "BEGIN" && end > i + 1 && steps[end].verb == "COMMIT") steps.subList(i + 1, end) else null
                if (body != null && body.all { it.verb == "SELECT" && !it.forUpdate }) {
                    result += body
                    i = end + 1
                } else {
                    result += step
                    i++
                }
            }
            return result
        }

        private fun <T> List<T>.indexOfFirstFrom(
            from: Int,
            predicate: (T) -> Boolean,
        ): Int = (from until size).firstOrNull { predicate(this[it]) } ?: -1
    }
}
