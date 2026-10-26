package com.softberries.doubleentry.parity

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OracleFixtureConfigTest {
    private class Recording : FixtureBinder<Pair<List<FixtureAccount>, List<FixtureTransfer>>> {
        val accounts = mutableListOf<FixtureAccount>()
        val transfers = mutableListOf<FixtureTransfer>()

        override fun account(account: FixtureAccount) {
            accounts += account
        }

        override fun transfer(transfer: FixtureTransfer) {
            transfers += transfer
        }

        override fun build() = accounts.toList() to transfers.toList()
    }

    @Test
    fun `binds the configuration of config_existing_config_snapshot`() {
        val (accounts, transfers) = OracleFixtureConfig.bind(Recording())
        val snapshot = OracleScenarios.load("config_existing_config_snapshot").result!!

        accounts.map { Triple(it.identifier, it.scoped, it.currency ?: OracleFixtureConfig.DEFAULT_CURRENCY) } shouldContainExactly
            snapshot["accounts"].map { Triple(it["account"].asText(), it["scoped"].asBoolean(), it["currency"].asText()) }
        transfers.map { listOf(it.from, it.to, it.code) } shouldContainExactly
            snapshot["transfers"].map { t -> t["transfer"].map { it.asText() } }
    }

    @Test
    fun `positive-only and negative-only accounts as in tools oracle rb`() {
        OracleFixtureConfig.ACCOUNTS.filter { it.positiveOnly }.map { it.identifier } shouldContainExactly listOf("savings")
        OracleFixtureConfig.ACCOUNTS.filter { it.negativeOnly }.map { it.identifier } shouldContainExactly listOf("liability")
    }

    @Test
    fun `the user scope resolves TestUser ids`() {
        OracleFixtureConfig.userScope(OracleFixtureConfig.user(2)) shouldBe 2L
        OracleFixtureConfig.T0.toString() shouldBe "2026-01-15T10:00:00Z"
    }
}
