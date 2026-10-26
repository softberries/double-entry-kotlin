# 10 — Domain model

Models inventory: `inventory/models.json` (ActiveRecord reflection: 4 gem models plus the spec-only `User`). Non-AR domain classes: `inventory/api.json`.
There are **no ActiveRecord validations, no enums and no state machines** in the gem (`inventory/models.json`: `validations: []`, `enums: {}` for every model).
The only callbacks are Rails-internal (`normalize_changed_in_place_attributes`, `autosave_associated_records_for_*`). Business checks are done in
overridden `save`/`save!` (`Line`) and in plain Ruby objects.

## 1. Configuration (process-global, mutable in Ruby; immutable in Kotlin per D-016) {#configuration}

| Element | Attributes | Default | Evidence |
|---|---|---|---|
| `DoubleEntry::Configuration` (`DoubleEntry.config` / `.configuration`, `DoubleEntry.configure { \|config\| … }`) | `json_metadata` (bool); delegates `accounts`, `accounts=`, `scope_identifier_max_length(=)`, `account_identifier_max_length(=)` → `DoubleEntry::Account`; `transfers`, `transfers=`, `code_max_length(=)` → `DoubleEntry::Transfer`; `define_accounts { \|accounts_set\| }`, `define_transfers { \|transfers_set\| }` | `json_metadata = false` (but the generated initializer sets `true`, BR-100); max lengths `nil` | `lib/double_entry/configuration.rb:5-37`, `lib/double_entry/configurable.rb:40-49`, spec `configuration_spec.rb` [1:1:1]..[1:5:1]; **verified** `oracle/api/json/config_defaults.json` |
| `DoubleEntry::Locking::Configuration` (`DoubleEntry::Locking.configuration`) | `running_inside_transactional_fixtures` (bool) | `false` | `lib/double_entry/locking.rb:21-29`; **verified** `config_defaults.json` |
| Table name prefix | `DoubleEntry.table_name_prefix` = `"double_entry_"` → every AR model under `DoubleEntry` (incl. `DoubleEntry::Validation::LineCheck`) gets the prefix | — | `lib/double_entry.rb:168-170`; specs `line_spec.rb` [1:1], `account_balance_spec.rb` [1:1:1], `line_check_spec.rb` [1:2:3] |

## 2. Account (definition) — `DoubleEntry::Account` {#account}

Value object defined at configuration time (`lib/double_entry/account.rb:141-157`).

| Attribute | Type | Rule | Evidence |
|---|---|---|---|
| `identifier` | Symbol | required; unique within the account set, across scoped and unscoped (`DuplicateAccount`); length ≤ `account_identifier_max_length` if set (`AccountIdentifierTooLongError`) | `account.rb:33-41,149-152`; BR-002, BR-005 |
| `scope_identifier` | callable `(scope_object) → id` or nil | present ⇒ account is **scoped** (`scoped? = !!scope_identifier`) | `account.rb:145,155-157` |
| `positive_only` | bool/nil | lines on this account may never get a negative running balance | `account.rb:146`, `line.rb:157-159`; BR-030 |
| `negative_only` | bool/nil | lines may never get a positive running balance | `account.rb:147`, `line.rb:160-162`; BR-030 |
| `currency` | String/Symbol/`Money::Currency` as given (e.g. `'BTC'`, `:btc`) | default `Money.default_currency` at definition time | `account.rb:148`; spec `account_spec.rb` [1:3:1], [1:3:2] |

`Account::Set` (`account.rb:28-66`): Hash keyed by identifier; `define`, `find(identifier, scoped)`, `find_without_scope`, `all`, `each`, `map`.
`Account.account(identifier, options)` and `Account.currency(identifier)` are class-level helpers (`account.rb:16-24`).

## 3. Account instance — `DoubleEntry::Account::Instance` (aggregate handle) {#account-instance}

A concrete account = definition + scope (`account.rb:68-139`). This is the object passed to `transfer`, `balance` and `lock_accounts`.

| Attribute | Meaning | Evidence |
|---|---|---|
| `account` | the `Account` definition | `account.rb:69` |
| `scope` | the original scope object given via `scope:` (e.g. a `User`), or nil | `account.rb:69,74` |
| `scope_identity` | `scope_identity:` option as given (**not** stringified, so an Integer stays an Integer), else `scope_identifier.call(scope).to_s` for scoped accounts, else nil | `account.rb:79-85`; **verified** `account_scope_identity_integer.json`, `account_scope_lambda_returns_nil.json` ("" when the lambda returns nil). Kotlin: always String (D-014) |
| delegated | `identifier, scope_identifier, scoped?, positive_only, negative_only, currency` | `account.rb:70` |
| `balance(options)` | → `BalanceCalculator.calculate(self, options)` | `account.rb:96-98` |

