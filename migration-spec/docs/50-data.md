# 50 — Data

Ground truth: the schema a host app gets from `rails g double_entry:install` (default `--json-metadata`), applied to PostgreSQL 17.11 and dumped:
`inventory/schema_generated_json_metadata_true.rb`, `inventory/structure_generated_json_metadata_true.sql`. Legacy variant (`--no-json-metadata`):
`inventory/*_json_metadata_false.*`. Test-suite variant: `inventory/schema_spec_support.rb` (from `spec/support/schema.rb`). Generated migration files:
`inventory/generated/json_metadata_{true,false}/db/migrate/*.rb`. Per D-003 this schema is a **hard compatibility contract**. Kotlin ships it as the
Flyway baseline, plus migration X-03 (D-005).

There are **no foreign keys, no check constraints, no triggers, no views and no DB defaults** other than id sequences (structure dump). Every relation (partner_id,
last_line_id, line_id, detail) is enforced only in application code. **verified** (structure dump).

## 1. `double_entry_lines` {#double_entry_lines}

| Column | Type | Null | Default | Meaning / written by | Evidence |
|---|---|---|---|---|---|
| id | bigint (sequence) | NOT NULL, PK | nextval | creation order = running-balance order (BR-026) | structure dump |
| account | varchar | NOT NULL | — | account identifier string | `line.rb:100` |
| scope | varchar | NULL | — | scope identity; NULL = unscoped | `line.rb:101` |
| code | varchar | NOT NULL | — | transfer code string | `line.rb:91` |
| amount | bigint | NOT NULL | — | signed minor units (source < 0, destination > 0) | `transfer.rb:107` |
| balance | bigint | NOT NULL | — | running balance after this line | `transfer.rb:111` |
| partner_id | bigint | NULL | — | id of the other line of the transfer; NULL only briefly inside the transfer transaction (BR-025) | `transfer.rb:117-119` |
| partner_account | varchar | NOT NULL | — | other side's identifier | `line.rb:115` |
| partner_scope | varchar | NULL | — | other side's scope | `line.rb:116` |
| detail_type | varchar | NULL | — | polymorphic host class name (e.g. `User`) | `line.rb:58`; `transfer_with_detail.json` |
| detail_id | bigint | NULL | — | host record id | same |
| metadata | jsonb (json mode only; `json` on non-PG) | NULL | — | transfer metadata, string keys (BR-028) | migration template `:23-29` |
| created_at / updated_at | timestamp(6) without time zone | NOT NULL | — | UTC (BR-035) | migration template `:30` |

Indexes (generator):

| Name | Columns | Used for (`line.rb:12-56`) |
|---|---|---|
| lines_scope_account_id_idx | (scope, account, id) | current balance: latest line by id (BR-040) |
| lines_scope_account_created_at_idx | (scope, account, created_at) | historic and period balances (BR-042/043) |
| lines_account_created_at_idx | (account, created_at) | reporting by account and period |
| lines_account_code_created_at_idx | (account, code, created_at) | code-filtered sums (BR-045) |

The test schema names the code index differently: `lines_account_code_created_at_partner_account_idx (account, code, created_at, partner_account)` (`spec/support/schema.rb:34`).
The generator's index is the contract (U-005). Repo insight: "indexes are carefully tuned … don't casually add/remove" (`repo_insights`).

## 2. `double_entry_account_balances` {#double_entry_account_balances}

| Column | Type | Null | Meaning |
|---|---|---|---|
| id | bigint PK | NOT NULL | lowest id per account = the lock and cache row (BR-061, BR-070) |
| account | varchar | NOT NULL | identifier |
| scope | varchar | NULL | scope identity |
| balance | bigint | NOT NULL | cached current balance (minor units) |
| created_at / updated_at | timestamp(6) | NOT NULL | `updated_at` is bumped by every transfer |

