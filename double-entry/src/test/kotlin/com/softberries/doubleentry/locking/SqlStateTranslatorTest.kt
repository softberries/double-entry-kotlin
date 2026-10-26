package com.softberries.doubleentry.locking

import com.softberries.doubleentry.locking.errors.LockWaitTimeout
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import jakarta.persistence.PersistenceException
import org.hibernate.exception.LockAcquisitionException
import org.junit.jupiter.api.Test
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.BadSqlGrammarException
import java.sql.BatchUpdateException
import java.sql.SQLException

/**
 * D-008: classification by SQLSTATE only. Ports `spec/double_entry/locking_spec.rb` [1:12:1:1] and [1:12:2:1] at the
 * translator level (the stubbed error is an SQLSTATE instead of a message); the real PostgreSQL lock timeout is in
 * [LockingExtensionsIntegrationTest].
 */
class SqlStateTranslatorTest {
    private fun sql(
        state: String?,
        message: String = "ERROR",
    ) = SQLException(message, state)

    @Test
    fun `finds the SQLSTATE through Spring, JPA and Hibernate wrappers`() {
        val deadlock = sql("40P01")
        val wrapped =
            CannotAcquireLockException(
                "could not execute statement",
                PersistenceException("tx", LockAcquisitionException("lock", deadlock)),
            )

        SqlStateTranslator.sqlStateOf(wrapped) shouldBe "40P01"
        SqlStateTranslator.isDeadlock(wrapped) shouldBe true
        SqlStateTranslator.sqlStateOf(DuplicateKeyException("dup", sql("23505"))) shouldBe "23505"
        SqlStateTranslator.isLockWaitTimeout(RuntimeException(sql("55P03"))) shouldBe true
    }

    @Test
    fun `the first SQLException with a state wins, following nextException for batch errors`() {
        val batch = BatchUpdateException("batch", null, 0, intArrayOf()).apply { nextException = sql("23505") }

        SqlStateTranslator.sqlStateOf(RuntimeException(batch)) shouldBe "23505"
        SqlStateTranslator.sqlStateOf(RuntimeException(sql("42P01", "deadlock detected"))) shouldBe "42P01"
    }

    @Test
    fun `message text is never used`() {
        // Ruby matched these texts (`locking_extensions.rb:20,48,63`, `locking.rb:46`); they classify as nothing here.
        listOf("PG::Error: ERROR:  deadlock detected", "duplicate key value", "lock wait timeout").forEach { text ->
            val error = RuntimeException(text, sql(null, text))
            SqlStateTranslator.sqlStateOf(error).shouldBeNull()
            SqlStateTranslator.isDeadlock(error) shouldBe false
            SqlStateTranslator.isLockWaitTimeout(error) shouldBe false
            SqlStateTranslator.translate(error) shouldBeSameInstanceAs error
        }
        SqlStateTranslator.isDeadlock(sql("42P01", "deadlock detected")) shouldBe false
    }

    @Test
    fun `a cyclic cause chain terminates`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        SqlStateTranslator.sqlStateOf(a).shouldBeNull()
    }

    @Test
    fun `locking_spec 1-12-1-1 a non lock wait timeout error is rethrown unchanged`() {
        val error = BadSqlGrammarException("select", "SELECT * FROM missing", sql("42P01"))

        val thrown = shouldThrow<BadSqlGrammarException> { SqlStateTranslator.translatingLockWaitTimeout { throw error } }

        thrown shouldBeSameInstanceAs error
    }

    @Test
    fun `locking_spec 1-12-2-1 a lock wait timeout becomes LockWaitTimeout with the Ruby class name as message`() {
        val error = CannotAcquireLockException("canceling statement due to lock timeout", sql("55P03"))

        val thrown = shouldThrow<LockWaitTimeout> { SqlStateTranslator.translatingLockWaitTimeout { throw error } }

        thrown.message shouldBe "DoubleEntry::Locking::LockWaitTimeout"
        thrown.cause shouldBeSameInstanceAs error
    }

    @Test
    fun `a checked SQLException thrown straight from JDBC is translated too`() {
        val timeout = sql("55P03")
        val thrown = shouldThrow<LockWaitTimeout> { SqlStateTranslator.translatingLockWaitTimeout { throw timeout } }
        thrown.cause shouldBeSameInstanceAs timeout

        val other = sql("42P01")
        shouldThrow<SQLException> { SqlStateTranslator.translatingLockWaitTimeout { throw other } } shouldBeSameInstanceAs other
    }

    @Test
    fun `the block value is returned when nothing is thrown`() {
        SqlStateTranslator.translatingLockWaitTimeout { 42 } shouldBe 42
    }
}
