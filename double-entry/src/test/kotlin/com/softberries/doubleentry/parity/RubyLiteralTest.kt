package com.softberries.doubleentry.parity

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RubyLiteralTest {
    @Test
    fun `parses ActiveRecord bind lists`() {
        RubyLiteral.parse("""[["account", "bank"], ["LIMIT", 1], ["x", nil], ["ok", true], ["f", -1.5]]""") shouldBe
            listOf(listOf("account", "bank"), listOf("LIMIT", 1L), listOf("x", null), listOf("ok", true), listOf("f", -1.5))
    }

    @Test
    fun `keeps nil inside strings and decodes Ruby escapes`() {
        RubyLiteral.parse(""""scope: nil, \#{Account} \"q\"\né"""") shouldBe "scope: nil, #{Account} \"q\"\né"
    }
}
