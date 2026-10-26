package com.softberries.doubleentry.account

import com.softberries.doubleentry.testing.TestUser

/** The accounts of the oracle's `standard_config` (`tools/oracle.rb:51-62`). */
internal object AccountFixtures {
    /** `USER_SCOPE` (`tools/oracle.rb:41`): rejects anything but a user, else its id. */
    val userScope: ScopeIdentifier = { user ->
        require(user is TestUser) { "not a User" }
        user.id
    }

    fun standardAccounts(extra: AccountSet.Builder.() -> Unit = {}): AccountSet =
        AccountSet.build {
            define("savings", scopeIdentifier = userScope, positiveOnly = true)
            define("checking", scopeIdentifier = userScope)
            define("btc_wallet", scopeIdentifier = userScope, currency = "BTC")
            define("bank")
            define("cash")
            define("store")
            define("liability", negativeOnly = true)
            define("btc_store", currency = "BTC")
            define("jpy_wallet", currency = "JPY")
            define("jpy_bank", currency = "JPY")
            extra()
        }

    fun resolver(
        scopeIdentifierMaxLength: Int? = null,
        extra: AccountSet.Builder.() -> Unit = {},
    ): AccountResolver = AccountResolver(standardAccounts(extra), scopeIdentifierMaxLength)
}
