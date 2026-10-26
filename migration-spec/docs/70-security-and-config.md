# 70 — Security and configuration

## 1. Authentication and authorisation {#auth}
**None.** The gem is an in-process library: every caller of the Ruby API is trusted. There is no auth code, no roles and no policies (`inventory/components.json` → `policies: []`;
`inventory/api.json` has no auth-related methods). Account **scope** (e.g. a user id) only partitions balances. Isolating tenants is the host's job: it decides
which scope it passes (`README.md:83-88`). So the inherited tenant-isolation rule (ID-2) has nothing to act on here.

Integrity controls the library *does* provide, which are protection against host bugs rather than security:
- the transfer allowlist (BR-020) and positive/negative-only accounts (BR-030);
- pessimistic row locks and the outermost-transaction rule (BR-060..071);
- consistency checking and repair (BR-090..096).

## 2. CSRF, CORS, rate limits, sessions {#web}
Not applicable: no HTTP surface (30-http-api.md §1).

## 3. SQL safety {#sql}
All queries use AR bind parameters, except `Line.find_id_and_created_at`, which interpolates `options[:joins]` verbatim and sanitises `options[:conditions]` with `sanitize_sql_for_conditions`
(`line.rb:147-152`). A host that passes untrusted input as `joins` would create SQL injection (**inferred**; the gem never calls this method itself). Kotlin: if ported, accept only a typed
criteria object and no raw join SQL (U-007).

## 4. Secrets {#secrets}
None. The gem reads no credentials. The DB connection belongs to the host app (`ActiveRecord::Base`). The spec DB config (`spec/support/database*.yml`) uses local passwordless or `password` CI users.

## 5. Library configuration (complete, `inventory/config.json`) {#config}

| Setting | Default | Effect | Kotlin (`@ConfigurationProperties` prefix `double-entry` + DSL) |
|---|---|---|---|
| `DoubleEntry.config.define_accounts` | empty set | account definitions (BR-001) | DSL / bean, immutable (D-016) |
| `DoubleEntry.config.define_transfers` | empty set | transfer allowlist (BR-003) | DSL / bean |
| `DoubleEntry.config.json_metadata` | `false` (generated initializer: `true`) | metadata storage mode (BR-028/029) | removed: always jsonb (D-004) |
| `account_identifier_max_length` | nil | BR-005 | `double-entry.account-identifier-max-length` |
| `scope_identifier_max_length` | nil | BR-007 | `double-entry.scope-identifier-max-length` |
| `code_max_length` | nil | BR-006 | `double-entry.code-max-length` |
| `DoubleEntry::Locking.configuration.running_inside_transactional_fixtures` | false | tolerate one enclosing transaction (tests) (BR-060) | test-only switch (D-007) |
| `Money.default_currency` (money gem, host-set) | money gem default (USD in specs, `spec/support/money.rb:2`) | currency of accounts without `currency:` (BR-001) | `double-entry.default-currency` |
| `Money.rounding_mode` (money gem) | HALF_EVEN | rounding on Money construction (BR-032) | fixed HALF_EVEN (D-006) |
| `ActiveRecord::Base.logger` | host | destination of BR-072 log lines | SLF4J |
| generator `--[no-]json-metadata` | true | BR-100 | n/a |

## 6. Environment variables (complete: `inventory/env.txt`) {#env}

**The library code (`lib/`) reads no environment variables.** All variables below belong to the test and dev tooling. Kotlin needs no equivalents beyond the
Testcontainers settings in `CLAUDE.md`.

| Variable | Default | Where | Purpose | Kotlin |
|---|---|---|---|---|
| `DB` {#env-DB} | `mysql` | `spec/support/database.rb:8`, `script/jack_hammer:24`, `Rakefile:9`, `spec/double_entry/locking_spec.rb:181`, CI | picks the database.yml section; `sqlite` skips concurrency specs and jack_hammer | n/a (PG only, D-002) |
| `POSTGRES_HOST` {#env-POSTGRES_HOST} | `localhost` | `spec/support/database.example.yml:2`, `docker-compose.yml:6` | PG host for specs | Testcontainers |
| `MYSQL_HOST` {#env-MYSQL_HOST} | `127.0.0.1` | `spec/support/database.example.yml:11`, `docker-compose.yml:7` | MySQL host for specs | n/a |
| `CI` {#env-CI} | unset | `spec/support/performance_helper.rb:12` | skip writing ruby-prof reports | n/a |
| `BUNDLE_GEMFILE` {#env-BUNDLE_GEMFILE} | `./Gemfile` | `.github/workflows/ci-workflow.yml:15` | CI picks the Rails-version gemfile | n/a (Gradle version catalog) |

## 7. Supply chain / build {#build}
The gem is built and released with the bundler gem tasks (`inventory/rake_tasks.txt`, `Rakefile:2`): `build`, `build:checksum`, `install`, `install:local`, `release[remote]` (with `release:guard_clean`, `release:source_control_push`, `release:rubygem_push`) and `clean`/`clobber`. Test tasks `spec` and `default` (`default` runs `spec` for mysql, postgres and sqlite, `Rakefile:6-12`) are in 80-tests.md, and `allowed_push_host` is rubygems.org (`double_entry.gemspec:17`). Kotlin:
Gradle `maven-publish` to the organisation's repository (U-010).
