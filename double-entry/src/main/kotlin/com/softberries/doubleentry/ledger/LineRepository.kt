package com.softberries.doubleentry.ledger

import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param

/**
 * Read-only access to `double_entry_lines` for hosts. There is no save: lines are written by [LineWriter] only, so the
 * BR-030 guard cannot be bypassed. Results are ordered by id (Ruby leaves the order to the database).
 * [Lines] turns the entities into [Line] read models.
 */
interface LineRepository :
    Repository<LineEntity, Long>,
    LineCriteriaQueries {
    fun findById(id: Long): LineEntity?

    fun findAllByOrderByIdAsc(): List<LineEntity>

    /** `Line.credits`: amount > 0, i.e. the destination lines; the inverted naming is kept (BR-081, D-009). */
    @Query("select l from LineEntity l where l.amount > 0 order by l.id")
    fun credits(): List<LineEntity>

    /** `Line.debits`: amount < 0, i.e. the source lines (BR-081, D-009). */
    @Query("select l from LineEntity l where l.amount < 0 order by l.id")
    fun debits(): List<LineEntity>

    /** `Line.with_id_greater_than(id)` (BR-082). */
    @Query("select l from LineEntity l where l.id > :id order by l.id")
    fun withIdGreaterThan(
        @Param("id") id: Long,
    ): List<LineEntity>
}

/** Query fragment of [LineRepository]. */
interface LineCriteriaQueries {
    /**
     * `Line.find_id_and_created_at` (BR-084): only id and created_at of the matching lines, ordered by id, without
     * loading entities. Takes typed [LineCriteria] instead of raw SQL (U-007).
     */
    fun findIdAndCreatedAt(criteria: LineCriteria): List<IdAndCreatedAt>
}
