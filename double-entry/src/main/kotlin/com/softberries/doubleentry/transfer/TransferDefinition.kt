package com.softberries.doubleentry.transfer

/**
 * An allowed transfer (`DoubleEntry::Transfer`, `transfer.rb:66-76`): money may move from the account [from] to the
 * account [to] with [code]. The identifiers are not checked against the account set (BR-004).
 */
data class TransferDefinition(
    val from: String,
    val to: String,
    val code: String,
)
