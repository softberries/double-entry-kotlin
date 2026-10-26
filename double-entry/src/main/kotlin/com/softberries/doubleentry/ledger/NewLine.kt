package com.softberries.doubleentry.ledger

import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.money.Money

/**
 * A line to create directly (`Line.new(...).save!`, BR-085). Only the minor units of [amount] and [balance] are
 * stored; their currency is not checked, as in Ruby (`line.rb:68-78`), and reads use the account's currency.
 * A null code (Ruby `NotNullViolation`) cannot be expressed (D-013).
 */
data class NewLine(
    val account: AccountInstance,
    val partnerAccount: AccountInstance,
    val code: String,
    val amount: Money,
    val balance: Money,
    val partnerId: Long? = null,
    val detail: DetailRef? = null,
    val metadata: Map<String, Any?>? = null,
)
