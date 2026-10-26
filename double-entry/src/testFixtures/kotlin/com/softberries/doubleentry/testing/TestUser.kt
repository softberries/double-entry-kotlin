package com.softberries.doubleentry.testing

/**
 * Scope object for scoped accounts in tests. Replaces the Ruby test-only `users` table and `:user` factory
 * (`spec/support/schema.rb:58-63`), which are not ported.
 */
data class TestUser(
    val id: Long,
)
