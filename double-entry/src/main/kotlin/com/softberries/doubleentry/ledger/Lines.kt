package com.softberries.doubleentry.ledger

import com.softberries.doubleentry.account.AccountResolver

/** Line queries returning [Line] read models (BR-080..084). */
class Lines(
    private val repository: LineRepository,
    private val resolver: AccountResolver,
) {
    fun find(id: Long): Line? = repository.findById(id)?.let(::toLine)

    fun all(): List<Line> = repository.findAllByOrderByIdAsc().map(::toLine)

    /** amount > 0 (BR-081, D-009). */
    fun credits(): List<Line> = repository.credits().map(::toLine)

    /** amount < 0 (BR-081, D-009). */
    fun debits(): List<Line> = repository.debits().map(::toLine)

    /** id > [id] (BR-082). */
    fun withIdGreaterThan(id: Long): List<Line> = repository.withIdGreaterThan(id).map(::toLine)

    /** BR-084, see [LineRepository.findIdAndCreatedAt]. */
    fun findIdAndCreatedAt(criteria: LineCriteria): List<IdAndCreatedAt> = repository.findIdAndCreatedAt(criteria)

    /**
     * `line.partner` = `Line.find(partner_id)` (BR-083).
     *
     * @throws NoSuchElementException if the line has no partner id or the partner row does not exist
     *   (Ruby `ActiveRecord::RecordNotFound`).
     */
    fun partner(line: Line): Line {
        val partnerId = line.partnerId ?: throw NoSuchElementException("Couldn't find DoubleEntry::Line without an ID")
        return find(partnerId) ?: throw NoSuchElementException("Couldn't find DoubleEntry::Line with 'id'=$partnerId")
    }

    /**
     * `line.pair` (BR-083): `(self, partner)` if the line is a decrease, else `(partner, self)`; so the negative line
     * comes first, and a zero-amount line comes second.
     */
    fun pair(line: Line): Pair<Line, Line> = if (line.isDecrease) line to partner(line) else partner(line) to line

    fun toLine(entity: LineEntity): Line = Line.of(entity, resolver)
}