Invariants and identity (BR-012): equality is the same class, identifier and `scope_identity`. `hash` is `"#{scope_identity}:#{identifier}".hash` for scoped accounts and `identifier.hash` otherwise.
Ordering is by `[scope_identity.to_s, identifier.to_s]`, which is also the lock order (BR-060). The scope identity length is checked at construction (`ScopeIdentifierTooLongError`, BR-007).

## 4. Transfer (definition) — `DoubleEntry::Transfer` {#transfer}

| Attribute | Rule | Evidence |
|---|---|---|
| `from`, `to` | account identifiers (Symbols). Not validated against defined accounts | `transfer.rb:66-71`; **verified** `config_transfer_unknown_accounts_allowed.json` (BR-004) |
| `code` | Symbol (any object usable as a Hash key); length ≤ `code_max_length` if set | `transfer.rb:72-75` (BR-006) |
| key | `[from, to, code]` unique (`DuplicateTransfer`) | `transfer.rb:30-39` (BR-003) |

`Transfer::Set` (`transfer.rb:26-64`): `define`, `find(from_instance, to_instance, code)` → Transfer or nil, `find!` → raises `TransferNotAllowed`, `all/each/map`.
`Transfer#process` and `#create_lines` perform the write (20-business-rules.md BR-025).

## 5. Line — `DoubleEntry::Line` (table `double_entry_lines`) — the ledger entry {#line}

`lib/double_entry/line.rb:57-164`. Append-only log; "should (ideally) never be updated" (`line.rb:10`), but see BR-025 (`partner_id` update) and BR-096 (AccountFixer).

