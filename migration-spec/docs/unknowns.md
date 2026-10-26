# Unknowns — double_entry

Status after Phase 4 (3 question rounds, 17 decisions, see decisions.md). **Open** = still unresolved; **resolved** = closed by a decision or by evidence.
"Who/what can resolve" names the party able to answer.

## A. What the investigation could not exercise (Phase 2)

**U-001 No HTTP server, no UI → no `oracle/http/` and no `oracle/ui/`** — *resolved (adapted)*
- Unclear: the procedure asks for a Rails server on :3000, route samples and Playwright screenshots.
- Evidence: the gem has no routes, app, views or JS (`inventory/routes.txt`, `inventory/components.json`, 30-http-api.md §1, 40-ui.md). There is no `bin/rails` and no README instruction to run a server.
- Done instead: `oracle.sh` runs `tools/oracle.rb`, which makes in-process API calls against PG 17 in both metadata modes (121 scenarios each, `oracle/index.json`), plus `script/jack_hammer`.
  `db:prepare` was replaced by applying the install generator's migration (`inventory/generated/**`). D-001 confirms that the in-process API is the parity contract.

**U-002 MySQL behaviour not observed** — *resolved by D-002 (out of scope)*
- `mysql2` cannot be compiled in the sandbox (no libmysqlclient). It was excluded from the investigator Gemfile (`inventory/gems.txt` header). MySQL-only paths (BR-047, BR-094 hint, lock-wait-timeout text) are **inferred**.
  D-002 makes Kotlin PG-only.

**U-003 Other Rails/Ruby versions not run** — *open, low risk*
- Only Ruby 4.0.5 + AR 8.1.4 were run (CI matrix: Ruby 3.2–4.0 × Rails 7.2/8.0/8.1). Version-sensitive spot: `return correct` inside a `lock_accounts` block in `line_check.rb:119`.
  Rails 7.0 rolled back transactions exited with `return`, while 7.1+ commits. The block is read-only, so the outcome is the same (inferred). Hosts on older Rails versions are not in the CI matrix.
- Resolve: host-app owners state their Rails versions (only relevant if a host runs Rails < 7.2 against the same DB during cut-over, D-017).

**U-016 Not exercisable at runtime** — *open (accepted)*
- Real deadlock restart (BR-067): unreachable through the public API because locks are always taken in the same order. Verified only by stubbed specs.
- `LockDisaster` (BR-062): needs a balance row deleted between creation and relock. Not reproduced.
- Railtie `after_initialize` (BR-101) and the generator inside a real Rails app: the generator was run standalone (`inventory/generated/**`, specs GN pass). No host Rails app was booted.
- Resolve: Kotlin tests can force these with fault injection (Kotlin team).

## B. Questions for owners / Kotlin team (not answerable from code)

**U-004 Do existing installations use legacy metadata mode (`double_entry_line_metadata`)?** — *open*
- Why: D-004 drops the table mode. A host on 1.x-style config would lose metadata reads after the switch.
- Evidence for "jsonb": README default since 2.0 (`README.md:56`), generator default `true`. Evidence for "legacy": the mode still exists (`json_metadata` default `false` in `configuration.rb:9`),
  and the spec schema has both.
- Resolve: host-app owners / DBAs: check whether `double_entry_line_metadata` exists and is non-empty in each DB. If so, a conversion into the jsonb column is needed (D-004 option C).

**U-005 Which code index do production DBs have?** — *open*
- Generator: `lines_account_code_created_at_idx (account, code, created_at)`. Spec schema: `lines_account_code_created_at_partner_account_idx (…, partner_account)` (`spec/support/schema.rb:34`).
  Older gem versions may have generated other indexes.
- Resolve: DBAs list the indexes of `double_entry_lines` per DB. The Flyway baseline must not fail on existing DBs (`baselineOnMigrate`), but index names matter if a later migration touches them.

**U-006 Which host apps use the gem, and which API parts?** — *open*
- Rarely used or helper APIs: `Line.find_id_and_created_at` (unused internally), `scopes_with_minimum_balance_for_account`, `Account.currency`, `Line#pair`, the legacy `LineMetadata` model.
- Resolve: host-app owners grep their code. Anything unused could be dropped by a later decision (the parity policy currently keeps everything).

