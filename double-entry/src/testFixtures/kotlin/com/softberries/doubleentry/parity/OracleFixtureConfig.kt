package com.softberries.doubleentry.parity

import com.softberries.doubleentry.testing.TestUser

/** An account of the oracle configuration. [scoped] accounts use [OracleFixtureConfig.userScope]. */
data class FixtureAccount(
    val identifier: String,
    val scoped: Boolean = false,
    val positiveOnly: Boolean = false,
    val negativeOnly: Boolean = false,
    /** ISO code; null = the default currency (USD in the oracle). */
    val currency: String? = null,
)

data class FixtureTransfer(
    val from: String,
    val to: String,
    val code: String,
)

/**
 * Narrow interface through which a parity driver turns the oracle configuration into the library's configuration
 * (e.g. with the M-005 DSL), so this harness does not depend on the config package.
 */
interface FixtureBinder<T> {
    fun account(account: FixtureAccount)

    fun transfer(transfer: FixtureTransfer)

    fun build(): T
}

/**
 * The configuration every oracle scenario starts from (`tools/oracle.rb` `standard_config`): no max lengths,
 * default currency USD, `running_inside_transactional_fixtures = false`, frozen time [T0] where a scenario freezes it.
 */
object OracleFixtureConfig {
    val T0 = RowComparator.T0
    const val DEFAULT_CURRENCY = "USD"

    val ACCOUNTS: List<FixtureAccount> =
        listOf(
            FixtureAccount("savings", scoped = true, positiveOnly = true),
            FixtureAccount("checking", scoped = true),
            FixtureAccount("btc_wallet", scoped = true, currency = "BTC"),
            FixtureAccount("bank"),
            FixtureAccount("cash"),
            FixtureAccount("store"),
            FixtureAccount("liability", negativeOnly = true),
            FixtureAccount("btc_store", currency = "BTC"),
            FixtureAccount("jpy_wallet", currency = "JPY"),
            FixtureAccount("jpy_bank", currency = "JPY"),
        )

    val TRANSFERS: List<FixtureTransfer> =
        listOf(
            FixtureTransfer("bank", "checking", "deposit"),
            FixtureTransfer("bank", "savings", "deposit"),
            FixtureTransfer("checking", "savings", "save"),
            FixtureTransfer("savings", "checking", "withdraw"),
            FixtureTransfer("checking", "checking", "p2p"),
            FixtureTransfer("bank", "cash", "salary"),
            FixtureTransfer("bank", "cash", "bonus"),
            FixtureTransfer("cash", "store", "purchase"),
            FixtureTransfer("cash", "store", "layby"),
            FixtureTransfer("cash", "bank", "refund"),
            FixtureTransfer("bank", "btc_store", "mismatch"),
            FixtureTransfer("btc_store", "btc_wallet", "btc_buy"),
            FixtureTransfer("jpy_bank", "jpy_wallet", "jpy_in"),
            FixtureTransfer("cash", "liability", "repay"),
            FixtureTransfer("liability", "cash", "borrow"),
            FixtureTransfer("savings", "savings", "self"),
        )

    /**
     * The scope resolver of the scoped accounts (`USER_SCOPE` in `tools/oracle.rb`): the user's id, and
     * `RuntimeError "not a User"` (→ [RuntimeException]) for any other object (`account_scope_lambda_raises.json`).
     */
    fun userScope(scope: Any?): Long = (scope as? TestUser)?.id ?: throw RuntimeException("not a User")

    /**
     * The oracle creates users with ids from 1 in each scenario (`alice` = 1, then `bob` = 2, …); [n] is 1-based.
     */
    fun user(n: Int = 1): TestUser = TestUser(n.toLong())

    fun <T> bind(binder: FixtureBinder<T>): T {
        ACCOUNTS.forEach(binder::account)
        TRANSFERS.forEach(binder::transfer)
        return binder.build()
    }
}
