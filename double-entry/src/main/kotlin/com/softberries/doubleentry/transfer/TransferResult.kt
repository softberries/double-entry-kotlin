package com.softberries.doubleentry.transfer

import com.softberries.doubleentry.ledger.Line

/**
 * The two lines of a transfer (`[credit, debit]`, `transfer.rb:95`): [credit] is the line of the `from` account with
 * the negated amount, [debit] the line of the `to` account with the amount. Each line's `partnerId` is the other's id.
 */
data class TransferResult(
    val credit: Line,
    val debit: Line,
)
