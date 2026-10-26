# 60 — Async processing and integrations

## 1. Jobs, schedules, retries {#jobs}
**None.** `inventory/components.json` → `jobs: []` (no `*_job.rb`, `jobs/`, Sidekiq, whenever or schedule files among the tracked files). There is no ActiveJob usage (no `ActiveJob` in `lib/`).
The only periodic activity is the **recommendation** that host apps run `DoubleEntry::Validation::LineCheck.perform!` (optionally with
`AccountFixer`) "on the order of hourly to daily" from their own scheduler (`README.md:172-182`). That is BR-090..096. Kotlin: expose `LineCheckService.perform(fixer)`
as a bean. Scheduling stays with the host (for example a host `@Scheduled` method).

Retries that do exist are **synchronous, in-process**: restarting the lock transaction on deadlock (BR-067) and retrying balance-row creation on deadlock (BR-069), both without limit.

## 2. Mailers, channels, webhooks, external APIs {#mailers}
**None.** `mailers: []`, `channels: []` (`inventory/components.json`). There are no HTTP clients and no ActionCable (gem deps: `double_entry.gemspec:30-33`).

## 3. Instrumentation (the only integration point) {#notifications}

| Event (ActiveSupport::Notifications) | When | Payload | Log line (LogSubscriber, AR logger) | Evidence |
|---|---|---|---|---|
| `deadlock_restart.double_entry` | deadlock while grabbing locks → whole lock transaction restarted | `exception:` | INFO `Deadlock causing restart`, DEBUG exception | `locking_extensions.rb:21`, `log_subscriber.rb:7-10`; specs LE [1:2:*:1:2] |
| `deadlock_retry.double_entry` | deadlock while creating a balance row → creation retried | `exception:` | INFO `Deadlock causing retry` | `locking_extensions.rb:66`, `log_subscriber.rb:12-15`; specs LE [1:3:*:1:2] |
| `duplicate_ignore.double_entry` | unique violation while creating a balance row → ignored | `exception:` | INFO `Duplicate ignored` | `locking_extensions.rb:49`, `log_subscriber.rb:17-20`; spec LE [1:3:2]; **verified** `oracle/api/json/lock_create_ignoring_duplicates_scoped.json`, `compat_nulls_not_distinct_index.json` (14 events) |

Kotlin (D-012): publish `ApplicationEvent`s with the same names and exception payload, log the same INFO/DEBUG messages via SLF4J (logger e.g. `double_entry`), and add a Micrometer counter
`double_entry.<event>` (X-10).

## 4. Redis / cache {#redis}
**Not used.** No Redis client, `Rails.cache` or cache store in `lib/` (`inventory/gems.txt` has no redis gem). The only "cache" is the DB table `double_entry_account_balances`
(50-data.md §2). The Redis 7 container was started only because the investigation procedure requires it. It is not needed by the gem or by the Kotlin library.
