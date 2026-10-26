package com.softberries.doubleentry.ledger

import com.softberries.doubleentry.config.DoubleEntryTables
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/**
 * A row of the append-only ledger `double_entry_lines` (`DoubleEntry::Line`, `line.rb:57`; `docs/50-data.md` §1).
 *
 * Columns hold the raw stored values: identifiers and scope identities as strings, amounts as minor units, timestamps
 * as UTC wall-clock time. [Line] is the read model with Money and account accessors. Setters are internal, so only
 * the library changes rows, and only through [LineWriter], which runs the BR-030 guard on every save.
 *
 * There is no JPA association: `partner_id` and the detail are plain columns (D-015).
 */
@Entity
@Table(name = DoubleEntryTables.LINES)
class LineEntity internal constructor(
    account: String,
    scope: String?,
    code: String,
    amount: Long,
    balance: Long,
    partnerAccount: String,
    partnerScope: String?,
    partnerId: Long? = null,
    detail: DetailRef? = null,
    metadata: Map<String, Any?>? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        internal set

    /** Account identifier (`line.rb:99-104`). */
    @Column(name = "account", nullable = false)
    var account: String = account
        internal set

    /** Scope identity of [account], null for unscoped accounts. */
    @Column(name = "scope")
    var scope: String? = scope
        internal set

    /** Transfer code (`line.rb:90-97`; a Symbol in Ruby). */
    @Column(name = "code", nullable = false)
    var code: String = code
        internal set

    /** Minor units (`Money#fractional`, `line.rb:68-70`). */
    @Column(name = "amount", nullable = false)
    var amount: Long = amount
        internal set

    /** Running balance of [account] after this line, in minor units. */
    @Column(name = "balance", nullable = false)
    var balance: Long = balance
        internal set

    @Column(name = "partner_account", nullable = false)
    var partnerAccount: String = partnerAccount
        internal set

    @Column(name = "partner_scope")
    var partnerScope: String? = partnerScope
        internal set

    /** Id of the other line of the transfer; a plain column, not an association. */
    @Column(name = "partner_id")
    var partnerId: Long? = partnerId
        internal set

    @Embedded
    var detail: DetailRef? = detail
        internal set

    /** jsonb with string keys (D-004, BR-028); null is SQL NULL, an empty map is `{}`. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    var metadata: Map<String, Any?>? = metadata
        internal set

    /**
     * Set by [LineWriter] from `doubleEntryClock` (BR-035). `LOCAL_DATE_TIME` binds the value as is: the default
     * `TIMESTAMP` binding plus `hibernate.jdbc.time_zone=UTC` would shift it by the JVM's offset.
     */
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: LocalDateTime
        internal set

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: LocalDateTime
        internal set

    override fun equals(other: Any?): Boolean = other is LineEntity && id != null && id == other.id

    override fun hashCode(): Int = LineEntity::class.hashCode()

    override fun toString(): String =
        "LineEntity(id=$id, account=$account, scope=$scope, code=$code, amount=$amount, balance=$balance, " +
            "partnerId=$partnerId, partnerAccount=$partnerAccount, partnerScope=$partnerScope, detail=$detail)"
}
