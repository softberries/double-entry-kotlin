package com.softberries.doubleentry.validation

import java.time.Instant
import java.time.ZoneOffset

/** Read model of a `double_entry_line_checks` row: an immutable snapshot of [LineCheckEntity]. */
data class LineCheck(
    val id: Long,
    val lastLineId: Long,
    val errorsFound: Boolean,
    val log: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    internal companion object {
        fun of(entity: LineCheckEntity): LineCheck =
            LineCheck(
                id = requireNotNull(entity.id) { "line check is not persisted" },
                lastLineId = entity.lastLineId,
                errorsFound = entity.errorsFound,
                log = entity.log,
                createdAt = entity.createdAt.toInstant(ZoneOffset.UTC),
                updatedAt = entity.updatedAt.toInstant(ZoneOffset.UTC),
            )
    }
}
