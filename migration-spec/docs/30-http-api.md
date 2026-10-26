# 30 — HTTP API → library API

## 1. HTTP routes: none

double_entry is a library gem and exposes **no HTTP endpoints**. Evidence: `inventory/routes.txt` lists no `config/routes.rb`, `config.ru`, `app/` or controllers
among the tracked files (`inventory/files.txt`). There are no Rack apps or engines (`inventory/components.json`). So there are no `oracle/http/` samples
(docs/unknowns.md U-001). Per D-001, **the contract to rebuild is the in-process Ruby API below**, and its oracle is `oracle/api/**` (`oracle/index.json`).

Conventions: "Errors" lists the exceptions and messages (**verified** where an oracle file is cited). Authn/authz: none; the caller is trusted (70-security-and-config.md).
Kotlin naming in the last column is a recommendation (inferred). Behaviour is fixed by the BR rules.

## 2. Public API (what host applications call)

### 2.1 `DoubleEntry.configure { |config| … }` {#configure}
- Source: `lib/double_entry/configurable.rb:46-48`, `lib/double_entry/configuration.rb`. Yields `DoubleEntry::Configuration` (10-domain-model.md §1).
- `config.define_accounts { |accounts| accounts.define(identifier:, scope_identifier:, positive_only:, negative_only:, currency:) }` (BR-001, 002, 005).
- `config.define_transfers { |transfers| transfers.define(from:, to:, code:) }` (BR-003, 004, 006).
- `config.json_metadata = true|false` (BR-028/029; Kotlin: jsonb only, D-004). `config.account_identifier_max_length=`, `scope_identifier_max_length=`, `code_max_length=` (BR-005..008).
- `DoubleEntry::Locking.configure { |c| c.running_inside_transactional_fixtures = true }` (BR-060).
- Errors: `DuplicateAccount`, `DuplicateTransfer`, `AccountIdentifierTooLongError`, `TransferCodeTooLongError` (**verified** `config_*.json`).
- Kotlin: an immutable `DoubleEntryConfiguration` built with a DSL or `@Bean`, validated at build time (D-016).

### 2.2 `DoubleEntry.account(identifier, scope: nil, scope_identity: nil)` → `Account::Instance` {#account}
- Source: `lib/double_entry.rb:51-53` → `account.rb:16-19`. Rules BR-009..012.
- Errors: `UnknownAccount` "account: <id> scoped?: <bool>"; `ScopeIdentifierTooLongError`; anything the scope lambda raises (**verified** `account_*.json`).
- Kotlin: `ledger.account(identifier: String, scope: Any? = null)` / `account(identifier, scopeIdentity: String)`.

### 2.3 `DoubleEntry.transfer(amount, from:, to:, code:, detail: nil, metadata: nil)` → `[credit_line, debit_line]` {#transfer}
- Source: `lib/double_entry.rb:88-90` → `transfer.rb:16-22,78-121`. Rules BR-020..035, BR-060..065.
- `amount`: Money ≥ 0. `from`/`to`: `Account::Instance`. `code`: Symbol. `detail`: AR record (polymorphic). `metadata`: Hash.
- Errors, in check order: `TransferIsNegative`; `TransferNotAllowed` "[<from>, <to>, <code>]"; `TransferNotAllowed` "from account and to account are identical"; `MismatchedCurrencies`
  "Mismatched currency (<to> <> <from>)"; `Locking::LockMustBeOutermostTransaction`; `Locking::LockNotHeld`; `AccountWouldBeSentNegative` / `AccountWouldBeSentPositiveError` (message `account.to_s`);
  `Locking::LockWaitTimeout`; DB errors (**verified** `transfer_*.json`).
- Response sample: `oracle/api/json/transfer_return_value.json`, rows `transfer_scoped_basic.json`.
- Kotlin: `ledger.transfer(amount: Money, from: AccountInstance, to: AccountInstance, code: String, detail: DetailRef? = null, metadata: Map<String, Any?>? = null): TransferResult(credit, debit)`.

### 2.4 `DoubleEntry.balance(account_or_symbol, scope: nil, at: nil, from: nil, to: nil, code: nil, codes: nil)` → Money {#balance}
- Also `Account::Instance#balance(options)` (`account.rb:96-98`). Source: `double_entry.rb:142-145` → `balance_calculator.rb:16-96`. Rules BR-040..049.
- Errors: those of `account` when a Symbol is passed (**verified** `balance_scoped_without_scope.json`).
- Samples: `balance_*.json` (fixture: `balance_fixture_lines.json`).

