package com.softberries.doubleentry.balance.cache

import org.springframework.data.repository.Repository

/**
 * Queries on `double_entry_account_balances` (`DoubleEntry::AccountBalance` class methods). Rows are written by the
 * locking protocol and transfers, not through this repository.
 */
interface AccountBalanceRepository :
    Repository<AccountBalanceEntity, Long>,
    AccountBalanceQueries {
    fun findById(id: Long): AccountBalanceEntity?

    fun findAllByOrderByIdAsc(): List<AccountBalanceEntity>
}
