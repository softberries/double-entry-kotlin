# Decisions — double_entry → Kotlin

Source: https://github.com/softberries/double_entry @ `f1474f0fe160edadd3b3264397b85d38d3a7751d` (gem version 2.0.2).
Each entry: question, answer, consequences. **→ CF** means the general Ruby→Kotlin part was also stored in Context Fabric
(project `ruby-to-kotlin-migration-demo`) as a self-contained `[ruby→kotlin]` sentence. Service-specific facts live only here.

## Inherited decisions

Phase 0 (2026-10-26):
- Read `/project-workspace/wiki/AGENTS.md`. It lists repo slug `double-entry` and project standards (MySQL and PostgreSQL; observability deps under evaluation). It contains no Ruby→Kotlin rules.
- `repo_insights(double-entry)`: hot files, tribal knowledge (locking must be outermost, the lines table is append-only, deadlock detection by message text) and one team-memory gotcha from an earlier run today: "gem, specs need spec/support/database.yml and DB=…; mysql2 won't build without libmysqlclient; ruby-prof 2.x breaks perf specs; Ruby 4.0.5/PG17/Rails 8.1.4: 167 examples/0 failures". This run reproduced it: inventory/specs_run_postgres.json.
- `wiki_find` exact `ruby→kotlin|ruby->kotlin|Kotlin` (ignore case) → no wiki matches. Meaning search "ruby to kotlin migration conventions parity policy" → no page.
- `[ruby→kotlin]` rules stored by the earlier `open-banking-rails` investigation (`/project-workspace/migrations/open-banking-rails/docs/decisions.md`, marked **→ CF** there; ids recorded in its `pending-context-fabric.md`) were applied as below.

| ID | Inherited decision | Source | Applies here |
|---|---|---|---|
| ID-1 | **Fixed target stack**: Kotlin/JVM 21, Spring Boot 3.x, Spring Web MVC, Spring Data JPA (Hibernate), Flyway, Jackson Kotlin, Spring Security, JTE, JUnit 5 + Kotest + MockK, Testcontainers (PostgreSQL, Redis), Gradle Kotlin DSL + version catalog + wrapper, ktlint, package-by-feature | `CLAUDE.md` "Target stack" | **Partly.** The library parts apply: Kotlin/JVM 21, Spring Boot 3 auto-configuration, Spring Data JPA, Flyway, Jackson (jsonb metadata), JUnit 5/Kotest/MockK, Testcontainers PostgreSQL, Gradle KTS, ktlint. **Not applicable** (D-001): Spring Web MVC, JTE, Spring Security (the library has no HTTP or UI), Redis (the gem never uses Redis; Testcontainers Redis is not needed) |
| ID-2 | **[ruby→kotlin] parity policy**: preserve valid-input behaviour; fix tenant-isolation defects and crashes on invalid input; every other deviation needs a recorded decision and a test | Context Fabric id `334aa226…` (from open-banking-rails D-002) | **Yes.** No tenant-isolation surface exists: scopes are host-provided strings and the library has no authorisation. "Crash on invalid input" for a library is interpreted in D-013. Every deviation is listed in the register below |
| ID-3 | **[ruby→kotlin] unused framework engines/routes and dev debug routes are not ported** | AGENTS.md team memory / open-banking-rails D-006 | Yes, by analogy: the Rails generator (`rails g double_entry:install`) and the Railtie are replaced by a Flyway migration and Spring Boot auto-configuration. `script/jack_hammer` becomes a concurrency test, not a shipped tool (00-overview.md §6) |
| ID-4 | **[ruby→kotlin] documentation-only features are out of scope** | open-banking-rails D-014 | Yes. README "Future Direction" (GitHub issues) is not scope. All README features exist in code |
| ID-5 | PostgreSQL as primary relational store | AGENTS.md "Standards" | Yes, see D-002 |
| ID-6 | Structured logging / OpenTelemetry | open-banking-rails ID-4/ID-5 | Library-level: log through SLF4J only. The host configures structured output and OTel (D-012) |

## Decisions taken in this investigation

All 17 questions were asked via `ask_user` on 2026-10-26 in 3 rounds (6 + 6 + 5). Every answer took the recommended option.