### 2.5 `DoubleEntry.lock_accounts(*accounts) { … }` {#lock_accounts}
- Source: `double_entry.rb:163-165` → `locking.rb:35-51`. Rules BR-060..074.
- Errors: `LockMustBeOutermostTransaction`, `LockNotHeld` "No lock held for account: <id>, scope <scope>", `LockWaitTimeout`, `LockDisaster`, anything the block raises (after rollback).
- Returns nil at the outermost level (Kotlin: the block value, X-05). Samples: `lock_*.json`.

### 2.6 `DoubleEntry::AccountBalance.scopes_with_minimum_balance_for_account(minimum_balance, account_identifier)` → `[String|nil]` {#scopes_with_minimum_balance}
- Source: `account_balance.rb:50-52`. Rule BR-050. Sample: `balance_scopes_with_minimum_balance.json`.

### 2.7 `DoubleEntry::Validation::LineCheck.perform!(fixer: nil)` → `LineCheck | nil`; `LineCheck.last_line_id_checked` → Integer {#linecheck}
- Source: `validation/line_check.rb:8-42`. Rules BR-090..095. Fixer: `DoubleEntry::Validation::AccountFixer.new` (`#recalculate_account(account)`, BR-096).
- Errors: `LockMustBeOutermostTransaction` (inside a transaction), `AccountWouldBeSentNegative` (fixer on positive-only history). Samples: `linecheck_*.json`, `accountfixer_*.json`.

### 2.8 Line queries (host-facing AR API) {#line}
- `DoubleEntry::Line` (AR model): `credits`, `debits`, `with_id_greater_than(id)`, `find_id_and_created_at(conditions:, joins:)`, and per record `amount`, `balance`, `code`, `account`, `partner_account`,
  `partner`, `pair`, `increase?`, `decrease?`, `currency`, `detail`, `metadata` (json mode). Rules BR-080..085. Samples: `line_*.json`, `transfer_return_value.json`.
- Kotlin: a read-only `LineRepository` (Spring Data) with the same query semantics. Lines are immutable for hosts.

### 2.9 Instance helpers {#account_instance}
- `Account::Instance#==, eql?, hash, <=>, to_s, inspect, scope_identity, scope, account, identifier, currency, positive_only, negative_only, scoped?` (BR-012).
- `DoubleEntry::Account.currency(identifier)` (BR-013).

### 2.10 Installation {#install}
- `rails generate double_entry:install [--[no-]json-metadata]` (BR-100); Railtie (BR-101). Kotlin: Flyway migration plus Spring Boot auto-configuration.

### 2.11 Internal API {#internal}
These are `@api private` or internal methods. They have no public-contract status, but are listed so that `inventory/api.json` is fully covered. Behaviour is in the cited rules.

## 3. Error format {#errors}

There is no wire format: errors are Ruby exceptions. Class hierarchy (complete, `inventory/errors.json`): `DoubleEntry::DoubleEntryError < RuntimeError`
(12 subclasses), `DoubleEntry::Locking::* < RuntimeError` (4), and `ActiveRecord::RestartTransaction` (internal). Messages:

| Exception | Message | Evidence |
|---|---|---|
| UnknownAccount | `account: <identifier> scoped?: <true\|false>` | `account.rb:49`; `account_unknown.json` |
| AccountIdentifierTooLongError | `account identifier '<id>' is too long. Please limit it to <n> characters.` | `account.rb:150`; `config_account_identifier_too_long.json` |
| ScopeIdentifierTooLongError | `scope identifier '<identity>' is too long. Please limit it to <n> characters.` | `account.rb:135`; `config_scope_too_long.json` |
| TransferCodeTooLongError | `transfer code '<code>' is too long. Please limit it to <n> characters.` | `transfer.rb:73`; `config_code_too_long.json` |
| TransferNotAllowed | `[<from>, <to>, <code>]` (Ruby inspect: `:sym`, `nil`, `"str"`) / `from account and to account are identical` | `transfer.rb:47,86`; `transfer_not_allowed_code.json`, `transfer_same_account.json` |
| TransferIsNegative, DuplicateAccount, DuplicateTransfer | class name (no message given) | `transfer.rb:17`, `account.rb:36`, `transfer.rb:34` |
| MismatchedCurrencies | `Mismatched currency (<to currency> <> <from currency>)` | `transfer.rb:89`; `transfer_mismatched_account_currencies.json` |
| AccountWouldBeSentNegative / AccountWouldBeSentPositiveError | `account.to_s`, e.g. `#{Account account: savings scope:  currency: USD}` | `line.rb:158,161`; `transfer_positive_only_violation.json` |
| Locking::LockMustBeOutermostTransaction, LockDisaster, LockWaitTimeout | class name | `locking.rb:118,72,47` |
| Locking::LockNotHeld | `No lock held for account: <identifier>, scope <scope object or blank>` | `locking.rb:84`; `lock_wrong_locks.json`, `lock_balance_for_locked_account_outside_lock.json` |
| MissingAccountError | never raised (defined only) | `errors.rb:15` |