| Attribute (column) | Ruby accessor | Rule | Evidence |
|---|---|---|---|
| `id` bigint PK | | creation order defines running-balance order | BR-026 |
| `account` varchar NOT NULL | `account` → `DoubleEntry.account(account.to_sym, scope_identity: scope)`; `account=` sets `account` = `identifier.to_s` **and** `scope` = `scope_identity` | reading needs the identifier to still be configured, else `UnknownAccount` | `line.rb:99-108`; **verified** `line_account_unknown_after_config_change.json` |
| `scope` varchar NULL | | scope identity string; NULL for unscoped | `line.rb:101` |
| `code` varchar NOT NULL | `code` → Symbol; `code=` stores `to_s` | nil ⇒ `NotNullViolation` on save | `line.rb:90-97`; spec `line_spec.rb` [1:2:1:2:1] |
| `amount` bigint NOT NULL | `Money.new(amount, currency)`; setter stores `money.fractional` | negative on the source line, positive on the destination line | `line.rb:64-70` |
| `balance` bigint NOT NULL | Money, same | running balance of (account, scope) after this line | `line.rb:72-78` |
| `partner_id` bigint NULL | `partner` → `Line.find(partner_id)` | id of the other line of the transfer | `line.rb:125-127` |
| `partner_account` varchar NOT NULL, `partner_scope` varchar NULL | `partner_account(=)` like `account(=)` | | `line.rb:114-123` |
| `detail_type` varchar, `detail_id` bigint | `belongs_to :detail, polymorphic: true, required: false` | optional host record | `line.rb:58`; Kotlin `DetailRef` (D-015) |
| `metadata` jsonb (json mode only) | plain AR attribute (Hash, string keys) | | `transfer.rb:112`; **verified** `oracle/api/json/transfer_with_metadata.json` |
| `created_at`, `updated_at` timestamp(6) NOT NULL | AR timestamps | UTC (BR-035) | migration template `:30` |
| derived | `currency` (account's currency), `decrease?` (amount < 0), `increase?` (amount > 0), `pair` | | `line.rb:110-143` |

Scopes: `credits` (`amount > 0`), `debits` (`amount < 0`), `with_id_greater_than(id)` (`line.rb:60-62`; inverted naming, BR-081/D-009).
Class method `find_id_and_created_at(options)` (`line.rb:147-152`, BR-084).
**Invariant check on save** (BR-030): `save`/`save!` are overridden to run `check_balance_will_remain_valid` first (`line.rb:80-88,156-163`).
**Declared but never defined:** `has_many :metadata … unless -> { … }` (`line.rb:59`). A lambda is always truthy, so the association never exists
(**verified** `inventory/models.json` lists only `detail`; `oracle/api/table/line_metadata_reader.json` → `NoMethodError`).

## 6. AccountBalance — `DoubleEntry::AccountBalance` (table `double_entry_account_balances`) — cache and lock row {#account-balance}

`lib/double_entry/account_balance.rb:10-53`.

| Attribute | Rule | Evidence |
|---|---|---|
| `account` varchar NOT NULL, `scope` varchar NULL | set via `account=` (identifier string, scope identity); `account` reader rebuilds the instance | `account_balance.rb:21-29` |
| `balance` bigint NOT NULL | Money in the account currency | `account_balance.rb:13-19`; spec `double_entry_spec.rb` [1:5:3] |
| unique (scope, account) | one row per account. **Not enforced for NULL scope on PG** (BR-070, D-005) | migration template `:11`; **verified** `lock_null_scope_duplicate_balance_rows.json` |
| lifecycle | created on demand at first lock, from the latest line balance; updated in every transfer; rewritten by AccountFixer; never deleted by the gem | `locking.rb:164-171`, `transfer.rb:104-105`, `account_fixer.rb:30-33` |

Class methods: `find_by_account(account, lock: bool)` (`:31-35`), `scopes_with_minimum_balance_for_account(min, identifier)` (`:50-52`, BR-050).

## 7. LineCheck — `DoubleEntry::Validation::LineCheck` (table `double_entry_line_checks`) {#line-check}

`lib/double_entry/validation/line_check.rb:6-122`. A record of one validation run: `last_line_id` bigint NOT NULL, `errors_found` bool NOT NULL,
`log` text, timestamps. The behaviour is in BR-090..095.

## 8. LineMetadata — `DoubleEntry::LineMetadata` (table `double_entry_line_metadata`, legacy mode, out of scope per D-004) {#line-metadata}

`lib/double_entry/line_metadata.rb:2-17`: `belongs_to :line`, `key` serialised through `SymbolWrapper` (string in DB, Symbol in Ruby), `value` string.
Loaded only when `json_metadata` is false (`lib/double_entry.rb:27-31`). **verified** `oracle/api/table/transfer_with_metadata.json`.

## 9. Locking::Lock (transient) {#lock}

`lib/double_entry/locking.rb:59-172`: holds the sorted accounts. The per-thread lock map `Thread.current[:double_entry_locks]` maps
`Account::Instance → AccountBalance` (locked row) while a `lock_accounts` block runs (BR-061, BR-071).

## 10. Errors (complete list: `inventory/errors.json`) {#errors}

`DoubleEntry::DoubleEntryError < RuntimeError`, with subclasses `UnknownAccount`, `AccountIdentifierTooLongError`, `ScopeIdentifierTooLongError`, `TransferNotAllowed`,
`TransferIsNegative`, `TransferCodeTooLongError`, `DuplicateAccount`, `DuplicateTransfer`, `AccountWouldBeSentNegative`, `AccountWouldBeSentPositiveError`,
`MismatchedCurrencies`, `MissingAccountError` (defined, never raised: grep shows no `raise/fail MissingAccountError`) (`lib/double_entry/errors.rb:3-15`).
`DoubleEntry::Locking::{LockMustBeOutermostTransaction, LockNotHeld, LockDisaster, LockWaitTimeout} < RuntimeError` (**not** DoubleEntryError) (`locking.rb:174-188`).
`ActiveRecord::RestartTransaction < RuntimeError` is internal (`locking_extensions.rb:76`). Messages: 30-http-api.md §4.

## 11. Aggregates for the Kotlin package-by-feature layout (recommendation, inferred) {#aggregates}

- `config` (Configuration, AccountDefinition, TransferDefinition, validation at build time)
- `account` (AccountInstance, scope resolution)
- `ledger` (Line entity, transfer write, credits/debits queries, metadata jsonb)
- `balance` (BalanceCalculator, AccountBalance entity, scopes-with-minimum query)
- `locking` (lock protocol, outermost-transaction guard, SQLSTATE mapping, events)
- `validation` (LineCheck entity, LineCheck service, AccountFixer)
- `money` (Money, CurrencyUnit registry — D-006)