Indexes: `index_account_balances_on_account (account)`, and `index_account_balances_on_scope_and_account (scope, account) UNIQUE`. The unique index does not cover NULL scope:
duplicates are possible (**verified**, BR-070). **Kotlin (D-005, X-03):** Flyway `V2` deletes duplicates (`DELETE … WHERE id NOT IN (SELECT min(id) … GROUP BY account, scope)`;
the extra rows always hold 0 per `lock_concurrent_threads_same_accounts.json`, but the migration must keep the lowest id regardless) and recreates the index as
`UNIQUE NULLS NOT DISTINCT (scope, account)`. Compatibility with Ruby writers is **verified** (`compat_nulls_not_distinct_index.json`).

## 3. `double_entry_line_checks` {#double_entry_line_checks}

| Column | Type | Null | Meaning |
|---|---|---|---|
| id | bigint PK | NOT NULL | |
| last_line_id | bigint | NOT NULL | last line id visited by that run (BR-094) |
| errors_found | boolean | NOT NULL | any incorrect account |
| log | text | NULL | BR-095 texts concatenated (`''` when there are no errors) |
| created_at / updated_at | timestamp(6) | NOT NULL | `created_at` orders the runs (BR-090) |

Index: `line_checks_created_at_last_line_id_idx (created_at, last_line_id)`.

## 4. `double_entry_line_metadata` (legacy, `--no-json-metadata` only; out of scope per D-004) {#double_entry_line_metadata}

Columns `id`, `line_id bigint NOT NULL`, `key varchar NOT NULL`, `value varchar NOT NULL`, timestamps. Index `lines_meta_line_id_key_value_idx (line_id, key, value)`.
In legacy mode the `metadata` column is absent from `double_entry_lines`. **verified** `inventory/structure_generated_json_metadata_false.sql`, `oracle/api/table/transfer_with_metadata.json`.
The test schema has **both** the jsonb column and this table (`spec/support/schema.rb:24-30,48-55`).

## 5. Test-only tables (not part of the contract) {#test_only}

`users (id, username varchar NOT NULL UNIQUE, timestamps)` from `spec/support/schema.rb:58-63`, and the Rails bookkeeping tables `schema_migrations` / `ar_internal_metadata`,
which exist only in the host app.

## 6. Migrations, data changes and seeds {#migrations}

- The gem ships exactly **one** migration template (`lib/generators/double_entry/install/templates/migration.rb`, `up`/`down`). There are no data migrations and no seeds.
  `down` drops `line_metadata` if it exists, then `line_checks`, `lines` and `account_balances`.
- The migration's `migration_version` is `[<AR major>.<minor>]` (for AR > 5) (`install_generator.rb:35-39`). Hosts generated with older Rails versions may have
  `timestamp` without explicit precision (PG default precision 6, which is equivalent). Older 1.x installs used the line_metadata table (README `:56`), see U-004.
- Kotlin Flyway plan (D-003/D-005): `V1__double_entry_baseline.sql` = `inventory/structure_generated_json_metadata_true.sql` (tables, sequences, indexes);
  `baselineOnMigrate=true` with `baselineVersion=1` for existing DBs; `V2__account_balances_unique_nulls_not_distinct.sql` (dedupe + index). Table names are fixed (`double_entry_` prefix).

## 7. Value encodings (D-003 contract) {#encodings}

| Value | Encoding | Evidence |
|---|---|---|
| account / partner_account | configured identifier, `Symbol#to_s` | `transfer_scoped_basic.json` (`"bank"`, `"checking"`) |
| scope / partner_scope | scope identity string (`"1"`), `""` if the lambda returns nil, NULL if unscoped | `account_scope_lambda_returns_nil.json` |
| code | `Symbol#to_s` | `transfer_scoped_basic.json` |
| amount / balance | integer minor units of the **account's** currency (USD cents, BTC satoshi ×1e-8, JPY yen) | `transfer_btc.json`, `transfer_jpy.json` |
| metadata | jsonb object, string keys | `transfer_with_metadata.json` |
| detail_type | host class name string | `transfer_with_detail.json` |
| timestamps | UTC wall-clock, microseconds | BR-035 |