**U-007 `Line.find_id_and_created_at` takes raw SQL `joins`** — *open (Kotlin team)*
- `line.rb:147-152` interpolates `options[:joins]` (an injection risk if hosts pass input, 70-security-and-config.md §3). Port it with a typed criteria API or drop it (depends on U-006).

**U-008 Clock injection** — *open (Kotlin team), recommendation: inject `java.time.Clock`*
- Ruby uses wall-clock `Time.now` via AR (specs use Timecop). BR-035/042/043 depend on `created_at`. A `Clock` keeps tests deterministic and matches the oracle's frozen-time scenarios.

**U-009 Equivalent of `ActiveRecord::Rollback` inside `lock_accounts`** — *open (Kotlin team)*
- BR-065: Ruby offers a silent rollback that returns nil. Kotlin needs an API, e.g. `TransactionStatus.setRollbackOnly()` exposed to the block or a library `RollbackSignal`. The result for D-010 (block value) after a rollback must be defined.

**U-010 Publication of the Kotlin artefact** — *open (platform team)*
- The Ruby gem is pushed to rubygems.org (`double_entry.gemspec:17`). Kotlin: Maven coordinates and repository (GitHub Packages / internal Nexus), and versioning (keep 2.x?).

**U-011 Fork vs upstream** — *open (owners)*
- The source is `softberries/double_entry`, but the gemspec homepage, CHANGELOG links and all recent commits point to `envato/double_entry` (`double_entry.gemspec:14`; top authors in `git log`: upstream maintainers).
  Are there fork-only changes, and must future upstream fixes be tracked until cut-over?

**U-013 LineCheck may skip lines that commit out of id order** — *open (behaviour preserved, inferred risk)*
- `perform!` checks `id > last_line_id_checked`. A transfer that takes its id before, but commits after, a LineCheck run that saw a higher id is never checked (`line_check.rb:46-48`). This is an inferred race, not observed.
  The parity policy keeps it. A fix (e.g. a safety lag) needs a decision. Resolve: owners decide whether to add it post-migration.

**U-014 Host AR time zone** — *open (host owners)*
- The oracle assumes `ActiveRecord.default_timezone = :utc` (the Rails default). A host configured with `:local` would have stored local timestamps, and Kotlin (UTC, BR-035) would then mis-place historic balances.
  Resolve: host owners confirm `default_timezone`.

## C. Resolved during the investigation

| Unknown | Resolution |
|---|---|
| U-012 `has_many :metadata … unless -> {}` never defines the association (`line.rb:59`) | Verified (`inventory/models.json`, `oracle/api/table/line_metadata_reader.json`). Moot for Kotlin: jsonb only (D-004) |
| U-017 NULL-scope duplicate balance rows | Verified (BR-070) → fix (D-005); Ruby compatibility of the fix verified (`compat_nulls_not_distinct_index.json`) |
| U-018 PG lock timeout not mapped | Verified (BR-068) → SQLSTATE mapping (D-008) |
| U-019 credits/debits inversion | Verified (BR-081) → preserve (D-009) |
| U-020 lock_accounts returns nil | Verified (BR-066) → return the block value (D-010) |
| U-021 Integer scope identity | Verified (BR-010) → String (D-014) |
| U-022 Non-Money / foreign-currency amounts | Verified (BR-024/033) → up-front validation (D-013) |
| U-023 Outermost transaction vs Spring `@Transactional` | → keep strict (D-007) |
| U-024 Deliverable form, DB support, data compatibility, money type, LineCheck log, instrumentation, detail, config lifecycle, mixed writers | D-001, D-002, D-003, D-006, D-011, D-012, D-015, D-016, D-017 |

## D. Post-migration hardening backlog (preserved now, candidates later)
U-013 (LineCheck lag); BR-031 (balance rows left by failed transfers, harmless); BR-050 ignoring the currency of `minimum_balance`; BR-051 (transfer arithmetic trusts the cache);
unlimited deadlock retries (BR-067/069).
