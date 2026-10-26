package com.softberries.doubleentry.ledger

import com.softberries.doubleentry.account.AccountFixtures
import com.softberries.doubleentry.account.AccountInstance
import com.softberries.doubleentry.account.AccountResolver
import com.softberries.doubleentry.config.DoubleEntryConfigurer
import com.softberries.doubleentry.testing.PostgresIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionTemplate

/**
 * Accounts of the oracle's `standard_config` (`tools/oracle.rb:51-62`) plus those of `spec/support/accounts.rb` that
 * `line_spec.rb` uses (`test`, `btc_test`) and the positive/negative-only accounts it defines inline.
 */
@TestConfiguration(proxyBeanMethods = false)
class LedgerTestAccounts {
    @Bean
    fun ledgerTestAccounts(): DoubleEntryConfigurer =
        DoubleEntryConfigurer { builder ->
            builder.accounts {
                define("savings", scopeIdentifier = AccountFixtures.userScope, positiveOnly = true)
                define("checking", scopeIdentifier = AccountFixtures.userScope)
                define("btc_wallet", scopeIdentifier = AccountFixtures.userScope, currency = "BTC")
                define("bank")
                define("cash")
                define("store")
                define("liability", negativeOnly = true)
                define("btc_store", currency = "BTC")
                define("test", scopeIdentifier = AccountFixtures.userScope)
                define("btc_test", scopeIdentifier = AccountFixtures.userScope, currency = "BTC")
                define("a_positive_only_acc", positiveOnly = true)
                define("a_negative_only_acc", negativeOnly = true)
            }
        }
}

/** Integration test base for the ledger: [LedgerTestAccounts] plus helpers that write lines like a transfer does. */
@Import(LedgerTestAccounts::class)
abstract class LedgerIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    protected lateinit var writer: LineWriter

    @Autowired
    protected lateinit var lines: Lines

    @Autowired
    protected lateinit var repository: LineRepository

    @Autowired
    protected lateinit var resolver: AccountResolver

    @Autowired
    protected lateinit var transactionTemplate: TransactionTemplate

    protected fun account(
        identifier: String,
        scopeIdentity: String? = null,
    ): AccountInstance = resolver.account(identifier, scopeIdentity = scopeIdentity)

    /**
     * Writes the two lines of a transfer of [amount] minor units in the order of the Ruby protocol
     * (`transfer.rb:105-121`): the negative line, the positive line with `partner_id`, then the partner-id update of
     * the first line. Balances start at zero. Returns (negative line, positive line).
     */
    protected fun transferLines(
        amount: Long,
        from: AccountInstance,
        to: AccountInstance,
        code: String,
    ): Pair<Line, Line> {
        val ids =
            transactionTemplate.execute {
                val credit = writer.insert(entity(from, to, code, -amount))
                val debit = writer.insert(entity(to, from, code, amount, partnerId = credit.id))
                credit.partnerId = debit.id
                writer.update(credit)
                credit.id!! to debit.id!!
            }!!
        return lines.find(ids.first)!! to lines.find(ids.second)!!
    }

    protected fun entity(
        account: AccountInstance,
        partner: AccountInstance,
        code: String,
        amount: Long,
        balance: Long = amount,
        partnerId: Long? = null,
    ): LineEntity =
        LineEntity(
            account = account.identifier,
            scope = account.scopeIdentity,
            code = code,
            amount = amount,
            balance = balance,
            partnerAccount = partner.identifier,
            partnerScope = partner.scopeIdentity,
            partnerId = partnerId,
        )

    protected fun lineCount(): Int = jdbcTemplate.queryForObject("SELECT count(*) FROM double_entry_lines", Int::class.java)!!
}