## 4. Complete method inventory (generated from `inventory/api.json`, 180 entries)

| Method | Kind | Source | Documented in |
|---|---|---|---|
| `DoubleEntry.account` | class_method | `lib/double_entry.rb:51` | [§account](#account) · BR-009..012 |
| `DoubleEntry.balance` | class_method | `lib/double_entry.rb:142` | [§balance](#balance) · BR-040..049 |
| `DoubleEntry.lock_accounts` | class_method | `lib/double_entry.rb:163` | [§lock_accounts](#lock_accounts) · BR-060..074 |
| `DoubleEntry.table_name_prefix` | class_method | `lib/double_entry.rb:168` | [§internal](#internal) · BR-102 |
| `DoubleEntry.transfer` | class_method | `lib/double_entry.rb:88` | [§transfer](#transfer) · BR-020..035 |
| `DoubleEntry::Account.account` | class_method | `lib/double_entry/account.rb:16` | [§account_instance](#account_instance) · BR-009, BR-013 |
| `DoubleEntry::Account.account_identifier_max_length` | class_method | `lib/double_entry/account.rb:7` | [§configure](#configure) · BR-005, BR-007, BR-008 |
| `DoubleEntry::Account.account_identifier_max_length=` | class_method | `lib/double_entry/account.rb:7` | [§configure](#configure) · BR-005, BR-007, BR-008 |
| `DoubleEntry::Account.accounts` | class_method | `lib/double_entry/account.rb:11` | [§configure](#configure) · BR-005, BR-007, BR-008 |
| `DoubleEntry::Account.accounts=` | class_method | `lib/double_entry/account.rb:8` | [§configure](#configure) · BR-005, BR-007, BR-008 |
| `DoubleEntry::Account.currency` | class_method | `lib/double_entry/account.rb:22` | [§account_instance](#account_instance) · BR-009, BR-013 |
| `DoubleEntry::Account.scope_identifier_max_length` | class_method | `lib/double_entry/account.rb:7` | [§configure](#configure) · BR-005, BR-007, BR-008 |
| `DoubleEntry::Account.scope_identifier_max_length=` | class_method | `lib/double_entry/account.rb:7` | [§configure](#configure) · BR-005, BR-007, BR-008 |
| `DoubleEntry::Account#currency` | instance_method(public) | `lib/double_entry/account.rb:141` | [§internal](#internal) · BR-001, BR-005 |
| `DoubleEntry::Account#identifier` | instance_method(public) | `lib/double_entry/account.rb:141` | [§internal](#internal) · BR-001, BR-005 |
| `DoubleEntry::Account#initialize` | instance_method(private) | `lib/double_entry/account.rb:143` | [§internal](#internal) · BR-001, BR-005 |
| `DoubleEntry::Account#negative_only` | instance_method(public) | `lib/double_entry/account.rb:141` | [§internal](#internal) · BR-001, BR-005 |
| `DoubleEntry::Account#positive_only` | instance_method(public) | `lib/double_entry/account.rb:141` | [§internal](#internal) · BR-001, BR-005 |
| `DoubleEntry::Account#scope_identifier` | instance_method(public) | `lib/double_entry/account.rb:141` | [§internal](#internal) · BR-001, BR-005 |
| `DoubleEntry::Account#scoped?` | instance_method(public) | `lib/double_entry/account.rb:155` | [§internal](#internal) · BR-001, BR-005 |
| `DoubleEntry::Account::Instance#<=>` | instance_method(public) | `lib/double_entry/account.rb:110` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Instance#==` | instance_method(public) | `lib/double_entry/account.rb:102` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Instance#account` | instance_method(public) | `lib/double_entry/account.rb:69` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Instance#balance` | instance_method(public) | `lib/double_entry/account.rb:96` | [§balance](#balance) · BR-040..046 |
| `DoubleEntry::Account::Instance#call_scope_identifier` | instance_method(public) | `lib/double_entry/account.rb:83` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Instance#currency` | instance_method(public) | `lib/double_entry/account.rb:70` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Instance#ensure_scope_is_valid` | instance_method(private) | `lib/double_entry/account.rb:132` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Instance#eql?` | instance_method(public) | `lib/double_entry/account.rb:106` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Instance#hash` | instance_method(public) | `lib/double_entry/account.rb:114` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Instance#identifier` | instance_method(public) | `lib/double_entry/account.rb:70` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Instance#initialize` | instance_method(private) | `lib/double_entry/account.rb:72` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Instance#inspect` | instance_method(public) | `lib/double_entry/account.rb:126` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Instance#negative_only` | instance_method(public) | `lib/double_entry/account.rb:70` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Instance#positive_only` | instance_method(public) | `lib/double_entry/account.rb:70` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Instance#scope` | instance_method(public) | `lib/double_entry/account.rb:69` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Instance#scope_identifier` | instance_method(public) | `lib/double_entry/account.rb:70` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Instance#scope_identity` | instance_method(public) | `lib/double_entry/account.rb:79` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Instance#scoped?` | instance_method(public) | `lib/double_entry/account.rb:70` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Instance#to_s` | instance_method(public) | `lib/double_entry/account.rb:122` | [§account_instance](#account_instance) · BR-007, BR-010, BR-012 |
| `DoubleEntry::Account::Set#all` | instance_method(public) | `lib/double_entry/account.rb:57` | [§internal](#internal) · BR-001, BR-002, BR-009 |
| `DoubleEntry::Account::Set#backing_collection` | instance_method(private) | `lib/double_entry/account.rb:63` | [§internal](#internal) · BR-001, BR-002, BR-009 |
| `DoubleEntry::Account::Set#define` | instance_method(public) | `lib/double_entry/account.rb:33` | [§internal](#internal) · BR-001, BR-002, BR-009 |
| `DoubleEntry::Account::Set#find` | instance_method(public) | `lib/double_entry/account.rb:43` | [§internal](#internal) · BR-001, BR-002, BR-009 |
| `DoubleEntry::Account::Set#find_without_scope` | instance_method(public) | `lib/double_entry/account.rb:53` | [§internal](#internal) · BR-001, BR-002, BR-009 |
| `DoubleEntry::AccountBalance.find_by_account` | class_method | `lib/double_entry/account_balance.rb:31` | [§internal](#internal) · BR-061, BR-062, BR-025 |
| `DoubleEntry::AccountBalance.scopes_with_minimum_balance_for_account` | class_method | `lib/double_entry/account_balance.rb:50` | [§scopes_with_minimum_balance](#scopes_with_minimum_balance) · BR-050 |
| `DoubleEntry::AccountBalance#account` | instance_method(public) | `lib/double_entry/account_balance.rb:27` | [§internal](#internal) · BR-061, BR-062, BR-025 |
| `DoubleEntry::AccountBalance#account=` | instance_method(public) | `lib/double_entry/account_balance.rb:21` | [§internal](#internal) · BR-061, BR-062, BR-025 |
| `DoubleEntry::AccountBalance#balance` | instance_method(public) | `lib/double_entry/account_balance.rb:13` | [§internal](#internal) · BR-061, BR-062, BR-025 |
| `DoubleEntry::AccountBalance#balance=` | instance_method(public) | `lib/double_entry/account_balance.rb:17` | [§internal](#internal) · BR-061, BR-062, BR-025 |
| `DoubleEntry::AccountBalance#currency` | instance_method(public) | `lib/double_entry/account_balance.rb:11` | [§internal](#internal) · BR-061, BR-062, BR-025 |
| `DoubleEntry::BalanceCalculator#calculate` | instance_method(public) | `lib/double_entry/balance_calculator.rb:16` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator#force_index?` | instance_method(private) | `lib/double_entry/balance_calculator.rb:41` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator#lines_table_name` | instance_method(private) | `lib/double_entry/balance_calculator.rb:37` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::Options#account` | instance_method(public) | `lib/double_entry/balance_calculator.rb:51` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::Options#at` | instance_method(public) | `lib/double_entry/balance_calculator.rb:51` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::Options#at?` | instance_method(public) | `lib/double_entry/balance_calculator.rb:62` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::Options#between?` | instance_method(public) | `lib/double_entry/balance_calculator.rb:66` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::Options#code?` | instance_method(public) | `lib/double_entry/balance_calculator.rb:70` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::Options#codes` | instance_method(public) | `lib/double_entry/balance_calculator.rb:51` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::Options#from` | instance_method(public) | `lib/double_entry/balance_calculator.rb:51` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::Options#initialize` | instance_method(private) | `lib/double_entry/balance_calculator.rb:53` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::Options#scope` | instance_method(public) | `lib/double_entry/balance_calculator.rb:51` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::Options#scope?` | instance_method(public) | `lib/double_entry/balance_calculator.rb:74` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::Options#to` | instance_method(public) | `lib/double_entry/balance_calculator.rb:51` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::RelationBuilder#account` | instance_method(public) | `lib/double_entry/balance_calculator.rb:82` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::RelationBuilder#at` | instance_method(public) | `lib/double_entry/balance_calculator.rb:82` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::RelationBuilder#at?` | instance_method(public) | `lib/double_entry/balance_calculator.rb:82` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::RelationBuilder#between?` | instance_method(public) | `lib/double_entry/balance_calculator.rb:82` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::RelationBuilder#build` | instance_method(public) | `lib/double_entry/balance_calculator.rb:88` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::RelationBuilder#code?` | instance_method(public) | `lib/double_entry/balance_calculator.rb:82` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::RelationBuilder#codes` | instance_method(public) | `lib/double_entry/balance_calculator.rb:82` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::RelationBuilder#from` | instance_method(public) | `lib/double_entry/balance_calculator.rb:82` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::RelationBuilder#initialize` | instance_method(private) | `lib/double_entry/balance_calculator.rb:84` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::RelationBuilder#options` | instance_method(public) | `lib/double_entry/balance_calculator.rb:81` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::RelationBuilder#scope` | instance_method(public) | `lib/double_entry/balance_calculator.rb:82` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::RelationBuilder#scope?` | instance_method(public) | `lib/double_entry/balance_calculator.rb:82` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::BalanceCalculator::RelationBuilder#to` | instance_method(public) | `lib/double_entry/balance_calculator.rb:82` | [§internal](#internal) · BR-040..047 |
| `DoubleEntry::Configurable.included` | class_method | `lib/double_entry/configurable.rb:36` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configurable::ClassMethods#config` | instance_method(public) | `lib/double_entry/configurable.rb:41` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configurable::ClassMethods#configuration` | instance_method(public) | `lib/double_entry/configurable.rb:41` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configurable::ClassMethods#configure` | instance_method(public) | `lib/double_entry/configurable.rb:46` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configuration#account_identifier_max_length` | instance_method(public) | `lib/double_entry/configuration.rb:12` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configuration#account_identifier_max_length=` | instance_method(public) | `lib/double_entry/configuration.rb:12` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configuration#accounts` | instance_method(public) | `lib/double_entry/configuration.rb:12` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configuration#accounts=` | instance_method(public) | `lib/double_entry/configuration.rb:12` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configuration#code_max_length` | instance_method(public) | `lib/double_entry/configuration.rb:22` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configuration#code_max_length=` | instance_method(public) | `lib/double_entry/configuration.rb:22` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configuration#define_accounts` | instance_method(public) | `lib/double_entry/configuration.rb:30` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configuration#define_transfers` | instance_method(public) | `lib/double_entry/configuration.rb:34` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configuration#initialize` | instance_method(private) | `lib/double_entry/configuration.rb:8` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configuration#json_metadata` | instance_method(public) | `lib/double_entry/configuration.rb:6` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configuration#json_metadata=` | instance_method(public) | `lib/double_entry/configuration.rb:6` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configuration#scope_identifier_max_length` | instance_method(public) | `lib/double_entry/configuration.rb:12` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configuration#scope_identifier_max_length=` | instance_method(public) | `lib/double_entry/configuration.rb:12` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configuration#transfers` | instance_method(public) | `lib/double_entry/configuration.rb:22` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Configuration#transfers=` | instance_method(public) | `lib/double_entry/configuration.rb:22` | [§configure](#configure) · BR-001..008 |
| `DoubleEntry::Generators::InstallGenerator.next_migration_number` | class_method | `lib/generators/double_entry/install/install_generator.rb:14` | [§install](#install) · BR-100 |
| `DoubleEntry::Generators::InstallGenerator#copy_migrations` | instance_method(public) | `lib/generators/double_entry/install/install_generator.rb:18` | [§install](#install) · BR-100 |
| `DoubleEntry::Generators::InstallGenerator#create_initializer` | instance_method(public) | `lib/generators/double_entry/install/install_generator.rb:22` | [§install](#install) · BR-100 |
| `DoubleEntry::Generators::InstallGenerator#json_metadata` | instance_method(public) | `lib/generators/double_entry/install/install_generator.rb:26` | [§install](#install) · BR-100 |
| `DoubleEntry::Generators::InstallGenerator#migration_version` | instance_method(public) | `lib/generators/double_entry/install/install_generator.rb:35` | [§install](#install) · BR-100 |
| `DoubleEntry::Line.find_id_and_created_at` | class_method | `lib/double_entry/line.rb:147` | [§line](#line) · BR-084 |
| `DoubleEntry::Line#account` | instance_method(public) | `lib/double_entry/line.rb:106` | [§line](#line) · BR-080 |
| `DoubleEntry::Line#account=` | instance_method(public) | `lib/double_entry/line.rb:99` | [§line](#line) · BR-080 |
| `DoubleEntry::Line#amount` | instance_method(public) | `lib/double_entry/line.rb:64` | [§line](#line) · BR-080 |
| `DoubleEntry::Line#amount=` | instance_method(public) | `lib/double_entry/line.rb:68` | [§line](#line) · BR-080 |
| `DoubleEntry::Line#balance` | instance_method(public) | `lib/double_entry/line.rb:72` | [§line](#line) · BR-080 |
| `DoubleEntry::Line#balance=` | instance_method(public) | `lib/double_entry/line.rb:76` | [§line](#line) · BR-080 |
| `DoubleEntry::Line#check_balance_will_remain_valid` | instance_method(private) | `lib/double_entry/line.rb:156` | [§line](#line) · BR-030, BR-085 |
| `DoubleEntry::Line#code` | instance_method(public) | `lib/double_entry/line.rb:95` | [§line](#line) · BR-080 |
| `DoubleEntry::Line#code=` | instance_method(public) | `lib/double_entry/line.rb:90` | [§line](#line) · BR-080 |
| `DoubleEntry::Line#currency` | instance_method(public) | `lib/double_entry/line.rb:110` | [§line](#line) · BR-080 |
| `DoubleEntry::Line#decrease?` | instance_method(public) | `lib/double_entry/line.rb:137` | [§line](#line) · BR-083 |
| `DoubleEntry::Line#increase?` | instance_method(public) | `lib/double_entry/line.rb:141` | [§line](#line) · BR-083 |
| `DoubleEntry::Line#pair` | instance_method(public) | `lib/double_entry/line.rb:129` | [§line](#line) · BR-083 |
| `DoubleEntry::Line#partner` | instance_method(public) | `lib/double_entry/line.rb:125` | [§line](#line) · BR-083 |
| `DoubleEntry::Line#partner_account` | instance_method(public) | `lib/double_entry/line.rb:121` | [§line](#line) · BR-080 |
| `DoubleEntry::Line#partner_account=` | instance_method(public) | `lib/double_entry/line.rb:114` | [§line](#line) · BR-080 |
| `DoubleEntry::Line#save` | instance_method(public) | `lib/double_entry/line.rb:80` | [§line](#line) · BR-030, BR-085 |
| `DoubleEntry::Line#save!` | instance_method(public) | `lib/double_entry/line.rb:85` | [§line](#line) · BR-030, BR-085 |
| `DoubleEntry::LineMetadata::SymbolWrapper.dump` | class_method | `lib/double_entry/line_metadata.rb:9` | [§internal](#internal) · BR-029 |
| `DoubleEntry::LineMetadata::SymbolWrapper.load` | class_method | `lib/double_entry/line_metadata.rb:4` | [§internal](#internal) · BR-029 |
| `DoubleEntry::Locking.balance_for_locked_account` | class_method | `lib/double_entry/locking.rb:55` | [§lock_accounts](#lock_accounts) · BR-060..073 |
| `DoubleEntry::Locking.lock_accounts` | class_method | `lib/double_entry/locking.rb:35` | [§lock_accounts](#lock_accounts) · BR-060..073 |
| `DoubleEntry::Locking::Configuration#initialize` | instance_method(private) | `lib/double_entry/locking.rb:26` | [§configure](#configure) · BR-060 |
| `DoubleEntry::Locking::Configuration#running_inside_transactional_fixtures` | instance_method(public) | `lib/double_entry/locking.rb:24` | [§configure](#configure) · BR-060 |
| `DoubleEntry::Locking::Configuration#running_inside_transactional_fixtures=` | instance_method(public) | `lib/double_entry/locking.rb:24` | [§configure](#configure) · BR-060 |
| `DoubleEntry::Locking::Lock#balance_for` | instance_method(public) | `lib/double_entry/locking.rb:89` | [§internal](#internal) · BR-060..074 |
| `DoubleEntry::Locking::Lock#create_missing_account_balances` | instance_method(private) | `lib/double_entry/locking.rb:164` | [§internal](#internal) · BR-060..074 |
| `DoubleEntry::Locking::Lock#ensure_locked!` | instance_method(public) | `lib/double_entry/locking.rb:81` | [§internal](#internal) · BR-060..074 |
| `DoubleEntry::Locking::Lock#ensure_outermost_transaction!` | instance_method(private) | `lib/double_entry/locking.rb:115` | [§internal](#internal) · BR-060..074 |
| `DoubleEntry::Locking::Lock#grab_locks` | instance_method(private) | `lib/double_entry/locking.rb:149` | [§internal](#internal) · BR-060..074 |
| `DoubleEntry::Locking::Lock#in_a_locked_transaction?` | instance_method(public) | `lib/double_entry/locking.rb:77` | [§internal](#internal) · BR-060..074 |
| `DoubleEntry::Locking::Lock#initialize` | instance_method(private) | `lib/double_entry/locking.rb:60` | [§internal](#internal) · BR-060..074 |
| `DoubleEntry::Locking::Lock#lock?` | instance_method(private) | `lib/double_entry/locking.rb:110` | [§internal](#internal) · BR-060..074 |
| `DoubleEntry::Locking::Lock#lock_and_call` | instance_method(private) | `lib/double_entry/locking.rb:127` | [§internal](#internal) · BR-060..074 |
| `DoubleEntry::Locking::Lock#locks` | instance_method(private) | `lib/double_entry/locking.rb:97` | [§internal](#internal) · BR-060..074 |
| `DoubleEntry::Locking::Lock#locks=` | instance_method(private) | `lib/double_entry/locking.rb:101` | [§internal](#internal) · BR-060..074 |
| `DoubleEntry::Locking::Lock#perform_lock` | instance_method(public) | `lib/double_entry/locking.rb:67` | [§internal](#internal) · BR-060..074 |
| `DoubleEntry::Locking::Lock#remove_locks` | instance_method(private) | `lib/double_entry/locking.rb:105` | [§internal](#internal) · BR-060..074 |
| `DoubleEntry::Transfer.code_max_length` | class_method | `lib/double_entry/transfer.rb:7` | [§configure](#configure) · BR-003, BR-006 |
| `DoubleEntry::Transfer.code_max_length=` | class_method | `lib/double_entry/transfer.rb:7` | [§configure](#configure) · BR-003, BR-006 |
| `DoubleEntry::Transfer.transfer` | class_method | `lib/double_entry/transfer.rb:16` | [§transfer](#transfer) · BR-020, BR-021 |
| `DoubleEntry::Transfer.transfers` | class_method | `lib/double_entry/transfer.rb:11` | [§configure](#configure) · BR-003, BR-006 |
| `DoubleEntry::Transfer.transfers=` | class_method | `lib/double_entry/transfer.rb:8` | [§configure](#configure) · BR-003, BR-006 |
| `DoubleEntry::Transfer#code` | instance_method(public) | `lib/double_entry/transfer.rb:66` | [§internal](#internal) · BR-021, BR-025 |
| `DoubleEntry::Transfer#create_line_metadata` | instance_method(public) | `lib/double_entry/transfer.rb:123` | [§internal](#internal) · BR-029 |
| `DoubleEntry::Transfer#create_lines` | instance_method(public) | `lib/double_entry/transfer.rb:98` | [§internal](#internal) · BR-021, BR-025 |
| `DoubleEntry::Transfer#from` | instance_method(public) | `lib/double_entry/transfer.rb:66` | [§internal](#internal) · BR-021, BR-025 |
| `DoubleEntry::Transfer#initialize` | instance_method(private) | `lib/double_entry/transfer.rb:68` | [§internal](#internal) · BR-021, BR-025 |
| `DoubleEntry::Transfer#process` | instance_method(public) | `lib/double_entry/transfer.rb:78` | [§internal](#internal) · BR-021, BR-025 |
| `DoubleEntry::Transfer#to` | instance_method(public) | `lib/double_entry/transfer.rb:66` | [§internal](#internal) · BR-021, BR-025 |
| `DoubleEntry::Transfer::Set#_find` | instance_method(private) | `lib/double_entry/transfer.rb:61` | [§internal](#internal) · BR-003, BR-020 |
| `DoubleEntry::Transfer::Set#all` | instance_method(public) | `lib/double_entry/transfer.rb:51` | [§internal](#internal) · BR-003, BR-020 |
| `DoubleEntry::Transfer::Set#backing_collection` | instance_method(private) | `lib/double_entry/transfer.rb:57` | [§internal](#internal) · BR-003, BR-020 |
| `DoubleEntry::Transfer::Set#define` | instance_method(public) | `lib/double_entry/transfer.rb:30` | [§internal](#internal) · BR-003, BR-020 |
| `DoubleEntry::Transfer::Set#find` | instance_method(public) | `lib/double_entry/transfer.rb:41` | [§internal](#internal) · BR-003, BR-020 |
| `DoubleEntry::Transfer::Set#find!` | instance_method(public) | `lib/double_entry/transfer.rb:45` | [§internal](#internal) · BR-003, BR-020 |
| `DoubleEntry::Validation::AccountFixer#lines_for_account` | instance_method(private) | `lib/double_entry/validation/account_fixer.rb:23` | [§linecheck](#linecheck) · BR-096 |
| `DoubleEntry::Validation::AccountFixer#recalculate_account` | instance_method(public) | `lib/double_entry/validation/account_fixer.rb:6` | [§linecheck](#linecheck) · BR-096 |
| `DoubleEntry::Validation::AccountFixer#update_balance_for_account` | instance_method(private) | `lib/double_entry/validation/account_fixer.rb:30` | [§linecheck](#linecheck) · BR-096 |
| `DoubleEntry::Validation::LineCheck.last_line_id_checked` | class_method | `lib/double_entry/validation/line_check.rb:8` | [§linecheck](#linecheck) · BR-090..095 |
| `DoubleEntry::Validation::LineCheck.perform!` | class_method | `lib/double_entry/validation/line_check.rb:12` | [§linecheck](#linecheck) · BR-090..095 |
| `DoubleEntry::Validation::LineCheck#cached_balance_correct?` | instance_method(private) | `lib/double_entry/validation/line_check.rb:108` | [§linecheck](#linecheck) · BR-090..095 |
| `DoubleEntry::Validation::LineCheck#find_previous_line` | instance_method(private) | `lib/double_entry/validation/line_check.rb:62` | [§linecheck](#linecheck) · BR-090..095 |
| `DoubleEntry::Validation::LineCheck#force_index` | instance_method(private) | `lib/double_entry/validation/line_check.rb:87` | [§linecheck](#linecheck) · BR-090..095 |
| `DoubleEntry::Validation::LineCheck#line_error_message` | instance_method(private) | `lib/double_entry/validation/line_check.rb:97` | [§linecheck](#linecheck) · BR-090..095 |
| `DoubleEntry::Validation::LineCheck#new_lines_since_last_run` | instance_method(private) | `lib/double_entry/validation/line_check.rb:46` | [§linecheck](#linecheck) · BR-090..095 |
| `DoubleEntry::Validation::LineCheck#perform` | instance_method(public) | `lib/double_entry/validation/line_check.rb:16` | [§linecheck](#linecheck) · BR-090..095 |
| `DoubleEntry::Validation::LineCheck#running_balance_correct?` | instance_method(private) | `lib/double_entry/validation/line_check.rb:50` | [§linecheck](#linecheck) · BR-090..095 |
| `ActiveRecord::LockingExtensions#create_ignoring_duplicates!` | instance_method(public) | `lib/active_record/locking_extensions.rb:31` | [§internal](#internal) · BR-067..069 |
| `ActiveRecord::LockingExtensions#ignoring_duplicates` | instance_method(private) | `lib/active_record/locking_extensions.rb:41` | [§internal](#internal) · BR-067..069 |
| `ActiveRecord::LockingExtensions#restartable_transaction` | instance_method(public) | `lib/active_record/locking_extensions.rb:9` | [§internal](#internal) · BR-067..069 |
| `ActiveRecord::LockingExtensions#retry_deadlocks` | instance_method(private) | `lib/active_record/locking_extensions.rb:57` | [§internal](#internal) · BR-067..069 |
| `ActiveRecord::LockingExtensions#with_restart_on_deadlock` | instance_method(public) | `lib/active_record/locking_extensions.rb:17` | [§internal](#internal) · BR-067..069 |
| `ActiveRecord::LockingExtensions::LogSubscriber#deadlock_restart` | instance_method(public) | `lib/active_record/locking_extensions/log_subscriber.rb:7` | [§internal](#internal) · BR-072 |
| `ActiveRecord::LockingExtensions::LogSubscriber#deadlock_retry` | instance_method(public) | `lib/active_record/locking_extensions/log_subscriber.rb:12` | [§internal](#internal) · BR-072 |
| `ActiveRecord::LockingExtensions::LogSubscriber#duplicate_ignore` | instance_method(public) | `lib/active_record/locking_extensions/log_subscriber.rb:17` | [§internal](#internal) · BR-072 |
| `ActiveRecord::LockingExtensions::LogSubscriber#logger` | instance_method(public) | `lib/active_record/locking_extensions/log_subscriber.rb:22` | [§internal](#internal) · BR-072 |
