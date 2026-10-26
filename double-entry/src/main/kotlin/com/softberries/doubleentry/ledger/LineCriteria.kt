package com.softberries.doubleentry.ledger

import java.time.Instant

/**
 * Typed filter for [LineRepository.findIdAndCreatedAt]; replaces the raw SQL `conditions:`/`joins:` options of
 * `Line.find_id_and_created_at` (`line.rb:147-152`, U-007). Null fields add no condition; all bounds are inclusive.
 */
data class LineCriteria(
    val account: String? = null,
    /** Scope identity; only compared when non-null (there is no `scope IS NULL` filter). */
    val scope: String? = null,
    val code: String? = null,
    val createdAtFrom: Instant? = null,
    val createdAtTo: Instant? = null,
    val idFrom: Long? = null,
    val idTo: Long? = null,
)

/** One row of [LineRepository.findIdAndCreatedAt]: `[id, created_at]` in Ruby. */
data class IdAndCreatedAt(
    val id: Long,
    val createdAt: Instant,
)
