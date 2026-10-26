package com.softberries.doubleentry.validation

import com.softberries.doubleentry.config.DoubleEntryTables
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/**
 * A row of `double_entry_line_checks` (`DoubleEntry::Validation::LineCheck`, `line_check.rb:6`; `docs/50-data.md` §3):
 * the result of one [LineCheckService.perform] run. Rows are written only by [LineCheckService] (BR-094); [LineCheck] is
 * the read model.
 */
@Entity
@Table(name = DoubleEntryTables.LINE_CHECKS)
class LineCheckEntity internal constructor(
    lastLineId: Long,
    errorsFound: Boolean,
    log: String?,
    now: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        internal set

    /** Id of the last line visited by the run (BR-090, BR-094). */
    @Column(name = "last_line_id", nullable = false)
    var lastLineId: Long = lastLineId
        internal set

    @Column(name = "errors_found", nullable = false)
    var errorsFound: Boolean = errorsFound
        internal set

    /** The error report (BR-095); `""` when nothing was wrong. The column is nullable, as in the Ruby schema. */
    @Column(name = "log")
    var log: String? = log
        internal set

    /** `LOCAL_DATE_TIME` binds the UTC wall-clock value as is (see [com.softberries.doubleentry.ledger.LineEntity]). */
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = now
        internal set

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = now
        internal set

    override fun equals(other: Any?): Boolean = other is LineCheckEntity && id != null && id == other.id

    override fun hashCode(): Int = LineCheckEntity::class.hashCode()

    override fun toString(): String =
        "LineCheckEntity(id=$id, lastLineId=$lastLineId, errorsFound=$errorsFound, createdAt=$createdAt, updatedAt=$updatedAt)"
}
