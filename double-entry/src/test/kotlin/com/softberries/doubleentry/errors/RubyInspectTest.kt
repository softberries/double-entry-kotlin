package com.softberries.doubleentry.errors

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.softberries.doubleentry.errors.RubyInspect.RubyValue
import com.softberries.doubleentry.testing.MigrationSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Ruby `#inspect` rendering used by `TransferNotAllowed` messages. Expected values were produced with Ruby 3.3 `p`. */
class RubyInspectTest {
    @Test
    fun `symbolArray renders symbols and nil`() {
        RubyInspect.symbolArray(listOf("bank", "cash", null)) shouldBe "[:bank, :cash, nil]"
    }

    @Test
    fun `a value flagged as String renders quoted (transfer_string_code json)`() {
        val oracle =
            jacksonObjectMapper().readTree(MigrationSpec.read("oracle/api/json/transfer_string_code.json"))["exception"]

        val message = RubyInspect.array(listOf(RubyValue.sym("bank"), RubyValue.sym("cash"), RubyValue.str("salary")))

        message shouldBe "[:bank, :cash, \"salary\"]"
        TransferNotAllowed(message).message shouldBe oracle["message"].asText()
    }

    @Test
    fun `null becomes nil for both kinds`() {
        RubyInspect.array(listOf(RubyValue.str(null), RubyValue.sym(null), RubyValue.Nil)) shouldBe "[nil, nil, nil]"
        RubyInspect.array(emptyList()) shouldBe "[]"
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '\'',
        value = [
            "bank|:bank",
            "Foo?|:Foo?",
            "Foo=|:Foo=",
            "a!|:a!",
            "_|:_",
            "café|:café",
            "@x|:@x",
            "@@x|:@@x",
            "\$x|:\$x",
            "\$1|:\$1",
            "[]=|:[]=",
            "!=|:!=",
            "foo bar|:\"foo bar\"",
            "a?=|:\"a?=\"",
            "a-b|:\"a-b\"",
            "9a|:\"9a\"",
            "@1|:\"@1\"",
            "''|:\"\"",
        ],
    )
    fun `symbol inspect quotes only non-identifier names`(
        name: String,
        expected: String,
    ) {
        RubyInspect.symbol(name) shouldBe expected
    }

    @Test
    fun `string inspect escapes like Ruby`() {
        RubyInspect.string("a\"b\\c") shouldBe "\"a\\\"b\\\\c\""
        RubyInspect.string("\t\n\r\u001B") shouldBe "\"\\t\\n\\r\\e\""
        RubyInspect.string("\u0001\u007F") shouldBe "\"\\u0001\\u007F\""
        RubyInspect.string("#{x} #\$y #@z # x") shouldBe "\"\\#{x} \\#\$y \\#@z # x\""
        RubyInspect.string("é") shouldBe "\"é\""
    }
}
