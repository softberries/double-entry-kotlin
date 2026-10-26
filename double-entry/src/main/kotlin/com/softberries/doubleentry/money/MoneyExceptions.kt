package com.softberries.doubleentry.money

/**
 * The currency code is not in the money gem 7.1.1 table (`Money::Currency::UnknownCurrency`, an `ArgumentError`).
 * The message is the gem's: `Unknown currency 'xyz'` with the down-cased code.
 */
class UnknownCurrencyException(
    code: String,
) : IllegalArgumentException("Unknown currency '$code'")

/**
 * Arithmetic or comparison of two [Money] values in different currencies. Ruby would try a currency exchange and fail
 * with `Money::Bank::UnknownRate`; the port never exchanges (D-006, X-07). M-012 maps it to `MismatchedCurrencies`.
 */
class MoneyCurrencyMismatchException(
    val left: CurrencyUnit,
    val right: CurrencyUnit,
) : IllegalArgumentException("Mismatched currencies: $left and $right")