### D-001 Deliverable form (round 1, Q1) **→ CF**
- **Question:** double_entry is a library gem used in-process by host Rails apps (no routes, UI or jobs). What should the Kotlin deliverable be?
- **Options:** (A) Kotlin library jar on Spring Data JPA + Flyway with a Spring Boot auto-configuration starter, same API surface *(recommended)*; (B) standalone REST ledger service; (C) both; (D) other.
- **Answer:** **(A).**
- **Consequences:** parity means the **in-process API** in 30-http-api.md (which documents the library API, since there are no routes) plus the DB rows it writes (50-data.md). The oracle is `oracle/api/**`, with no HTTP samples or screenshots. The web parts of the target stack do not apply (ID-1). Host Kotlin apps call `DoubleEntry.account/transfer/balance/lockAccounts` equivalents.

### D-002 Database support (round 1, Q2)
- **Answer:** **PostgreSQL only.** MySQL and SQLite code paths are dropped.
- **Consequences:** deviation **X-01**: no `USE INDEX` / `FORCE INDEX` hints (BR-047, BR-094) and no SQLite "database is locked" handling. Error classification becomes SQLSTATE-based (D-008). Minimum PG version is 15 because of D-005 (`NULLS NOT DISTINCT`). Tested with Testcontainers PostgreSQL 17.

### D-003 Data compatibility (round 1, Q3)
- **Answer:** **In place.** Identical schema: the Flyway baseline equals the generator migration, with `baselineOnMigrate` for existing DBs. Value encodings are identical. Ruby-written and Kotlin-written rows are interchangeable.
- **Consequences:** 50-data.md is a hard contract. Account/partner_account/code are stored as the configured identifier strings, scope as the scope-identity string or NULL, amounts and balances as bigint minor units, timestamps as UTC `timestamp(6) without time zone`, metadata as jsonb with string keys, and detail as (`detail_type` = host class-name string, `detail_id`). The running balance continues from the latest line by id (BR-040).

### D-004 Metadata storage (round 1, Q4)
- **Answer:** **jsonb column only.** The legacy `double_entry_line_metadata` table mode (`json_metadata = false`) is out of scope.
- **Consequences:** deviation **X-02**. Kotlin always writes `metadata` jsonb on both lines (BR-028) and never creates or reads `double_entry_line_metadata`. The `json_metadata` config flag and the generator option `--no-json-metadata` are not ported. Existing installations that use the table mode must convert first (U-004 records the open question of whether any exist).

### D-005 NULL-scope duplicate balance rows (round 1, Q5)
- **Answer:** **Fix.** Uniqueness on `(scope, account)` must also hold for NULL scope: PG 15+ `UNIQUE NULLS NOT DISTINCT`. A Flyway migration first deletes duplicate rows, keeping the lowest id (the row that holds the balance, BR-070). Add a concurrency test.
- **Consequences:** deviation **X-03**. Verified compatible with the Ruby gem (mixed writers, D-017): `oracle/api/json/compat_nulls_not_distinct_index.json` shows 8 threads, 2 rows, and 14 `duplicate_ignore.double_entry` events.

### D-006 Money type (round 1, Q6) **→ CF**
- **Answer:** **Own value type** `Money(minorUnits: Long, currency: CurrencyUnit)`. The currency registry is ported from the money gem 7.1.1 table (ISO plus BTC etc.). Rounding is HALF_EVEN when a fractional minor amount is constructed. Arithmetic is allowed only within one currency.
- **Consequences:** `Money#to_s` formatting (decimal mark `.`, no thousands separator, `subunit_to_unit` decimals, e.g. `-0.00010000` BTC, `0` JPY) must be reproduced where it appears in messages (BR-095, BR-012).

### D-007 Outermost transaction rule (round 2, Q1)
- **Answer:** **Keep strict.** Throw `LockMustBeOutermostTransaction` when a Spring-managed or JDBC transaction is already active. Provide the test-only switch that tolerates exactly one enclosing transaction (`running_inside_transactional_fixtures`).
- **Consequences:** BR-060 unchanged. The Kotlin implementation must check `TransactionSynchronizationManager.isActualTransactionActive()` (or connection-level nesting) before starting its own transaction.

### D-008 Error classification on PostgreSQL (round 2, Q2) **→ CF** (general part)
- **Answer:** **Map by SQLSTATE.** `55P03` → `LockWaitTimeoutException`; `40P01` → restart the whole lock transaction (unlimited, as Ruby); `23505` while creating a balance row → ignore.
- **Consequences:** deviation **X-04**. On PG, Ruby raises `ActiveRecord::LockWaitTimeout` (`oracle/api/json/lock_wait_timeout_pg.json`); Kotlin raises the library's `LockWaitTimeout`.

### D-009 `credits` / `debits` naming (round 2, Q3)
- **Answer:** **Preserve the semantics exactly**: `credits()` = amount > 0 and `debits()` = amount < 0. Document the inversion.
- **Consequences:** BR-081 as observed (`oracle/api/json/line_scopes.json`).

