package com.softberries.doubleentry.locking

/**
 * `DoubleEntry::Locking::Configuration` (`locking.rb:21-29`).
 *
 * [runningInsideTransactionalFixtures] is a test-only switch (D-007): when true, `lockAccounts` tolerates exactly one
 * enclosing transaction (e.g. a test-managed transaction) instead of none (BR-060). Hosts set it by declaring their
 * own `LockingSettings` bean in their test context.
 */
data class LockingSettings(
    val runningInsideTransactionalFixtures: Boolean = false,
)
