package com.softberries.doubleentry.validation

import org.springframework.data.repository.Repository

/** Read-only queries on `double_entry_line_checks`; rows are written by [LineCheckService] only. */
interface LineCheckRepository : Repository<LineCheckEntity, Long> {
    fun findById(id: Long): LineCheckEntity?

    fun findAllByOrderByIdAsc(): List<LineCheckEntity>

    fun count(): Long

    /** `order('created_at DESC').limit(1)` (`line_check.rb:9`): the newest check by created_at, not by id (BR-090). */
    fun findFirstByOrderByCreatedAtDesc(): LineCheckEntity?
}
