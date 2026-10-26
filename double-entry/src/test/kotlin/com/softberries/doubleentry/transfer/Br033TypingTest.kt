package com.softberries.doubleentry.transfer

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.full.valueParameters

/**
 * BR-033 (D-013, X-07): Ruby crashed on an Integer amount (`TypeError` after the balance rows were committed,
 * `transfer_integer_amount.json`), a nil amount (`NoMethodError`, `transfer_nil_amount.json`) and a missing `from:`
 * (`NoMethodError`, `transfer_missing_from.json`). In Kotlin these calls do not compile:
 *
 * ```
 * transfers.transfer(100, from = bank, to = cash, code = "salary")    // Int is not Money
 * transfers.transfer(null, from = bank, to = cash, code = "salary")   // Money is not nullable
 * transfers.transfer(usd(1), to = cash, code = "salary")              // no value for parameter 'from'
 * transfers.transfer(usd(1), from = bank, to = cash)                  // no value for parameter 'code' (transfer_missing_code)
 * ```
 *
 * This test pins the signature that makes them impossible: amount, accounts and code are required and non-null;
 * only detail and metadata are optional.
 */
class Br033TypingTest {
    @Test
    fun `amount, from, to and code are required non-null parameters`() {
        val parameters = TransferService::transfer.valueParameters

        parameters.map { it.name } shouldContainExactly listOf("amount", "from", "to", "code", "detail", "metadata")
        parameters.map { it.type.toString() } shouldContainExactly
            listOf(
                "com.softberries.doubleentry.money.Money",
                "com.softberries.doubleentry.account.AccountInstance",
                "com.softberries.doubleentry.account.AccountInstance",
                "kotlin.String",
                "com.softberries.doubleentry.ledger.DetailRef?",
                "kotlin.collections.Map<kotlin.String, kotlin.Any?>?",
            )
        parameters.take(4).map { it.isOptional to it.type.isMarkedNullable } shouldContainExactly List(4) { false to false }
        parameters.drop(4).all { it.isOptional && it.type.isMarkedNullable } shouldBe true
    }
}
