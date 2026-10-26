# 20 — Business rules

Each rule gives its evidence (`path:line`), its status (**verified** = oracle or passing spec; **inferred** = code only), the covering spec(s) or **untested**,
and the Kotlin note where a decision changes it (X-## = deviation register in decisions.md). Oracle paths are relative to `oracle/api/json/`
unless they say `table/`. Spec ids are from `inventory/specs.json` (all 167 passed on PG 17, `inventory/specs_run_postgres.json`).
Abbreviations: `DE` = `spec/double_entry_spec.rb`, `TR` = `spec/double_entry/transfer_spec.rb`, `LK` = `spec/double_entry/locking_spec.rb`,
`LN` = `spec/double_entry/line_spec.rb`, `AC` = `spec/double_entry/account_spec.rb`, `AB` = `spec/double_entry/account_balance_spec.rb`,
`BC` = `spec/double_entry/balance_calculator_spec.rb`, `CF` = `spec/double_entry/configuration_spec.rb`, `LC` = `spec/double_entry/validation/line_check_spec.rb`,
`LE` = `spec/active_record/locking_extensions_spec.rb`, `GN` = `spec/generators/double_entry/install/install_generator_spec.rb`.

## A. Configuration and accounts {#config}

**BR-001 Account definition.** `accounts.define(identifier:, scope_identifier: nil, positive_only: nil, negative_only: nil, currency: nil)`.
The currency defaults to `Money.default_currency` at definition time. Evidence: `account.rb:33-41,143-153`. **verified** `config_existing_config_snapshot.json`,
`config_defaults.json` (`default_currency_of_account_without_currency: USD`). Specs: AC [1:3:1], [1:3:2]; CF [1:1:1], [1:4:1]. Kotlin: the default currency is a configuration property.

**BR-002 Duplicate account.** Defining an identifier that already exists raises `DuplicateAccount` (message = class name), whether the earlier definition was scoped or unscoped.
Evidence: `account.rb:35-36`. **verified** `config_duplicate_account.json`, `config_duplicate_account_scoped_vs_unscoped.json`. Spec: DE [1:1:1].

**BR-003 Duplicate transfer.** Defining an existing `[from, to, code]` raises `DuplicateTransfer`. Evidence: `transfer.rb:32-36`. **verified** `config_duplicate_transfer.json`. Specs: DE [1:1:2]; CF [1:2:1], [1:5:1].

**BR-004 No referential check on transfer definitions.** `transfers.define(from: :nope, to: :nada, code: :x)` succeeds. Evidence: `transfer.rb:68-76`. **verified** `config_transfer_unknown_accounts_allowed.json`. **untested** by specs.

**BR-005 Account identifier max length.** If `account_identifier_max_length` is set and `identifier.length > max`, defining raises
`AccountIdentifierTooLongError` with message `account identifier '<id>' is too long. Please limit it to <max> characters.` (Length = max is OK.)
Evidence: `account.rb:149-152`. **verified** `config_account_identifier_too_long.json`, `config_account_identifier_max_length_ok.json`. Specs: AC [1:1:1:1:1], [1:1:1:2:1].

**BR-006 Transfer code max length.** The same rule for `code_max_length`, raising `TransferCodeTooLongError`: `transfer code '<code>' is too long. Please limit it to <max> characters.`
Evidence: `transfer.rb:72-75`. **verified** `config_code_too_long.json`. Specs: TR [1:1:1:1:1], [1:1:1:2:1].

**BR-007 Scope identifier max length.** When an `Account::Instance` is built, if the scope identity is non-nil, `scope_identifier_max_length` is set and the identity is longer,
it raises `ScopeIdentifierTooLongError`: `scope identifier '<identity>' is too long. Please limit it to <max> characters.` This applies to identities from `scope_identity:` and from the lambda.
Evidence: `account.rb:132-138`. **verified** `config_scope_too_long.json`, `config_scope_too_long_via_lambda.json`. Specs: AC [1:2:3:1:1:1], [1:2:3:1:2:1].

**BR-008 Max-length defaults.** All three max lengths default to nil (no limit). Evidence: `account.rb:7`, `transfer.rb:7`. **verified** `config_defaults.json`. Specs: CF [1:3:1:1:1], [1:3:1:2:1], [1:3:1:3:1], [1:3:2:1:1], [1:3:3:1:1], [1:3:4:1:1].

**BR-009 Account lookup.** `DoubleEntry.account(identifier, scope: obj | scope_identity: str)`. The lookup is *scoped* iff `scope.present? || scope_identity.present?`
(so `nil`, `''` and `false` count as unscoped, while `0` counts as scoped). It succeeds only if the identifier is defined **and** `definition.scoped? == lookup scoped`. Otherwise it raises
`UnknownAccount` with message `account: <identifier> scoped?: <true|false>`. The identifier must match exactly: the String `'bank'` does not find `:bank`.
Evidence: `account.rb:16-19,43-51`. **verified** `account_unscoped.json`, `account_unknown.json`, `account_scoped_without_scope.json`, `account_unscoped_with_scope.json`,
`account_scope_empty_string.json`, `account_scope_false.json`, `account_string_identifier.json`, `account_scope_zero.json`. Specs: DE [1:2:1:1]..[1:2:1:5], [1:2:2:1], [1:2:3:1], [1:6:7]; AC [1:4:1:1], [1:4:1:2].

**BR-010 Scope identity.** `scope_identity:` given → used as-is. Otherwise, for a scoped account → `scope_identifier.call(scope).to_s` (a lambda returning nil gives `""`).
Unscoped → nil. Evidence: `account.rb:79-85`. **verified** `account_scoped.json` (`"1"`), `account_scope_identity_option.json`,
`account_scope_identity_integer.json` (Integer 42 kept), `account_scope_lambda_returns_nil.json`. Spec: LN [1:2:1:3:2:1]. Kotlin: always String (**X-08**, D-014).

**BR-011 Scope validation is the host's lambda.** Exceptions raised by `scope_identifier` (e.g. `'not a User'`) propagate unchanged from `account` and `balance`.
Evidence: `account.rb:84`, README `:225-228`. **verified** `account_scope_lambda_raises.json`. Spec: DE [1:6:4].

**BR-012 Account instance identity, order and text.** `==`/`eql?` compare class, identifier and scope_identity. `hash` per 10-domain-model.md §3. `<=>` compares `[scope_identity.to_s, identifier.to_s]`.
`to_s`/`inspect` = `#{Account account: <identifier> scope: <scope object to_s> currency: <currency>}`. The scope part is the *original scope object*,
so it is blank for instances rebuilt from DB rows (`scope_identity:`). Evidence: `account.rb:100-128`. **verified** `account_instance_semantics.json`, `linecheck_account_balance_error_with_fixer.json`.
Specs: AC [1:2:1], [1:2:2]. Kotlin: `toString()` must produce the same text where it appears in messages (BR-030, BR-063, BR-095). The scope part is `scope?.toString() ?: ""`.

**BR-013 Account currency helper.** `Account.currency(identifier)` returns the configured currency or nil (no error). Evidence: `account.rb:22-24`. **verified** `config_defaults.json`. **untested**.

## B. Transfers {#transfers}

**BR-020 Transfer allowlist.** A transfer is allowed only if `[from.identifier, to.identifier, code]` was defined. Scope plays no part, and the code is compared exactly
(the String `"salary"` ≠ the Symbol `:salary`). Otherwise `TransferNotAllowed` is raised with message `[<from>, <to>, <code>].inspect` (e.g. `[:bank, :cash, :nope]`, `[:bank, :cash, nil]`).
Evidence: `transfer.rb:41-49,61-63`. **verified** `transfer_not_allowed_direction.json`, `transfer_not_allowed_code.json`, `transfer_missing_code.json`, `transfer_string_code.json`.
Specs: DE [1:3:2], [1:3:3], [1:3:4]; TR [1:3:1:1], [1:3:1:2], [1:3:2:1], [1:3:2:2]. Kotlin: render the message in the same format (`[:from, :to, :code]`, `nil` for a null code).

**BR-021 Order of transfer checks.** (1) `amount.negative?` → `TransferIsNegative` (message = class name). (2) Allowlist (BR-020). (3) Same identifier **and** same scope identity →
`TransferNotAllowed` "from account and to account are identical". (4) `to.currency != from.currency` → `MismatchedCurrencies` "Mismatched currency (<to> <> <from>)".
(5) Lock and write. Steps 1–4 write nothing. Evidence: `transfer.rb:16-22,85-90`. **verified** `transfer_negative_amount.json`, `transfer_same_account.json`,
`transfer_mismatched_account_currencies.json` (no rows). Specs: DE [1:3:5], [1:6:5]. Kotlin: add the amount-currency check after (4) (D-013, **X-07**).

**BR-022 Zero amount allowed.** `Money.new(0)` creates two lines with amount 0 and unchanged balances. Evidence: `transfer.rb:17`. **verified** `transfer_zero_amount.json`. **untested**.

**BR-023 Same identifier, different scope.** Allowed when defined (e.g. `checking → checking`). Evidence: `transfer.rb:85`. **verified** `transfer_same_identifier_other_scope.json`. Specs: DE [1:6:2], [1:6:6].

**BR-024 Amount currency is not checked explicitly.** Money in another currency fails inside the lock when it is added to the account-currency balance
(`Money::Bank::UnknownRate: No conversion rate known for 'BTC' -> 'USD'`). The transaction rolls back, but balance rows created by the lock (BR-031) stay.
Evidence: `transfer.rb:104-105`. **verified** `transfer_amount_currency_differs_from_accounts.json`, `transfer_amount_currency_differs_first_transfer_zero_balance.json`. **untested**. Kotlin: **X-07**.

**BR-025 Transfer write protocol (protocol for mixed writers, D-017).** Inside `lock_accounts(from, to)`:
1. `credit_balance` = locked row of `from`, `debit_balance` = locked row of `to` (`Locking.balance_for_locked_account`).
2. `UPDATE` the from row: `balance = balance − amount`. Then `UPDATE` the to row: `balance = balance + amount`. Each via `update_attribute`, which also sets `updated_at`.
3. Credit line: `account = from`, `amount = −amount`, `balance = from row's new balance`, `code`, `detail`, `metadata` (json mode), `partner_account = to`. `INSERT`; `partner_id` is NULL at this point.
4. Debit line: `account = to`, `amount = +amount`, `balance = to row's new balance`, `partner_id = credit.id`, `partner_account = from`. `INSERT`.
5. `UPDATE` the credit line: `partner_id = debit.id` (also bumps `updated_at`).
6. Legacy mode only: metadata rows (BR-029). `COMMIT`. Returns `[credit, debit]`.
Evidence: `transfer.rb:78-121`. **verified** SQL in `transfer_scoped_basic.json`, `transfer_unscoped_basic.json`, `transfer_return_value.json`. Specs: TR [1:2:1:1], [1:2:1:2]; DE [1:3:1], [1:4:1]..[1:4:8], [1:5:4].

**BR-026 Running-balance invariant.** For every (account, scope), ordered by line `id`: `line.balance = previous.balance + line.amount` (previous = 0 for the first line).
The cached `account_balances.balance` equals the balance of the latest line. Evidence: `transfer.rb:101-111`, `line_check.rb:50-60`. **verified** `transfer_sequence_running_balances.json`,
`lock_concurrent_threads_same_accounts.json` (`running_balances_consistent: true`), `oracle/concurrency/jack_hammer_*.txt`. Specs: DE [1:5:1], [1:5:2], [1:5:10].

**BR-027 Both lines carry the transfer attributes.** `code` (stored as string), `detail_type`/`detail_id` (the host record's class name and id) and `metadata` are written to both lines.
`partner_account`/`partner_scope` hold the other side. Evidence: `transfer.rb:109-114`. **verified** `transfer_with_detail.json` (`detail_type: "User"`). Specs: DE [1:4:2], [1:4:3], [1:4:4]; LN [1:2:1:6:1].
Kotlin: `DetailRef(type, id)` (D-015).

**BR-028 Metadata, json mode (the only Kotlin mode, D-004).** `metadata:` (a Hash) is assigned unchanged to both lines' jsonb column. Keys are serialised as strings, values keep their JSON types
(arrays and nested objects too). `{}` is stored as `{}`, and no metadata gives NULL. Evidence: `transfer.rb:112`. **verified** `transfer_with_metadata.json`
(`{"n": 1, "tax": ["GST","VAT"], "nested": {"a": 1}, "country": "AU"}` — the key order shown is PG jsonb's), `transfer_with_metadata_simple.json`, `transfer_with_empty_metadata.json`.
Specs: TR [1:2:2:1:1]..[1:2:2:1:3], [1:2:3:1:1], [1:2:3:1:2], [1:2:1:3:1], [1:2:1:3:2].

**BR-029 Metadata, legacy table mode (out of scope, D-004/X-02).** For each key, for each value of `Array(value)`: one `LineMetadata` row for the credit line, then one for the debit line.
`key` = `key.to_s`, `value` is cast to string (`1` → `"1"`, a nested Hash `{a: 1}` → `"[:a, 1]"`). No jsonb is written. Evidence: `transfer.rb:93,123-130`.
**verified** `table/transfer_with_metadata.json`. Specs: TR [1:2:2:2:1]..[1:2:2:2:6], [1:2:3:2:1]..[1:2:3:2:4], [1:2:1:4:1], [1:2:1:4:2].

**BR-030 Positive-only / negative-only.** Before every `Line` save (`save`/`save!`): if the account is `positive_only` and the line balance < 0, raise
`AccountWouldBeSentNegative` (message = `account.to_s`, e.g. `#{Account account: savings scope:  currency: USD}`). If `negative_only` and balance > 0, raise `AccountWouldBeSentPositiveError`.
A balance of exactly 0 is fine. The enclosing transaction rolls back: no lines, and balance rows keep their prior values. This applies to **every** line save, including the partner-id update and
AccountFixer (BR-096). Evidence: `line.rb:80-88,156-163`. **verified** `transfer_positive_only_violation.json`, `transfer_positive_only_exact_zero.json`,
`transfer_negative_only_violation.json`, `transfer_negative_only_ok.json`, `line_direct_create_positive_only_check.json`. Specs: LN [1:2:2:1], [1:2:3:1].

**BR-031 Balance rows survive failed transfers.** Balance rows created during lock acquisition are committed in their own transactions *before* the locked transaction runs (BR-062).
They persist (with the balance computed from lines) even when the transfer then fails or is rolled back. Evidence: `locking.rb:67-74,164-171`. **verified** `transfer_positive_only_violation.json`
(checking row, balance 0), `lock_rollback.json`, `lock_exception_rollback.json`. **untested** by specs.

**BR-032 Amount storage and rounding.** Amounts and balances are stored as `Money#fractional` (integer minor units, bigint). A fractional minor amount is rounded **half-even**
when the Money is constructed (money gem default): `Money.new(10.5)` → 10, `Money.new(11.5)` → 12. Overflow beyond the bigint range raises `ActiveModel::RangeError` and rolls back.
Evidence: `line.rb:68-70`. **verified** `transfer_fractional_cents.json`, `transfer_fractional_cents_11_5.json`, `transfer_bigint_max.json`, `transfer_bigint_overflow.json`. **untested**.
Kotlin: `Money` construction rounds HALF_EVEN (D-006). Overflow → `ArithmeticException` (`Math.addExact`) before the write. The message differs, which is acceptable (invalid input, D-013).

**BR-033 Non-Money input crashes.** An Integer amount raises `TypeError: Can't add or subtract a non-zero Integer value` after balance rows are created. A nil amount raises `NoMethodError` (`negative?`).
A missing `from:` raises `NoMethodError` (`identifier`). Evidence: `transfer.rb:17-21`. **verified** `transfer_integer_amount.json`, `transfer_nil_amount.json`, `transfer_missing_from.json`.
**untested**. Kotlin: impossible by typing (D-013, **X-07**).

**BR-034 Transfers lock, so they must be the outermost transaction** (BR-060), unless they run inside a `lock_accounts` block that already holds both accounts (BR-063).
Evidence: `transfer.rb:91`. **verified** `transfer_inside_ar_transaction.json`, `lock_multiple_transfers_atomic.json`. Specs: LK [1:4], [1:5], [1:6].

**BR-035 Timestamps and time zone.** `created_at`/`updated_at` are set by ActiveRecord from the current time in **UTC** (`ActiveRecord.default_timezone = :utc`).
The column is `timestamp(6) without time zone`, so microseconds are kept. The two lines of one transfer get separate, slightly increasing timestamps, and the credit line's `updated_at` changes with its partner-id update.
`created_at` is the transfer time used by historic balances (BR-042/043). Evidence: migration template `:30`; **verified** SQL in `transfer_scoped_basic.json`, `line_find_id_and_created_at.json`. **untested** by specs.
Kotlin: take an injectable `Clock` and write UTC `LocalDateTime` with microsecond precision (U-008).

## C. Balances {#balances}

**BR-040 Current balance.** Without a `from`+`to` pair and without codes: `SELECT balance FROM double_entry_lines WHERE account = <id> [AND scope = <scope>] ORDER BY id DESC LIMIT 1`.
The scope condition is added only when the scope identity is non-nil, so for unscoped accounts there is no `scope IS NULL` condition (identifiers are unique across scoped and unscoped, BR-002).
Ordering is by **id**, not time. Evidence: `balance_calculator.rb:16-32,88-95`. **verified** SQL in `balance_current.json`, `balance_symbol_with_scope.json`. Specs: DE [1:5:1], [1:6:1], [1:6:3]; BC [1:1:4:1].

**BR-041 No lines → zero** in the account's currency (`Money.zero(currency)`: `0.00` USD, `0.00000000` BTC, `0` JPY). Evidence: `balance_calculator.rb:31`. **verified** `balance_no_lines.json`. Spec: LK [1:1].

**BR-042 Balance at a time.** `at:` adds `created_at <= at` (inclusive) and still takes the latest line **by id**. If an older-timestamped line has a higher id, its balance wins.
Evidence: `balance_calculator.rb:90`. **verified** `balance_at_exact_timestamp.json`, `balance_at_between.json`, `balance_at_before_first.json`, `balance_order_by_id_not_time.json`
(`at: T0-1d` → 11.00, including a line created at T0). Specs: DE [1:5:5]; BC [1:1:1:1:1].

**BR-043 Balance over a period.** When **both** `from` and `to` are given and `at` is not → `SUM(amount) WHERE created_at BETWEEN from AND to` (both ends inclusive). Only `from` or only `to` → ignored (current balance).
`from > to` → 0. Evidence: `balance_calculator.rb:21-23,66-68,91`. **verified** `balance_between_inclusive.json`, `balance_between_empty.json`, `balance_from_only.json`, `balance_to_only.json`,
`balance_inverted_range.json`. Specs: DE [1:5:6], [1:5:7]; BC [1:1:1:2:1].

**BR-044 `at` beats `from/to`.** With `at` present the range is ignored entirely. Evidence: `balance_calculator.rb:66-68`. **verified** `balance_at_overrides_range.json`. Spec: BC [1:1:1:1:2:1].

**BR-045 Code filters.** `codes = Array(codes) + [code]` without nils. If non-empty → `SUM(amount)` with `code IN (codes)`, combined with the time filter (`<= at`, or `BETWEEN`).
`codes: []` with no `code` → no filter (running balance). Codes are compared as strings. Evidence: `balance_calculator.rb:56,70-72,92`. **verified** `balance_code.json`, `balance_codes.json`,
`balance_code_and_codes.json`, `balance_code_unknown.json`, `balance_code_and_at.json`, `balance_code_and_range.json`, `balance_codes_empty_array.json`.
Specs: DE [1:5:8], [1:5:9]; BC [1:1:2:1], [1:1:3:1].

**BR-046 Time values** are converted to UTC for comparison (a `+10:00` Time equal to T0 gives the same result). Evidence: AR quoting. **verified** `balance_time_with_offset.json`. **untested**. Kotlin: accept `Instant`/`OffsetDateTime` and compare in UTC.

**BR-047 MySQL index hint** (`USE INDEX (lines_scope_account_id_idx)` for scoped running-balance queries on MySQL). Evidence: `balance_calculator.rb:37-47`. **inferred**. Kotlin: not ported (**X-01**).

**BR-048 Balances read lines, never the cache.** `balance()` ignores `double_entry_account_balances`. Evidence: `balance_calculator.rb`. **verified** `balance_reads_lines_not_cache.json`. **untested**.

**BR-049 `DoubleEntry.balance(:symbol, scope:)`** resolves the account first with the BR-009/011 errors. Evidence: `double_entry.rb:142-145`. **verified** `balance_symbol_with_scope.json`, `balance_scoped_without_scope.json`. Specs: DE [1:6:3], [1:6:4], [1:6:7].

**BR-050 Scopes with a minimum balance.** `AccountBalance.scopes_with_minimum_balance_for_account(min_money, identifier)` → `SELECT scope FROM account_balances WHERE account = <id> AND balance >= <min.fractional>`.
It returns scope strings (nil for an unscoped account) in no guaranteed order. **The currency of `min_money` is ignored.** The identifier may be a Symbol or a String. It reads the cache, so duplicate NULL-scope rows (BR-070) show up.
Evidence: `account_balance.rb:50-52`. **verified** `balance_scopes_with_minimum_balance.json`. Specs: AB [1:2:1:1:1], [1:2:1:2:1], [1:2:1:3:1].

**BR-051 Transfer arithmetic uses the cache.** New line balances are computed from the locked `account_balances` rows, not from lines (BR-025). A wrong cache therefore spreads into new lines until LineCheck/AccountFixer repairs it.
Evidence: `transfer.rb:101-111`. **inferred**.

## D. Locking {#locking}

**BR-060 Outermost transaction.** `lock_accounts(*accounts) { }` flattens and sorts the accounts (BR-012). If no lock is held in this thread, the DB connection's open transaction count must be ≤ 0,
or ≤ 1 when `running_inside_transactional_fixtures` is true. Otherwise it raises `LockMustBeOutermostTransaction` (message = class name). Evidence: `locking.rb:35-43,60-63,115-120`.
**verified** `lock_inside_transaction.json`, `transfer_inside_ar_transaction.json`, `transfer_inside_transaction_with_fixtures_flag.json` (ok), `transfer_inside_two_transactions_with_fixtures_flag.json` (raises).
Specs: LK [1:3], [1:4]. Kotlin: unchanged (D-007).

**BR-061 Lock acquisition (protocol, D-017).** In a restartable transaction: for each account in sorted order,
`SELECT * FROM double_entry_account_balances WHERE scope = <s> | scope IS NULL AND account = <id> ORDER BY id ASC LIMIT 1 FOR UPDATE`. If every row exists, store the lock map
(thread-local, BR-071), run the block, clear the map (ensure) and commit. Evidence: `locking.rb:127-161`, `account_balance.rb:31-35`. **verified** SQL in `lock_order_sql.json`
(bank, cash, store [scope ""] before checking [scope "1"]). Specs: LK [1:5], [1:7], [1:11], [1:13:1].

**BR-062 Missing balance rows.** If any row is missing, the first transaction commits without running the block (locks already taken are released). Then, for each missing account in order:
compute its balance from lines (BR-040, outside any lock) and `create_ignoring_duplicates!` (its own transaction). Then retry the locked transaction **once**. If rows are still missing → `LockDisaster`.
Evidence: `locking.rb:67-74,149-171`. **verified** `lock_creates_missing_balances.json`, `lock_balance_from_lines_after_flush.json`, `oracle/concurrency/jack_hammer_t2000f4.txt` (balances flushed 4×).
Specs: LK [1:1], [1:2], [1:13:2].

**BR-063 Nested locks.** If this thread already holds locks, every requested account must be among them, else `LockNotHeld` "No lock held for account: <identifier>, scope <scope object>".
The scope part is `account.scope`, the original object, e.g. `#<User:0x…>`, or blank for instances built from `scope_identity`. Then the block runs directly (no new transaction or savepoint).
Evidence: `locking.rb:38-40,81-87`. **verified** `lock_wrong_locks.json`, `lock_nested_subset_ok.json`, `lock_nested_superset.json`. Specs: LK [1:6], [1:7], [1:8].
Kotlin: same message format, with `scope` = the scope object's `toString()` or blank.

**BR-064 Transfers inside a lock** take the nested path, so both accounts must be locked by the outer block. Multiple transfers and other writes inside one block commit atomically.
Evidence: `transfer.rb:91`. **verified** `lock_multiple_transfers_atomic.json`. Spec: LK [1:5]; README `:143-158`.

**BR-065 Rollback.** An exception in the block rolls back the transaction and propagates. `ActiveRecord::Rollback` rolls back silently. Balance rows created before (BR-031) remain.
Evidence: AR transaction semantics, `locking.rb:131-137`. **verified** `lock_rollback.json`, `lock_exception_rollback.json`. Specs: LK [1:9], [1:10].
Kotlin: a dedicated `rollback()` signal or exception equivalent to `ActiveRecord::Rollback` (U-009).

**BR-066 Return value.** Outermost `lock_accounts` returns nil; a nested call returns the block's value. Evidence: `locking.rb:42,70-73`. **verified** `lock_returns_block_value.json`. **untested**. Kotlin: always the block value (**X-05**, D-010).

**BR-067 Deadlock restart.** A `StatementInvalid` whose message matches `/deadlock/i` or `/database is locked/i` **while acquiring locks** publishes `deadlock_restart.double_entry {exception}`
and restarts the whole transaction, with no retry limit. A deadlock inside the block is not caught and propagates. Evidence: `locking.rb:129-130`, `locking_extensions.rb:9-27`. **verified** by specs only
(LE [1:1:1], [1:2:1:1:1], [1:2:1:1:2], [1:2:2:1:1], [1:2:2:1:2], [1:2:3:1:1], [1:2:3:1:2]). No real deadlock is observable because lock order is consistent. Kotlin: SQLSTATE 40P01 (D-008).

**BR-068 Lock wait timeout.** A `StatementInvalid` whose message matches `/lock wait timeout/i` → `DoubleEntry::Locking::LockWaitTimeout`. Other errors propagate. On PG the message is
"canceling statement due to lock timeout", so it surfaces as `ActiveRecord::LockWaitTimeout`. Evidence: `locking.rb:45-51`. **verified** `lock_wait_timeout_pg.json`. Specs: LK [1:12:1:1], [1:12:2:1] (stubbed).
Kotlin: SQLSTATE 55P03 → `LockWaitTimeout` (**X-04**).

**BR-069 Creating balance rows tolerates races.** `create_ignoring_duplicates!`: an error matching `/duplicate/i` or `/ConstraintException/` is ignored, publishing `duplicate_ignore.double_entry`.
An error matching `/deadlock/i` or `/database is locked/i` publishes `deadlock_retry.double_entry` and retries without limit. Evidence: `locking_extensions.rb:31-72`. **verified** `lock_create_ignoring_duplicates_scoped.json`,
`compat_nulls_not_distinct_index.json`. Specs: LE [1:3:1], [1:3:2], [1:3:3:1:1]..[1:3:5:1:2].

**BR-070 NULL-scope uniqueness gap.** The unique index `(scope, account)` does not cover NULL scope on PG, so concurrent first use of an unscoped account creates duplicate rows.
Locks and the cache use the lowest-id row (`ORDER BY id LIMIT 1`), so balances stay correct, but the extra rows stay at 0. **verified** `lock_concurrent_threads_same_accounts.json` (16 rows for 2 accounts),
`lock_null_scope_duplicate_balance_rows.json`. **untested**. Kotlin: fix (**X-03**, D-005).

**BR-071 Locks are thread-local.** The lock map lives in `Thread.current[:double_entry_locks]`. Another thread neither sees nor shares it, and concurrent threads on disjoint accounts proceed in parallel.
Evidence: `locking.rb:97-107`, CHANGELOG 2.0.2 (#226). **verified** `lock_concurrent_threads_same_accounts.json`, `oracle/concurrency/jack_hammer_t2000z.txt`. Specs: LK [1:13:1], [1:13:2].
Kotlin: ThreadLocal (or a transaction-bound resource), cleared in `finally`.

**BR-072 Instrumentation log lines.** The `LogSubscriber` attached to `double_entry` writes INFO `Deadlock causing restart`, `Deadlock causing retry` or `Duplicate ignored`, with the exception at DEBUG, to the AR logger.
Evidence: `log_subscriber.rb:7-29`. **inferred** (event publication **verified** `lock_create_ignoring_duplicates_scoped.json`). Kotlin: D-012.

**BR-073 `Locking.balance_for_locked_account(account)`** outside a lock → `LockNotHeld`. Evidence: `locking.rb:55-57,89-93`. **verified** `lock_balance_for_locked_account_outside_lock.json`. **untested**.

**BR-074 Empty lock.** `lock_accounts` with no accounts opens a transaction and runs the block. Evidence: `locking.rb:149-160`. **verified** `lock_empty.json`. **untested**.

## E. Line queries {#line-queries}

**BR-080 Line value accessors.** `amount`/`balance` are Money in the account's configured currency (if the account is no longer configured, reading raises `UnknownAccount`). `code` is returned as a Symbol.
`account`/`partner_account` are rebuilt from the identifier and the scope string. `AccountBalance#balance` is likewise Money in the account currency (`account_balance.rb:11-15`). Evidence: `line.rb:64-123`. **verified** `transfer_return_value.json`, `line_account_unknown_after_config_change.json`.
Specs: LN [1:2:1:1:1:1], [1:2:1:3:1:1], [1:2:1:3:2:1], [1:2:1:4:1:1], [1:2:1:4:2:1], [1:2:1:5:1:1]; DE [1:4:8], [1:5:3].

**BR-081 `credits` / `debits` (inverted naming).** `Line.credits` = `amount > 0` (destination lines). `Line.debits` = `amount < 0` (source lines, which `transfer` returns as "credit").
Evidence: `line.rb:60-61`. **verified** `line_scopes.json`. **untested**. Kotlin: preserved (D-009).

**BR-082 `with_id_greater_than(id)`** = `id > ?`. Evidence: `line.rb:62`. **verified** `line_scopes.json`. Covered indirectly by LC specs.

**BR-083 Partner and pair.** `partner` = `Line.find(partner_id)`. `pair` = `[self, partner]` if `decrease?` else `[partner, self]`, so the negative line always comes first; for a zero amount the result is `[partner, self]`.
`decrease?` = amount < 0, `increase?` = amount > 0. Evidence: `line.rb:125-143`. **verified** `transfer_return_value.json`. Specs: DE [1:4:5], [1:4:6], [1:4:7].

**BR-084 `Line.find_id_and_created_at(conditions:, joins:)`** → raw rows `[[id, created_at], …]` from `SELECT id, created_at FROM double_entry_lines <joins> WHERE <sanitised conditions>`.
It is not used inside the gem (public helper). Evidence: `line.rb:147-152`. **verified** `line_find_id_and_created_at.json`. **untested**.

**BR-085 Lines can be created directly** (`Line.new(...).save!`). The positive/negative-only check (BR-030) and DB constraints still apply: a nil code raises `NotNullViolation`.
Evidence: `line.rb:80-88`. **verified** `line_direct_create_missing_code.json`. Specs: LN [1:2:1:2:1], LK [1:2].

## F. Validation (consistency check and repair) {#validation}

**BR-090 Last checked line.** `LineCheck.last_line_id_checked` = `last_line_id` of the newest check **by created_at** (not by id), or 0 if there are none. Evidence: `line_check.rb:8-10`.
**verified** `linecheck_last_line_id_checked.json`. Spec: LC [1:1:1:1].

**BR-091 Running-balance check.** `perform!(fixer: nil)` iterates the lines with `id > last_line_id_checked` in id order (batches of 1000, `find_each`). For each line it finds the previous line of the same account and scope
(`scope IS NULL` for NULL scope) with a smaller id. If `line.balance != previous.balance (or 0) + line.amount`, it appends the line error text (BR-095) and marks the account incorrect.
Every visited account counts as active. Evidence: `line_check.rb:16-27,50-85`. **verified** `linecheck_line_error_no_fixer.json`, `linecheck_unscoped_accounts.json`.
Specs: LC [1:2:1:2:1:1], [1:2:1:2:2:1], [1:2:2:2:1:1].

**BR-092 Cached-balance check.** For each active account: `lock_accounts(account)` (so it must be the outermost transaction; `LockMustBeOutermostTransaction` otherwise), then compare `AccountBalance.find_by_account(account).balance`
with the BR-040 balance. A mismatch appends the account error text and marks the account incorrect. Evidence: `line_check.rb:29-31,108-121`. **verified** `linecheck_account_balance_error_with_fixer.json`,
`linecheck_inside_transaction.json`. Specs: LC [1:2:1:3:1:1], [1:2:1:3:2:1].

**BR-093 Repair.** If a fixer is given, `fixer.recalculate_account(account)` runs for every incorrect account. Without a fixer, nothing is changed. Evidence: `line_check.rb:33`.
**verified** `linecheck_line_error_with_fixer.json` vs `linecheck_line_error_no_fixer.json`. Specs: LC [1:2:1:2:3], [1:2:1:2:4:1], [1:2:1:3:3], [1:2:1:3:4:1], [1:2:2:2:3], [1:2:2:2:4:1].

**BR-094 Result row.** If at least one line was visited → `INSERT double_entry_line_checks (errors_found = any incorrect, last_line_id = last visited id, log)` and return the record.
Otherwise return nil and write nothing. Evidence: `line_check.rb:35-41`. **verified** `linecheck_consistent.json`, `linecheck_no_lines.json`, `linecheck_second_run_nothing_new.json`, `linecheck_incremental.json`.
Specs: LC [1:2:1:1:1:1], [1:2:1:1:1:2], [1:2:1:1:2], [1:2:1:1:3:1], [1:2:1:1:4], [1:2:2:1:1]. (MySQL `FORCE INDEX` hint, `line_check.rb:87-95`: **X-01**.)

**BR-095 Log formats (headers byte-identical, D-011).** The line error is
```
*********************************
Error on line #<id>: balance:<line.balance.to_s> != <previous_balance.to_s> + <line.amount.to_s>
*********************************
  <previous_line array inspect>
  <line inspect>

```
(The heredoc is `strip_heredoc`-ed: the two record lines are indented by 2 spaces, followed by an empty line.) The account error is
```
*********************************
Error on account <account.to_s>: <cached.to_s> (cached balance) != <running.to_s> (running balance)
*********************************

```
Money `to_s` has no symbol, `.` as the decimal mark, no thousands separator, and `subunit_to_unit` decimals (`-99.99`, `-0.00010000` BTC). Line errors come first (in id order), then account errors (in active-account insertion order).
Evidence: `line_check.rb:97-106,113-118`. **verified** `linecheck_line_error_with_fixer.json`, `linecheck_account_balance_error_with_fixer.json`. Specs: LC [1:2:1:3:2:1], [1:2:2:2:2:1].
Kotlin: record dumps rendered in Kotlin format (**X-06**).

**BR-096 AccountFixer.** `recalculate_account(account)` inside `lock_accounts(account)` (outermost rule applies): it walks all lines of (account identifier, scope identity string or NULL) by id, keeping a running sum from zero.
Each line whose balance differs is rewritten via `update_attribute` (which runs the BR-030 check, so a positive-only account whose true history goes negative raises `AccountWouldBeSentNegative`
and rolls back the whole repair). Finally the cached balance is set to the sum. Evidence: `account_fixer.rb:6-33`. **verified** `accountfixer_standalone.json`, `accountfixer_positive_only_goes_negative.json`. Specs: LC [1:2:1:2:3], [1:2:1:3:3].

## G. Installation and wiring {#install}

**BR-100 Install generator.** `rails generate double_entry:install [--json-metadata | --no-json-metadata]` (default `--json-metadata` = true) creates
`db/migrate/<timestamp>_create_double_entry_tables.rb` (class `CreateDoubleEntryTables < ActiveRecord::Migration[<major>.<minor>]`) and `config/initializers/double_entry.rb`
with `config.json_metadata = <bool>` and commented examples. Evidence: `install_generator.rb:10-39`, templates. **verified** `inventory/generated/**`. Specs: GN [1:1:1], [1:1:2], [1:2:1], [1:2:2], [1:3:1], [1:3:2].
Kotlin: a Flyway migration shipped with the library plus auto-configuration (D-001, ID-3).

**BR-101 Model loading.** The Railtie's `after_initialize` loads `LineMetadata` only when `json_metadata` is false, then `Line`. So `json_metadata` must be set in an initializer, and non-Rails users require `double_entry/line` themselves.
Evidence: `double_entry.rb:25-33`, `script/jack_hammer:40-41`. **inferred**. Kotlin: auto-configuration.

**BR-102 Table names** are `double_entry_lines`, `double_entry_account_balances`, `double_entry_line_checks` and `double_entry_line_metadata` (prefix BR in 10-domain-model §1). **verified** `config_defaults.json`.
Specs: LN [1:1], [1:2:4]; AB [1:1:1]; LC [1:2:3].
