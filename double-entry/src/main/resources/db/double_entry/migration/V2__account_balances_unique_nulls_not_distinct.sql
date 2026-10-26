-- X-03 / D-005 / BR-070: one balance row per account, also for unscoped accounts (scope IS NULL).
-- The Ruby generator's unique index (scope, account) treats NULLs as distinct on PostgreSQL, so concurrent first use
-- of an unscoped account can leave duplicate rows (oracle: lock_null_scope_duplicate_balance_rows.json,
-- lock_concurrent_threads_same_accounts.json). Locks and the cache always use the lowest id
-- (ORDER BY id ASC LIMIT 1, account_balance.rb:31-35), so that row is kept regardless of the balance the others hold.
-- UNIQUE NULLS NOT DISTINCT needs PostgreSQL >= 15 (D-002); DoubleEntrySchemaMigrator checks that before migrating.
-- Ruby writers keep working against the new index (compat_nulls_not_distinct_index.json).

-- Block concurrent writers (e.g. Ruby processes) between the dedupe and the index rebuild.
LOCK TABLE double_entry_account_balances IN ACCESS EXCLUSIVE MODE;

DELETE FROM double_entry_account_balances
WHERE id NOT IN (SELECT min(id) FROM double_entry_account_balances GROUP BY account, scope);

DROP INDEX index_account_balances_on_scope_and_account;

CREATE UNIQUE INDEX index_account_balances_on_scope_and_account
    ON double_entry_account_balances USING btree (scope, account) NULLS NOT DISTINCT;
