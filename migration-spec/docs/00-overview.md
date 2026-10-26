# 00 — Overview: double_entry

Source: https://github.com/softberries/double_entry @ `f1474f0fe160edadd3b3264397b85d38d3a7751d`. This is a fork of `envato/double_entry`, gem version
2.0.2 (`lib/double_entry/version.rb:4`). Evidence notation: `path:line` = Ruby repo; `spec … [id]` = RSpec example id from
`inventory/specs.json`; `oracle/…` = observed at runtime. **verified** = observed in the oracle or by a passing spec; **inferred** = read from code only.

## 1. Purpose

DoubleEntry is a **Ruby library (gem) for keeping a double-entry ledger inside a Rails application's database**
(`README.md:11-18`, `double_entry.gemspec:12` "Tools to build your double entry financial ledger"). It:

- lets the host app **configure accounts** (identifier, optional scope, currency, positive/negative-only) and an **allowlist of transfers**
  (from, to, code) (`README.md:203-251`, `lib/double_entry/configuration.rb`) — **verified** (`oracle/api/json/config_*.json`);
- records each **transfer** as **two lines** (a negative line on the source and a positive line on the destination), each carrying the account's
  **running balance** (`lib/double_entry/transfer.rb:98-121`) — **verified** (`oracle/api/json/transfer_scoped_basic.json`);
- caches the current balance per account in `double_entry_account_balances`, whose rows are also the **DB lock objects** used to serialise
  transfers (`lib/double_entry/account_balance.rb:3-9`, `lib/double_entry/locking.rb`) — **verified** (`oracle/api/json/lock_order_sql.json`);
- answers **balance queries**: current, at a point in time, over a period, filtered by transfer code (`lib/double_entry/balance_calculator.rb`) — **verified** (`oracle/api/json/balance_*.json`);
- offers **consistency checking and repair** of running and cached balances (`lib/double_entry/validation/*`) — **verified** (`oracle/api/json/linecheck_*.json`).

It does **not** enforce accounting rules beyond the transfer allowlist and positive/negative-only accounts (`README.md:15-16`).

## 2. Users / roles

| Role | How they interact | Evidence |
|---|---|---|
| Host application developer | Adds the gem, runs `rails g double_entry:install`, writes `config/initializers/double_entry.rb`, calls the Ruby API | `README.md:40-66,203-251` |
| Host application code (runtime) | Calls `DoubleEntry.account/transfer/balance/lock_accounts`, `AccountBalance.scopes_with_minimum_balance_for_account`, `Validation::LineCheck.perform!` | `lib/double_entry.rb:51-165`, `lib/double_entry/account_balance.rb:50`, `README.md:174-182` |
| Scheduled job in the host app (optional) | Runs `LineCheck.perform!(fixer: …)` hourly to daily | `README.md:172` (recommendation only; the gem ships no scheduler) |

There are **no end users, no authentication and no authorisation**: the library trusts its caller (inferred from the complete `inventory/api.json`; no auth code exists).
"Scope" (e.g. a user id) partitions accounts, but it is not a security boundary (`README.md:83-88`).

## 3. Architecture

```
Host app (Rails)                                  double_entry gem (lib/)
────────────────                                  ───────────────────────────────────────────────────────────────
config/initializers/double_entry.rb ──configure──▶ Configuration (accounts Set, transfers Set, json_metadata, max lengths)
                                                    │  lib/double_entry/configuration.rb, account.rb (Set), transfer.rb (Set)
DoubleEntry.account(:id, scope:) ────────────────▶ Account::Instance (identifier + scope identity + currency)
DoubleEntry.transfer(money, from:, to:, code:) ───▶ Transfer.process ─▶ Locking.lock_accounts ─▶ AccountBalance (SELECT … FOR UPDATE)
                                                    │                         └─▶ Line ×2 (INSERT) [+ LineMetadata rows in legacy mode]
DoubleEntry.balance(account, at:/from:/to:/code:) ▶ BalanceCalculator ─▶ Line (latest balance or SUM(amount))
DoubleEntry.lock_accounts(a, b) { … } ───────────▶ Locking (thread-local lock map, outermost transaction)
LineCheck.perform!(fixer:) ──────────────────────▶ Validation::LineCheck ─▶ AccountFixer ─▶ Line/AccountBalance updates
                                                   ActiveRecord::LockingExtensions (restartable_transaction, deadlock retry,
                                                    create_ignoring_duplicates!) + LogSubscriber (notifications → log)
```

- Components (complete list: `inventory/components.json`, `inventory/api.json`): `DoubleEntry` module facade (`lib/double_entry.rb`),
  `Account`/`Account::Set`/`Account::Instance` (`account.rb`), `Transfer`/`Transfer::Set` (`transfer.rb`), `Line` (AR, `line.rb`),
  `AccountBalance` (AR, `account_balance.rb`), `LineMetadata` (AR, legacy, `line_metadata.rb`), `BalanceCalculator` (+`Options`, `RelationBuilder`),
  `Locking` (+`Lock`, `Configuration`), `Validation::LineCheck` (AR), `Validation::AccountFixer`, `Configurable` mixin, errors (`errors.rb`),
  `ActiveRecord::LockingExtensions` (+`LogSubscriber`), `Railtie` (`double_entry.rb:25-33`), and `Generators::InstallGenerator` with templates.
- All state is in 4 DB tables (50-data.md) plus process-global configuration (10-domain-model.md §1) and a **thread-local** lock map
  (`locking.rb:97-107`).
- No HTTP, UI, background jobs, mailers, ActionCable, cache, Redis or external APIs (`inventory/components.json`: 0 files in these categories;
  `inventory/routes.txt`). Details: 30-http-api.md, 40-ui.md, 60-async-and-integrations.md.

