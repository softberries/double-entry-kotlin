# Host guide: from the `double_entry` gem to `double-entry` for Spring Boot

This guide is for teams that move a host application from the Ruby gem `double_entry` 2.0.2 to the Kotlin library
`double-entry`. It covers installation, configuration, transactions, scheduling, the deviations from the gem, and the
checks to run before cut-over. It replaces the gem's README sections "Installation", "Interface" and "Configuration"
(`README.md:40-251` of the gem).

The library runs in-process, like the gem. It has no HTTP endpoints, no UI and no jobs of its own, and it supports
PostgreSQL only.

Contents:

1. [Gradle dependency](#1-gradle-dependency)
2. [Accounts and transfers: the Ruby initializer in Kotlin](#2-accounts-and-transfers-the-ruby-initializer-in-kotlin)
3. [`double-entry.*` properties](#3-double-entry-properties)
4. [Database schema and Flyway coexistence](#4-database-schema-and-flyway-coexistence)
5. [Transactions: the outermost-transaction rule vs `@Transactional`](#5-transactions-the-outermost-transaction-rule-vs-transactional)
6. [Scheduling `LineCheckService` with `@Scheduled`](#6-scheduling-linecheckservice-with-scheduled)
7. [Deviations from the gem (X-01 … X-11)](#7-deviations-from-the-gem-x-01--x-11)
8. [Cut-over checklist](#8-cut-over-checklist)
9. [Releasing the library](#9-releasing-the-library)

---

## 1. Gradle dependency

| Gem | Kotlin |
|---|---|
| `gem 'double_entry'` in the `Gemfile`, then `bundle` | the Maven artefact `com.softberries:double-entry` |
| pushed to rubygems.org | published to GitHub Packages of `softberries/double-entry-kotlin` |
| version 2.0.2 | version line 3.x (major bump: new platform) |

The coordinates follow the proposal for U-010, which is still open. If the platform team picks another group,
repository or version line, only `group`/`version` in `gradle.properties` and the repository in
`double-entry/build.gradle.kts` change.

GitHub Packages needs credentials even for reads: a GitHub user and a token with `read:packages`. In GitHub Actions,
`GITHUB_TOKEN` is enough for repositories of the same owner.

```kotlin
// settings.gradle.kts of the host (a host without central repository management puts the inner
// `repositories { … }` block into build.gradle.kts instead)
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            name = "doubleEntry"
            url = uri("https://maven.pkg.github.com/softberries/double-entry-kotlin")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).get()
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).get()
            }
        }
    }
}

// build.gradle.kts of the host
dependencies {
    implementation("com.softberries:double-entry:3.0.0")
    runtimeOnly("org.postgresql:postgresql")
}
```

What the dependency brings:

- `spring-boot-starter-data-jpa`, Micrometer and SLF4J as API dependencies, and Flyway (core plus the PostgreSQL
  module) at runtime. The Spring Boot BOM the library was built with (3.5.x) is imported in the POM.
- A Spring Boot auto-configuration (`DoubleEntryAutoConfiguration`, listed in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`). It replaces the gem's Railtie.
  No `@ComponentScan` of the library packages is needed. The auto-configuration registers the library's JPA entities
  and Spring Data repositories by adding `com.softberries.doubleentry` to the auto-configuration packages. A host that
  declares its own `@EntityScan` or `@EnableJpaRepositories` replaces that scan and must list
  `com.softberries.doubleentry` there as well, or startup fails.
- Configuration metadata for the `double-entry.*` properties (`META-INF/spring-configuration-metadata.json`), so IDEs
  complete them in `application.yml`.
- The Flyway migrations in `classpath:db/double_entry/migration`. They replace `rails generate double_entry:install`
  and `rake db:migrate` (section 4).

Requirements: JDK 21; Spring Boot 3.2 or later (Spring Framework 6.1 transaction listeners are used; the library is
built and tested with Boot 3.5.x); Kotlin 2.1 or later if the host is written in Kotlin (the library is compiled with
Kotlin 2.2); PostgreSQL 15 or later.

## 2. Accounts and transfers: the Ruby initializer in Kotlin

The gem reads `config/initializers/double_entry.rb`. In Kotlin the same definitions become a
`DoubleEntryConfiguration` bean built with `doubleEntry { accounts { … }; transfers { … } }`. Pass the bound
`DoubleEntryProperties` so the `double-entry.*` settings (section 3) apply.

The gem's README example (`README.md:203-251`), next to its Kotlin equivalent:

<table>
<tr><th>Ruby: <code>config/initializers/double_entry.rb</code></th><th>Kotlin: a <code>@Configuration</code> class</th></tr>
<tr><td>

```ruby
require 'double_entry'

DoubleEntry.configure do |config|
  # Use json(b) column in double_entry_lines table
  # to store metadata instead of separate metadata table
  config.json_metadata = true

  config.define_accounts do |accounts|
    user_scope = ->(user) do
      raise 'not a User' unless user.class.name == 'User'
      user.id
    end
    accounts.define(identifier: :savings,
                    scope_identifier: user_scope,
                    positive_only: true)
    accounts.define(identifier: :checking,
                    scope_identifier: user_scope)
  end

  config.define_transfers do |transfers|
    transfers.define(from: :checking, to: :savings,
                     code: :deposit)
    transfers.define(from: :savings, to: :checking,
                     code: :withdraw)
  end
end
```

</td><td>

```kotlin
@Configuration
class LedgerConfiguration {
    // jsonb metadata is the only mode (X-02):
    // there is no json_metadata switch.

    @Bean
    fun doubleEntryConfiguration(
        properties: DoubleEntryProperties,
    ) = doubleEntry(properties) {
        val userScope: ScopeIdentifier = { user ->
            require(user is User) { "not a User" }
            user.id
        }
        accounts {
            define("savings",
                scopeIdentifier = userScope,
                positiveOnly = true)
            define("checking",
                scopeIdentifier = userScope)
        }
        transfers {
            define(from = "checking", to = "savings",
                code = "deposit")
            define(from = "savings", to = "checking",
                code = "withdraw")
        }
    }
}
```

</td></tr>
<tr><td>

```ruby
DoubleEntry.configure do |config|
  config.define_accounts do |accounts|
    accounts.define(identifier: :savings,
                    scope_identifier: user_scope,
                    currency: 'AUD')
  end
end
```

</td><td>

```kotlin
doubleEntry(properties) {
    accounts {
        define("savings",
            scopeIdentifier = userScope,
            currency = "AUD")
    }
}
```

</td></tr>
</table>

Mapping of the remaining initializer and API constructs:

| Ruby | Kotlin |
|---|---|
| `identifier: :savings` (Symbol or String) | `"savings"` (always a String, X-11) |
| `scope_identifier: ->(user) { user.id }` | `scopeIdentifier = { (it as User).id }` (type `ScopeIdentifier = (Any) -> Any?`). The result is stored with `toString()`; `null` becomes `""` (X-08) |
| `positive_only: true` / `negative_only: true` | `positiveOnly = true` / `negativeOnly = true` |
| `currency: 'AUD'`; default `Money.default_currency` | `currency = "AUD"`; default `double-entry.default-currency` (USD) |
| `config.json_metadata = …` | not ported (X-02) |
| `DoubleEntry.config.account_identifier_max_length = 31` (and `scope_identifier_max_length`, `code_max_length`) | the property `double-entry.account-identifier-max-length=31` (section 3), or inside the block: `accountIdentifierMaxLength = 31` |
| `DoubleEntry.account(:savings, scope: user)` | `doubleEntry.account("savings", user)` on the injected `DoubleEntry` bean |
| `account.balance` / `DoubleEntry.balance(:savings, scope: user, at: t)` | `doubleEntry.balance(account)` / `doubleEntry.balance("savings", scope = user, options = BalanceOptions(at = t))` |
| `DoubleEntry.transfer(Money.new(20_00), from: a, to: b, code: :deposit, metadata: {key1: ['v1', 'v2']}, detail: order)` | `doubleEntry.transfer(Money.ofMinor(2000, CurrencyUnit.of("USD")), from = a, to = b, code = "deposit", metadata = mapOf("key1" to listOf("v1", "v2")), detail = DetailRef("Order", order.id))` |
| `DoubleEntry.lock_accounts(a, b) { … }` | `doubleEntry.lockAccounts(a, b) { lock -> … }` (section 5) |
| `raise ActiveRecord::Rollback` inside `lock_accounts` | `lock.rollback()` |
| `DoubleEntry::Validation::LineCheck.perform!(fixer: AccountFixer.new)` | `lineCheckService.perform(accountFixer)` (section 6) |

There are two ways to supply the definitions:

- one `DoubleEntryConfiguration` bean, as above; or
- one or more `DoubleEntryConfigurer` beans, applied in `@Order` order. Use these when several modules each define their
  own accounts:

  ```kotlin
  @Bean
  @Order(1)
  fun savingsAccounts() = DoubleEntryConfigurer { builder ->
      builder.accounts { define("savings", scopeIdentifier = { (it as User).id }, positiveOnly = true) }
  }
  ```

With neither, startup fails with `No DoubleEntryConfiguration bean defined: declare one with doubleEntry { … }`.
Definition errors (`DuplicateAccount`, `DuplicateTransfer`, `AccountIdentifierTooLongError`,
`TransferCodeTooLongError`, unknown currency) are thrown while the bean is built, so the application fails at startup
instead of at first use.

Then inject `DoubleEntry` wherever the Ruby code called the `DoubleEntry` module:

```kotlin
@Service
class Savings(private val doubleEntry: DoubleEntry) {
    fun deposit(user: User, amount: Money): TransferResult =
        doubleEntry.transfer(
            amount,
            from = doubleEntry.account("checking", user),
            to = doubleEntry.account("savings", user),
            code = "deposit",
        )

    fun balance(user: User): Money = doubleEntry.balance("savings", scope = user)
}
```

The library exceptions keep the Ruby class names and messages: for example `TransferNotAllowed`,
`AccountWouldBeSentNegative`, `UnknownAccount`, `LockMustBeOutermostTransaction`. Each has a `rubyClassName` for log
correlation, and messages that Ruby leaves empty are the Ruby class name (e.g. `DoubleEntry::TransferIsNegative`).

The host bean wins over the library's for: `doubleEntryClock` (a `java.time.Clock`, by bean name),
`DoubleEntrySchemaMigrator`, `DoubleEntryConfiguration`, `LockingSettings` and `DoubleEntry`.

## 3. `double-entry.*` properties

| Property | Default | Ruby equivalent |
|---|---|---|
| `double-entry.account-identifier-max-length` | unset (no limit) | `DoubleEntry.config.account_identifier_max_length` |
| `double-entry.scope-identifier-max-length` | unset (no limit) | `DoubleEntry.config.scope_identifier_max_length` |
| `double-entry.code-max-length` | unset (no limit) | `DoubleEntry.config.code_max_length` |
| `double-entry.default-currency` | `USD` | `Money.default_currency` (money gem) |
| `double-entry.flyway.enabled` | `true` | `rails generate double_entry:install` + `rake db:migrate` (section 4) |

Lengths count characters (Unicode code points), as Ruby's `String#length` does. Set the lengths to the column sizes of
your schema if they were shortened. Example:

```yaml
double-entry:
  account-identifier-max-length: 31
  scope-identifier-max-length: 23
  code-max-length: 47
  default-currency: AUD
```

The library reads no environment variables. The database connection is the host's `spring.datasource.*`.

The library also sets `hibernate.jdbc.time_zone=UTC` for the whole persistence unit (a `HibernatePropertiesCustomizer`).
This overrides a host value of `spring.jpa.properties.hibernate.jdbc.time_zone`. The host's own entities are then read
and written in UTC too.

## 4. Database schema and Flyway coexistence

The gem's install generator writes a Rails migration. The library instead ships Flyway migrations and applies them at
startup, before Hibernate validates the schema:

| Version | Content |
|---|---|
| `V1__double_entry_baseline.sql` | exactly the generator schema with `--json-metadata`: `double_entry_lines`, `double_entry_account_balances`, `double_entry_line_checks` and their indexes |
| `V2__account_balances_unique_nulls_not_distinct.sql` | deletes duplicate balance rows of the same account and scope (keeps the lowest id) and recreates `index_account_balances_on_scope_and_account` as `UNIQUE NULLS NOT DISTINCT` (X-03) |

Rules:

- **Own history table.** The library records its migrations in `double_entry_schema_history`, never in the host's
  table. Two Flyway instances must never share a history table. Keep the host's own (`flyway_schema_history` by
  default), and never point the host's Flyway at `classpath:db/double_entry/migration`.
- **Existing database written by the gem.** If `double_entry_lines` exists and `double_entry_schema_history` does not,
  the library records a baseline at version 1 and does not run V1 again. Then it runs V2. Rows written by Ruby stay as
  they are. This works like `baselineOnMigrate=true` with `baselineVersion=1`, but only when the double_entry tables are
  really there. A schema that holds only host tables is not baselined, and V1 runs.
- **Empty database.** V1 and V2 run.
- **Host Flyway on an existing database.** The host's Flyway sees the double_entry tables, so the schema is not empty.
  If the host's history table does not exist yet (the host adopts Flyway at this cut-over), set
  `spring.flyway.baseline-on-migrate=true`. Add `spring.flyway.baseline-version=0` if the host's own V1 must still run.
  If the Rails app kept its schema in `schema_migrations`, that table stays and is ignored.
- **Host Flyway on a new, empty database.** The order of the host's Flyway and the library's migrator at startup is not
  defined. If the library runs first, the host's Flyway finds the double_entry tables and stops with "non-empty schema
  without schema history table". Set `spring.flyway.baseline-on-migrate=true` and `spring.flyway.baseline-version=0`
  so the host's own migrations still run from V1 in either order.
- **Hibernate.** Use `spring.jpa.hibernate.ddl-auto=validate` or `none`. Never let Hibernate create or update these tables.
- **Managing the schema yourself.** Set `double-entry.flyway.enabled=false` and apply the SQL files from the jar
  (`db/double_entry/migration/*.sql`) with your own tooling, in version order.
- **Minimum PostgreSQL version.** The migrator stops startup on anything older than PostgreSQL 15 with
  `double_entry requires PostgreSQL 15 or later (UNIQUE NULLS NOT DISTINCT); connected to …`.

V2 takes an `ACCESS EXCLUSIVE` lock on `double_entry_account_balances` for the dedupe and the index rebuild. Writers,
Ruby or Kotlin, wait for it, so run the first start in a quiet period. Run the V2 pre-check from section 8 first, to see
which rows it deletes.

## 5. Transactions: the outermost-transaction rule vs `@Transactional`

In Ruby, `DoubleEntry.lock_accounts` and `DoubleEntry.transfer` must be the outermost database transaction, or they
raise `DoubleEntry::Locking::LockMustBeOutermostTransaction`. The Kotlin library keeps this rule strictly (D-007, BR-060):

- `doubleEntry.transfer(…)` and `doubleEntry.lockAccounts(…) { … }` open their own transaction.
- If a Spring-managed or JDBC transaction is already active, they throw `LockMustBeOutermostTransaction`. This includes
  a caller annotated with `@Transactional`, a `TransactionTemplate` block, and a test method with Spring's test-managed
  transaction.
- `DoubleEntry` is deliberately not `@Transactional`. Do not annotate the services that call it either.

What to do instead of `@Transactional`:

```kotlin
@Service
class Purchases(
    private val doubleEntry: DoubleEntry,
    private val orders: OrderRepository,
) {
    // No @Transactional here: lockAccounts is the transaction.
    fun purchase(user: User, order: Order) {
        val wallet = doubleEntry.account("checking", user)
        val shop = doubleEntry.account("revenue")
        doubleEntry.lockAccounts(wallet, shop) { lock ->
            // Transfers inside the block reuse its locks and its transaction.
            doubleEntry.transfer(order.total, from = wallet, to = shop, code = "purchase", detail = DetailRef("Order", order.id))
            // Other work of the same unit commits (or rolls back) atomically with the transfer.
            orders.save(order.markPaid())
            if (!order.isValid()) lock.rollback() // = raise ActiveRecord::Rollback
        }
    }
}
```

- Repository calls inside the block join the lock transaction (`Propagation.REQUIRED`). A nested
  `@Transactional(propagation = REQUIRES_NEW)` would run in a separate transaction, outside the locks. Avoid it.
- `lockAccounts` returns the block's value (X-05). After `lock.rollback()` the block runs to its end, the transaction
  rolls back, and the block's value is still returned.
- Nested `lockAccounts` calls are allowed if they lock a subset of the outer accounts. Otherwise they throw `LockNotHeld`.
- A deadlock (SQLSTATE `40P01`) while the locks are being taken rolls back and restarts the lock transaction before the
  block runs (`deadlock_restart.double_entry`, as in Ruby). A deadlock inside the block is not retried: it propagates
  to the caller and the transaction rolls back, so the block runs at most once. Retry at the caller if you need it,
  and keep side effects outside the database (emails, HTTP calls) until `lockAccounts` has returned.
- **Tests.** Spring's test-managed transactions (`@Transactional` on a test class) break the rule, as Rails
  transactional fixtures did. Prefer no test transaction and truncate the tables between tests. Otherwise declare
  `@Bean fun lockingSettings() = LockingSettings(runningInsideTransactionalFixtures = true)` in the test context. It
  tolerates exactly one enclosing transaction (the Ruby `running_inside_transactional_fixtures` switch). Never set it in
  production.

## 6. Scheduling `LineCheckService` with `@Scheduled`

The gem's `DoubleEntry::Validation::LineCheck.perform!` becomes the `LineCheckService` bean. `AccountFixer` is a bean
too. Like the gem, the library schedules nothing. The README recommends a run hourly to daily, depending on volume.

```kotlin
@Configuration
@EnableScheduling
class SchedulingConfiguration

@Component
class LineCheckJob(
    private val lineCheck: LineCheckService,
    private val fixer: AccountFixer,
) {
    // Ruby: DoubleEntry::Validation::LineCheck.perform!(fixer: DoubleEntry::Validation::AccountFixer.new)
    @Scheduled(cron = "0 15 * * * *") // hourly
    fun run() {
        lineCheck.perform(fixer) // or perform() to record the findings without fixing them
    }
}
```

- Call it **outside any transaction**: no `@Transactional`. The cached-balance check locks each touched account with
  `lockAccounts`, so an enclosing transaction throws `LockMustBeOutermostTransaction`.
- It checks only lines with an id above the last check's `last_line_id`. It writes one row to `double_entry_line_checks`
  and returns it as a `LineCheck`, or returns `null` when there were no new lines.
- Run it on **one instance** only (e.g. with ShedLock or a single scheduler node). Ruby had the same constraint.
- The gem's log lines are kept: header lines are byte-identical, and record dumps use a Kotlin rendering (X-06).
- Like Ruby, it can miss a line that took its id before, but committed after, a run that already saw a higher id
  (U-013, kept for parity).

## 7. Deviations from the gem (X-01 … X-11)

Everything else behaves as in the gem (same rows, same balances, same exceptions and messages). These are the
intentional differences, in host terms:

| # | What changes for the host | Why |
|---|---|---|
| X-01 | PostgreSQL only. No MySQL or SQLite support, no MySQL `USE INDEX`/`FORCE INDEX` hints. Lock and deadlock errors are classified by SQLSTATE. | D-002 |
| X-02 | Metadata is always stored in the jsonb `metadata` column of both lines. The legacy `double_entry_line_metadata` table (`json_metadata = false`, `--no-json-metadata`) is neither written nor read. Convert such data first (cut-over check U-004). | D-004 |
| X-03 | Exactly one balance row per account, also for unscoped accounts: the index becomes `UNIQUE NULLS NOT DISTINCT` and V2 deletes existing duplicates (keeping the lowest id, which is the row the gem reads). Ruby writers keep working against the new index. | D-005 |
| X-04 | A PostgreSQL lock timeout (`55P03`, e.g. with `lock_timeout` set) raises the library's `LockWaitTimeout` instead of `ActiveRecord::LockWaitTimeout`. Catch that class. | D-008 |
| X-05 | `lockAccounts` returns the block's value at every level. Ruby returned `nil` from the outermost call. | D-010 |
| X-06 | LineCheck log: the header lines are identical, but record dumps use a Kotlin rendering instead of Ruby `inspect` (`#<DoubleEntry::Line id: 1, …>`). Adjust log parsers that read the record dumps. | D-011 |
| X-07 | A transfer whose amount currency differs from the account currency throws `MismatchedCurrencies` before anything is written. Ruby raised `Money::Bank::UnknownRate` after committing balance rows. Non-`Money` and `nil` amounts are compile errors. | D-013 |
| X-08 | The scope identity is always a String: `scopeIdentity = "42"`, never the Integer `42`. A scope identifier lambda that returns `null` gives `""`. The stored value is the same as Ruby's. | D-014 |
| X-09 | The configuration is immutable after startup. There is no runtime `DoubleEntry.configure`. Tests that need other definitions use their own Spring context or call `doubleEntry { … }` directly. | D-016 |
| X-10 | Instrumentation: `deadlock_restart.double_entry`, `deadlock_retry.double_entry` and `duplicate_ignore.double_entry` become Spring `ApplicationEvent`s (`DoubleEntryEvent`) with the same names and log lines. Each one also increments a Micrometer counter `double_entry.<event>`, if a `MeterRegistry` exists. Replace `ActiveSupport::Notifications.subscribe` with an `@EventListener`. | D-012 |
| X-11 *(proposed, not yet recorded in decisions.md)* | Account identifiers and transfer codes are Strings, so the Symbol/String distinction disappears. In Ruby, `DoubleEntry.account('bank')` for an account defined as `:bank` raises `UnknownAccount`, and `code: 'salary'` for a transfer defined with `:salary` raises `TransferNotAllowed` (oracle `account_string_identifier`, `transfer_string_code`). In Kotlin both calls succeed. Ruby code that relied on these errors needs another guard. | backlog: under D-013, "impossible by typing" |

Further differences that only change the API's shape: `DoubleEntry` is an injected bean, not a module. Polymorphic
`detail` is a `DetailRef(type, id)` value: keep the Ruby class names (e.g. `"Order"`) as `type` for existing rows, and
the library never loads the target (D-015). Time comes from the `doubleEntryClock` bean (`Clock.systemUTC()` by default).

## 8. Cut-over checklist

Run these checks on **every** database before the first Kotlin start. Tick each one or record why it does not apply.

- [ ] **PostgreSQL 15 or newer** (D-002, D-005: `NULLS NOT DISTINCT`).
      ```sql
      SHOW server_version;  -- must be >= 15
      ```
- [ ] **U-004: no legacy metadata table in use.** The count must be 0, or the table must be absent. Otherwise convert
      the rows into `double_entry_lines.metadata` first (X-02, D-004).
      ```sql
      SELECT to_regclass('double_entry_line_metadata');          -- NULL: table absent, check passed
      SELECT count(*) FROM double_entry_line_metadata;            -- only if the table exists: must be 0
      ```
- [ ] **U-005: list the indexes of `double_entry_lines`.** Record the result. The generator names are
      `lines_account_code_created_at_idx`, `lines_account_created_at_idx`, `lines_scope_account_created_at_idx` and
      `lines_scope_account_id_idx`. Older gem versions and the gem's spec schema
      (`lines_account_code_created_at_partner_account_idx`) differ. Baselining never touches existing indexes, but any
      later migration that does needs these names.
      ```sql
      SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'double_entry_lines' ORDER BY indexname;
      ```
- [ ] **U-014: the Rails app's `default_timezone` is `:utc`.** Check `config.active_record.default_timezone` (Rails
      default `:utc`), or `ActiveRecord.default_timezone` in a Rails console. The Kotlin library reads and writes UTC
      `timestamp without time zone`. With `:local`, historic rows hold local times and `balance(at:)`/`from`/`to`
      results would shift.
- [ ] **V2 pre-check: duplicate NULL-scope balance rows.** V2 keeps the lowest id per `(account, scope)` and deletes the
      rest. List what it will delete, and keep the output for reconciliation:
      ```sql
      SELECT account, scope, count(*) AS rows, min(id) AS kept_id,
             array_agg(id ORDER BY id) AS ids, array_agg(balance ORDER BY id) AS balances
      FROM double_entry_account_balances
      GROUP BY account, scope
      HAVING count(*) > 1
      ORDER BY account, scope;
      ```
      Only unscoped accounts (`scope IS NULL`) can appear: the old unique index already prevents duplicates of scoped
      rows. The kept row is the one the gem locks and updates.
      After the migration, run `LineCheckService.perform(accountFixer)` once to confirm the cached balances.
- [ ] **U-003: the host runs Rails 7.2 or newer if Ruby and Kotlin write concurrently** (mixed writers, D-017). Only
      Rails 7.2/8.0/8.1 are in the gem's CI matrix, and the mixed-writer protocol was verified against ActiveRecord 8.1.4.
      ```sh
      bundle exec rails runner 'puts Rails.version'
      ```
- [ ] **Host Flyway and Hibernate settings** match section 4 (own history table, `baseline-on-migrate` where the
      host adopts Flyway, `ddl-auto=validate` or `none`).
- [ ] **No caller of `transfer`/`lockAccounts` runs inside `@Transactional`** (section 5). A grep for `@Transactional`
      in the classes that inject `DoubleEntry` is a good start.
- [ ] **Error handling and log parsing** updated for X-04 (`LockWaitTimeout`), X-06 (record dumps) and X-10 (events
      instead of `ActiveSupport::Notifications`).

## 9. Releasing the library

The gem was released with `rake release[remote]` (bundler gem tasks: `release:guard_clean`,
`release:source_control_push`, `release:rubygem_push`). The Kotlin equivalents:

| Ruby | Kotlin |
|---|---|
| `rake build` | `./gradlew build` (the library jar, sources and javadoc jars, POM) |
| install locally: `rake install` | `./gradlew publishToMavenLocal` (into `~/.m2/repository/com/softberries/double-entry/`) |
| `rake release:guard_clean` | not needed: a tag always points at a committed tree |
| `rake release:source_control_push[remote]` | `git tag vX.Y.Z && git push origin vX.Y.Z` |
| `rake release:rubygem_push` | the workflow `.github/workflows/release.yml`. It starts on tags `v*` only, builds and tests, and publishes `com.softberries:double-entry:X.Y.Z` to GitHub Packages with `GITHUB_TOKEN` |

The version comes from the tag: `v3.0.0` publishes `3.0.0`. Without `-Pversion` the build uses the `-SNAPSHOT` version
from `gradle.properties`. A tag that is not `vX.Y.Z` (optionally with a pre-release suffix such as `v3.0.0-rc.1`) fails
the workflow before anything is published.
