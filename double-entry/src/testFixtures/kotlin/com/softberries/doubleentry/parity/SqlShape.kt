package com.softberries.doubleentry.parity

/** The protocol-relevant facts of one statement (D-017): what is compared between Ruby and Kotlin SQL. */
data class SqlProtocolStep(
    val table: String?,
    val verb: String,
    val whereColumns: List<String>,
    val forUpdate: Boolean,
)

/**
 * Shape of one SQL statement, extracted the same way from ActiveRecord log lines and from JDBC statements.
 * Hibernate and ActiveRecord SQL text differ, so only these facts are kept: [table], [verb], [whereColumns] in
 * predicate order (`col` for comparisons, `col IS NULL` / `col IS NOT NULL`), [lock] (`FOR UPDATE`,
 * `FOR NO KEY UPDATE`, …), [orderBy] (`id ASC`) and the resolved [limit].
 */
data class SqlShape(
    val table: String?,
    val verb: String,
    val whereColumns: List<String> = emptyList(),
    val lock: String? = null,
    val orderBy: List<String> = emptyList(),
    val limit: Long? = null,
) {
    /** Only a plain `FOR UPDATE` counts: the Ruby gem's lock strength (BR-060..071). */
    val forUpdate: Boolean get() = lock == "FOR UPDATE"

    val step: SqlProtocolStep get() = SqlProtocolStep(table, verb, whereColumns, forUpdate)

    val isTransactionControl: Boolean get() = verb in TRANSACTION_VERBS

    companion object {
        val TRANSACTION_VERBS = setOf("BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE SAVEPOINT", "ROLLBACK TO SAVEPOINT")
        private val SPACES = Regex("\\s+")
        private val CLAUSE_END = Regex("\\s(ORDER BY|LIMIT|FOR|FETCH|OFFSET|RETURNING|GROUP BY)\\s", RegexOption.IGNORE_CASE)
        private val WHERE = Regex("\\sWHERE\\s", RegexOption.IGNORE_CASE)
        private val AND_OR = Regex("\\s(?:AND|OR)\\s", RegexOption.IGNORE_CASE)
        private val PREDICATE =
            Regex("^\\(*\\s*([\\w.]+)\\s*(IS NOT NULL|IS NULL|=|<>|!=|<=|>=|<|>|IN\\b|LIKE\\b)?", RegexOption.IGNORE_CASE)
        private val LOCK = Regex("\\sFOR\\s+(UPDATE|NO KEY UPDATE|SHARE|KEY SHARE)\\b", RegexOption.IGNORE_CASE)
        private val ORDER_BY = Regex("\\sORDER BY\\s(.+?)(?=\\sLIMIT\\s|\\sFOR\\s|\\sFETCH\\s|\\sOFFSET\\s|$)", RegexOption.IGNORE_CASE)
        private val LIMIT = Regex("\\s(?:LIMIT|FETCH FIRST)\\s+(\\$\\d+|\\?|\\d+)", RegexOption.IGNORE_CASE)
        private val TABLE_AFTER =
            mapOf("SELECT" to "FROM", "DELETE" to "FROM", "INSERT" to "INTO", "UPDATE" to "UPDATE", "TRUNCATE" to "TRUNCATE")

        /** Parses [sql]; [binds] are the bound values in order (`$n` → `binds[n-1]`, the k-th `?` → `binds[k]`). */
        fun parse(
            sql: String,
            binds: List<Any?> = emptyList(),
        ): SqlShape {
            val s =
                sql
                    .replace("\"", "")
                    .replace(SPACES, " ")
                    .trim()
                    .removeSuffix(";")
            val words = s.split(' ')
            val first = words.first().uppercase()
            val verb =
                when {
                    first == "ROLLBACK" && words.getOrNull(1).equals("TO", ignoreCase = true) -> "ROLLBACK TO SAVEPOINT"
                    first == "RELEASE" -> "RELEASE SAVEPOINT"
                    first == "START" -> "BEGIN"
                    else -> first
                }
            val table =
                TABLE_AFTER[verb]?.let { keyword ->
                    Regex("\\b$keyword\\s+(?:TABLE\\s+)?([\\w.]+)", RegexOption.IGNORE_CASE)
                        .find(s)
                        ?.groupValues
                        ?.get(1)
                        ?.substringAfterLast('.')
                        ?.lowercase()
                }
            return SqlShape(
                table = table,
                verb = verb,
                whereColumns = whereColumns(s),
                lock =
                    LOCK
                        .find(s)
                        ?.groupValues
                        ?.get(1)
                        ?.let { "FOR " + it.uppercase().replace(SPACES, " ") },
                orderBy = orderBy(s),
                limit = LIMIT.find(s)?.let { resolve(it.groupValues[1], s.substring(0, it.range.first), binds) },
            )
        }

        private fun whereColumns(s: String): List<String> {
            val start = WHERE.find(s) ?: return emptyList()
            val rest = s.substring(start.range.last + 1)
            val clause = CLAUSE_END.find(rest)?.let { rest.substring(0, it.range.first) } ?: rest
            return clause.split(AND_OR).mapNotNull { predicate ->
                PREDICATE.find(predicate.trim())?.let { match ->
                    val column = match.groupValues[1].substringAfterLast('.').lowercase()
                    when (val op = match.groupValues[2].uppercase()) {
                        "IS NULL", "IS NOT NULL" -> "$column $op"
                        else -> column
                    }
                }
            }
        }

        private fun orderBy(s: String): List<String> =
            ORDER_BY.find(s)?.groupValues?.get(1)?.split(',')?.map { term ->
                val parts = term.trim().split(' ')
                val direction = parts.getOrNull(1)?.uppercase()?.takeIf { it == "ASC" || it == "DESC" } ?: "ASC"
                parts[0].substringAfterLast('.').lowercase() + " " + direction
            } ?: emptyList()

        private fun resolve(
            token: String,
            before: String,
            binds: List<Any?>,
        ): Long? =
            when {
                token.startsWith("$") -> binds.getOrNull(token.drop(1).toInt() - 1)
                token == "?" -> binds.getOrNull(before.count { it == '?' })
                else -> token
            }?.toString()?.toLongOrNull()
    }
}