## 4. Request flow (the call path of `DoubleEntry.transfer`)

**verified** by the SQL in `oracle/api/json/transfer_scoped_basic.json`:

1. `DoubleEntry.transfer(amount, from:, to:, code:, detail:, metadata:)` → `Transfer.transfer` (`double_entry.rb:88-90`, `transfer.rb:16-22`).
2. Negative amount → `TransferIsNegative`. Then allowlist lookup on `[from.identifier, to.identifier, code]` → `TransferNotAllowed` (BR-020/021).
3. `Transfer#process`: identical account → `TransferNotAllowed`; account currencies differ → `MismatchedCurrencies` (`transfer.rb:85-90`).
4. `Locking.lock_accounts(from, to)` (`locking.rb:35-51`): outermost-transaction check, accounts sorted, and in a transaction
   `SELECT … FROM double_entry_account_balances WHERE scope … AND account = … ORDER BY id LIMIT 1 FOR UPDATE` per account. If a row is missing,
   that transaction ends; each missing row is created from the lines' latest balance (one transaction each) and the lock is retried (BR-061/062).
5. Inside the lock: `UPDATE` the from balance row (−amount), `UPDATE` the to balance row (+amount), `INSERT` the credit line (from, −amount,
   new from balance, partner = to), `INSERT` the debit line (to, +amount, new to balance, `partner_id` = credit id), `UPDATE` the credit line's
   `partner_id` = debit id. `COMMIT` (`transfer.rb:98-121`).
6. Returns `[credit_line, debit_line]` (`double_entry.rb:83`, CHANGELOG 2.0.0 #190).

## 5. Tech stack (Ruby)

| Item | Version observed | Evidence |
|---|---|---|
| Ruby | 4.0.5 (gemspec `>= 3`; CI 3.2–4.0) | `oracle/environment.txt`, `double_entry.gemspec:28`, `.github/workflows/ci-workflow.yml:9` |
| ActiveRecord / ActiveSupport / Railties | 8.1.4 (gemspec `>= 6.1.0`; CI 7.2, 8.0, 8.1) | `inventory/gems.txt`, `double_entry.gemspec:30-33` |
| money gem | 7.1.1 (`>= 6.0.0`) | `inventory/gems.txt` |
| Databases | PostgreSQL (17.11 tested here), MySQL 8 (CI), SQLite (tested here) | `oracle/environment.txt`, `inventory/specs_run_{postgres,sqlite}.json` |
| Tests | RSpec 3.13, factory_bot, database_cleaner (truncation), timecop, rspec-its, generator_spec, ruby-prof | `inventory/gems.txt`, `spec/support/*` |

## 6. Gem → JVM library mapping (target stack, D-001)

Direct dependencies from `double_entry.gemspec:30-46`. Transitive gems in `inventory/gems.txt` are covered by their parent row.

| Gem | Role in the gem | Kotlin / JVM equivalent | Status |
|---|---|---|---|
| activerecord | ORM for `Line`, `AccountBalance`, `LineCheck`, `LineMetadata`; transactions; `lock(true)` | Spring Data JPA (Hibernate) entities + `@Lock(PESSIMISTIC_WRITE)` / native `FOR UPDATE`; `TransactionTemplate` | target stack |
| activesupport | `delegate`, `present?`, `strip_heredoc`, `Notifications`, `LogSubscriber`, time helpers | Kotlin stdlib; Spring `ApplicationEventPublisher` + SLF4J + Micrometer (D-012) | decided |
| money (7.1.1) | Money value object, currency table, HALF_EVEN rounding, formatting | own `Money(minorUnits: Long, currency: CurrencyUnit)` + registry ported from money gem (D-006) | decided |
| railties | `Railtie` (after_initialize loads models), `rails g double_entry:install` | Spring Boot auto-configuration (`@AutoConfiguration`, `@ConfigurationProperties`) + Flyway migration shipped in the jar (D-001, ID-3) | decided |
| pg (dev) | PostgreSQL driver | PostgreSQL JDBC driver; Testcontainers PostgreSQL | target stack |
| mysql2, sqlite3 (dev) | MySQL/SQLite drivers | — (PostgreSQL only, D-002) | decided: dropped |
| rake (dev) | `rake spec`, gem build/release tasks | Gradle tasks (`test`, `publish`) | target stack |
| rspec, rspec-its (dev) | test framework | JUnit 5 + Kotest assertions | target stack |
| factory_bot (dev) | `:user` factory with balances | Kotlin test fixtures/builders | target stack |
| database_cleaner (dev) | truncation before each example | Testcontainers PG + `TRUNCATE … RESTART IDENTITY` per test | target stack |
| timecop (dev) | freeze time | injectable `java.time.Clock` (the library must take a `Clock` for `created_at`) | needs decision → recommended: inject `Clock` (Kotlin team, U-008) |
| generator_spec (dev) | tests the install generator | Flyway migration test (schema equals `inventory/structure_generated_json_metadata_true.sql` + X-03) | target stack |
| ruby-prof (dev) | performance specs (no assertions) | optional JMH or a timed JUnit test; no parity requirement | needs decision (Kotlin team, low priority) |
| — (`script/jack_hammer`) | multi-process/thread concurrency reconciliation | Testcontainers concurrency test (threads + mixed Ruby/Kotlin per D-017) | decided (ID-3) |

## 7. Document map

10 domain model · 20 business rules (BR-###) · 30 library API (the gem has no HTTP routes) · 40 UI (none) · 50 data · 60 async/integrations (none; instrumentation) ·
70 security/config · 80 tests · `coverage.json` · `unknowns.md` · `decisions.md` (D-001..D-017, deviations X-01..X-10).
