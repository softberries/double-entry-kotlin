package com.softberries.doubleentry.money

/**
 * Maps [Money] to and from the bigint `amount`/`balance` columns (`line.rb:64-78`, `account_balance.rb:13-19`).
 *
 * Only minor units are stored; the currency is the account's, so the entity supplies it when reading. This is not a
 * JPA `AttributeConverter`, because a converter cannot see the owning entity's account.
 */
object MoneyConverter {
    /** `self[:amount] = money.fractional`. */
    fun toColumn(money: Money): Long = money.minorUnits

    /** `Money.new(self[:amount], currency)`. */
    fun fromColumn(
        minorUnits: Long,
        currency: CurrencyUnit,
    ): Money = Money.ofMinor(minorUnits, currency)
}
