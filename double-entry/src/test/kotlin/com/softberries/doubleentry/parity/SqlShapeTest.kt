package com.softberries.doubleentry.parity

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldStartWith
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SqlShapeTest {
    private val balances = "double_entry_account_balances"
    private val lines = "double_entry_lines"

    private fun lock(vararg where: String) = SqlProtocolStep(balances, "SELECT", where.toList(), forUpdate = true)

    private fun tx(verb: String) = SqlProtocolStep(null, verb, emptyList(), forUpdate = false)

    @Test
    fun `lock_order_sql reduces to the ordered lock protocol`() {
        val steps = SqlCapture.protocol(SqlCapture.fromRubyLog(OracleScenarios.load("lock_order_sql").sql))

        // The users fixture transaction is dropped; then bank, cash, store (scope NULL) and alice checking are locked in order.
        steps shouldStartWith
            listOf(
                tx("BEGIN"),
                lock("scope IS NULL", "account"),
                lock("scope IS NULL", "account"),
                lock("scope IS NULL", "account"),
                lock("scope", "account"),
                tx("COMMIT"),
                SqlProtocolStep(lines, "SELECT", listOf("account"), forUpdate = false),
                tx("BEGIN"),
                SqlProtocolStep(balances, "INSERT", emptyList(), forUpdate = false),
                tx("COMMIT"),
            )
        val lockOrder = SqlCapture.rubyStatements(OracleScenarios.load("lock_order_sql").sql).filter { "FOR UPDATE" in it.sql }
        lockOrder.take(4).map { it.binds.dropLast(1) } shouldContainExactly
            listOf(listOf("bank"), listOf("cash"), listOf("store"), listOf("1", "checking"))
    }

    @Test
    fun `ActiveRecord lock statement shape`() {
        val shape = SqlCapture.fromRubyLog(OracleScenarios.load("transfer_scoped_basic").sql)[4]

        shape shouldBe
            SqlShape(
                table = balances,
                verb = "SELECT",
                whereColumns = listOf("scope IS NULL", "account"),
                lock = "FOR UPDATE",
                orderBy = listOf("id ASC"),
                limit = 1,
            )
    }

    @Test
    fun `Hibernate-style SQL gives the same shape`() {
        val shape =
            SqlShape.parse(
                "select ab1_0.id,ab1_0.account,ab1_0.balance from double_entry_account_balances ab1_0 " +
                    "where ab1_0.scope is null and ab1_0.account=? order by ab1_0.id fetch first ? rows only for update",
                listOf("bank", 1),
            )

        shape shouldBe SqlShape(balances, "SELECT", listOf("scope IS NULL", "account"), "FOR UPDATE", listOf("id ASC"), 1)
        SqlShape.parse("select * from double_entry_account_balances where id=? for no key update", listOf(1)).forUpdate shouldBe false
    }

    @Test
    fun `insert, update, raw multi-line SQL and transaction control`() {
        SqlShape.parse("insert into double_entry_lines (account,amount) values (?,?) returning id").step shouldBe
            SqlProtocolStep(lines, "INSERT", emptyList(), false)
        SqlShape.parse("UPDATE \"double_entry_lines\" SET \"partner_id\" = \$1 WHERE \"double_entry_lines\".\"id\" = \$3").step shouldBe
            SqlProtocolStep(lines, "UPDATE", listOf("id"), false)
        SqlShape.parse("ROLLBACK TO SAVEPOINT active_record_1").verb shouldBe "ROLLBACK TO SAVEPOINT"

        val raw =
            SqlCapture.fromRubyLog(
                listOf(
                    "(0.7ms)          SELECT id, created_at FROM \"double_entry_lines\"",
                    "WHERE account = 'bank'",
                    "AND scope IS NULL",
                    "ORDER BY id DESC",
                    "LIMIT 1",
                ),
            )
        raw.single() shouldBe SqlShape(lines, "SELECT", listOf("account", "scope IS NULL"), null, listOf("id DESC"), 1)
    }

    @Test
    fun `every numbered placeholder of the oracle sql has a parsed bind`() {
        OracleScenarios.all().forEach { scenario ->
            SqlCapture.rubyStatements(scenario.sql).forEach { statement ->
                val highest = Regex("\\$(\\d+)").findAll(statement.sql).maxOfOrNull { it.groupValues[1].toInt() } ?: 0
                (statement.binds.size >= highest) shouldBe true
            }
        }
    }

    @Test
    fun `every oracle sql statement parses into a shape with a verb`() {
        OracleScenarios.all().forEach { scenario ->
            SqlCapture.fromRubyLog(scenario.sql).forEach { shape ->
                shape.verb.matches(Regex("[A-Z]+( [A-Z]+)*")) shouldBe true
            }
        }
    }
}
