package com.softberries.doubleentry.ledger

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import java.time.LocalDateTime
import java.time.ZoneOffset

/** JPQL implementation of [LineCriteriaQueries]; every value is a bind parameter. */
class LineCriteriaQueriesImpl : LineCriteriaQueries {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun findIdAndCreatedAt(criteria: LineCriteria): List<IdAndCreatedAt> {
        val conditions = mutableListOf<String>()
        val parameters = mutableMapOf<String, Any>()

        fun condition(
            name: String,
            value: Any?,
            jpql: String,
        ) {
            if (value == null) return
            conditions += jpql
            parameters[name] = value
        }
        condition("account", criteria.account, "l.account = :account")
        condition("scope", criteria.scope, "l.scope = :scope")
        condition("code", criteria.code, "l.code = :code")
        condition("createdAtFrom", criteria.createdAtFrom?.toUtc(), "l.createdAt >= :createdAtFrom")
        condition("createdAtTo", criteria.createdAtTo?.toUtc(), "l.createdAt <= :createdAtTo")
        condition("idFrom", criteria.idFrom, "l.id >= :idFrom")
        condition("idTo", criteria.idTo, "l.id <= :idTo")

        val where = if (conditions.isEmpty()) "" else " where " + conditions.joinToString(" and ")
        val query =
            entityManager.createQuery(
                "select l.id, l.createdAt from LineEntity l$where order by l.id",
                Array<Any>::class.java,
            )
        parameters.forEach { (name, value) -> query.setParameter(name, value) }
        return query.resultList.map { row ->
            IdAndCreatedAt(row[0] as Long, (row[1] as LocalDateTime).toInstant(ZoneOffset.UTC))
        }
    }

    private fun java.time.Instant.toUtc(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
}
