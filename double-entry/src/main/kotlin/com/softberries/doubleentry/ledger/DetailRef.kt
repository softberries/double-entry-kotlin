package com.softberries.doubleentry.ledger

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * The host object a line refers to, stored as (`detail_type`, `detail_id`). Replaces the Ruby polymorphic
 * `belongs_to :detail` (`line.rb:58`): the library never loads the target (D-015). Hosts keep the Ruby class-name
 * strings (e.g. `"Order"`) for existing types so Ruby- and Kotlin-written rows stay interchangeable (D-003).
 */
@Embeddable
data class DetailRef(
    @Column(name = "detail_type")
    val type: String,
    @Column(name = "detail_id")
    val id: Long,
)