### D-010 Return value of `lock_accounts` (round 2, Q4)
- **Answer:** **Return the block's value at every level.**
- **Consequences:** deviation **X-05**. Ruby returns nil at the outermost level (`oracle/api/json/lock_returns_block_value.json`).

### D-011 LineCheck log format (round 2, Q5)
- **Answer:** **Header lines byte-identical.** Record dumps are rendered in a documented Kotlin format with the same fields.
- **Consequences:** deviation **X-06** (record dumps only). Exact formats are in BR-095.

### D-012 Instrumentation (round 2, Q6) **→ CF**
- **Answer:** **Spring ApplicationEvents** with the same names and payload, the same SLF4J INFO/DEBUG messages, and a Micrometer counter per event.
- **Consequences:** BR-072 and 60-async-and-integrations.md §3. The Micrometer counters are an addition (**X-10**).

### D-013 Invalid input (round 3, Q1) **→ CF**
- **Answer:** **Validate up front, before any DB write.** Typed non-null parameters make Integer or nil amounts and missing accounts impossible. An amount currency different from the account currency raises `MismatchedCurrencies` with nothing written. Library exceptions stay as in Ruby.
- **Consequences:** deviation **X-07**. Ruby raises `Money::Bank::UnknownRate` / `TypeError` after committing balance rows (BR-033).

### D-014 Scope identity type (round 3, Q2)
- **Answer:** **Always a String.** A resolver returning null gives `""`; unscoped accounts have a null scope.
- **Consequences:** deviation **X-08**. Ruby keeps an Integer passed via `scope_identity:` (BR-010).

### D-015 Polymorphic `detail` (round 3, Q3)
- **Answer:** **Value object `DetailRef(type: String, id: Long)`** supplied by the host. The library never loads the target. Hosts keep the Ruby class-name strings for existing types.
- **Consequences:** BR-027. `Line.detail` is not a JPA association.

### D-016 Configuration lifecycle (round 3, Q4)
- **Answer:** **Immutable configuration built at startup**, with the same validation rules and errors. Tests use their own instances.
- **Consequences:** deviation **X-09**: runtime redefinition or replacement of account/transfer sets is impossible. BR-001..008 apply at build time.

### D-017 Mixed writers during cut-over (round 3, Q5)
- **Answer:** **Assume Ruby and Kotlin may write concurrently.** Kotlin implements the identical locking and row protocol (BR-060..071), and a mixed Ruby+Kotlin concurrency test must pass.
- **Consequences:** lock order, `SELECT … FOR UPDATE … ORDER BY id LIMIT 1`, cached-balance update in the same transaction, partner-id write sequence and balance-row creation from lines are all **protocol**, not implementation detail.

## Deviation register — where the Kotlin expectation differs from the Ruby oracle

Every row needs a Kotlin test. Oracle files for these cases show **Ruby** behaviour and must not be used as the Kotlin expectation.

| # | Decision | Area | Ruby (oracle) | Kotlin expectation |
|---|---|---|---|---|
| X-01 | D-002 | DB support | MySQL `USE/FORCE INDEX`, SQLite lock text | PG only, no hints |
| X-02 | D-004 | metadata | `json_metadata=false` writes `double_entry_line_metadata` rows (`oracle/api/table/*`) | jsonb only; table mode absent |
| X-03 | D-005 | `double_entry_account_balances` unique index | NULL-scope duplicates possible (`lock_concurrent_threads_same_accounts.json`: 16 rows) | `UNIQUE NULLS NOT DISTINCT (scope, account)`; dedupe migration; exactly one row per account |
| X-04 | D-008 | PG lock timeout | `ActiveRecord::LockWaitTimeout` (`lock_wait_timeout_pg.json`) | library `LockWaitTimeout` |
| X-05 | D-010 | `lock_accounts` return | nil at outermost level | block value |
| X-06 | D-011 | LineCheck log record dumps | Ruby `inspect` (`#<DoubleEntry::Line id: 1, …>`) | documented Kotlin rendering; header lines identical |
| X-07 | D-013 | amount currency ≠ account currency; non-Money amount | `Money::Bank::UnknownRate` / `TypeError` after balance rows committed | `MismatchedCurrencies` before any write; non-Money impossible |
| X-08 | D-014 | `scope_identity: 42` | Integer kept, ≠ "42" | always `"42"` |
| X-09 | D-016 | config | mutable at runtime | immutable after startup |
| X-10 | D-012 | instrumentation | AS::Notifications + log lines | ApplicationEvents + same logs + Micrometer counters (addition) |
