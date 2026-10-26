# double-entry-kotlin

Kotlin/Spring Boot port of the [double_entry](https://github.com/softberries/double_entry) Ruby ledger gem
(version 2.0.2, commit `f1474f0`). It is a double-entry bookkeeping library: accounts, transfers between them, running
balances, account locking and a line-check validator. All of it runs inside the host application's process, against
PostgreSQL.

## Deliverable (decision D-001)

The Ruby gem is a library with no routes, UI, jobs or mailers. The Kotlin port therefore ships as:

- a **library jar** (`double-entry`) built on Spring Data JPA and Flyway. Host applications call its in-process API.
- a **Spring Boot auto-configuration** for it (`DoubleEntryAutoConfiguration`). It replaces the Railtie and the install
  generator's initializer; no component scanning of the library packages is needed.

The library exposes no HTTP endpoints. The web parts of the general migration stack (Spring Web MVC, JTE, Spring Security,
Redis) are not used (`migration-spec/docs/decisions.md` ID-1).

The specification of the Ruby gem (functional docs, inventories and the runtime oracle) is copied verbatim to
[`migration-spec/`](migration-spec). Behaviour parity with it is the definition of done.

## Modules

| Module | Content |
|---|---|
| `double-entry` | The library, package-by-feature under `com.softberries.doubleentry`. Ships the Flyway migrations in `db/double_entry/migration`. Its `testFixtures` hold the shared test base (`PostgresIntegrationTest`, `MutableClock`, `TestUser`, `TestDoubleEntryApplication`). |
| `sample-host` | A minimal Spring Boot application that depends on the library. It provides `GET /actuator/health` and hosts integration and parity runs. |

## Build and test

Requirements: JDK 21 and a Docker daemon (Testcontainers starts `postgres:17`).

```
./gradlew build            # ktlint, compile, all tests (replaces `rake spec` / `rake default` / `rake build`)
./gradlew test             # tests only
./gradlew ktlintFormat     # fix formatting
./gradlew clean            # replaces `rake clean` / `rake clobber`
./gradlew publishToMavenLocal   # replaces `rake install`: com.softberries:double-entry:<version> (+ sources/javadoc jars, POM) into ~/.m2
```

Releases replace `rake release[remote]`: push a tag `vX.Y.Z` and `.github/workflows/release.yml` builds, tests and
publishes `com.softberries:double-entry:X.Y.Z` to GitHub Packages (U-010 proposal; see
[`docs/host-guide.md` §9](docs/host-guide.md#9-releasing-the-library)). The library is MIT-licensed like the gem
([LICENSE](LICENSE)).

CI (`.github/workflows/ci.yml`) runs the jobs `ktlint` and `build` on every push to `main` and on every pull request.

Running the sample host locally (needs a PostgreSQL database):

```
SAMPLE_HOST_DB_URL=jdbc:postgresql://localhost:5432/double_entry ./gradlew :sample-host:bootRun
curl -i localhost:8080/actuator/health   # 200 {"status":"UP"}; 503 {"status":"DOWN"} without a database
```

## Using the library in a host application

**Migrating a Rails host?** Read [`docs/host-guide.md`](docs/host-guide.md): Gradle dependency, the Ruby initializer
side by side with the Kotlin configuration, properties, Flyway coexistence, transactions, scheduling, the deviations
X-01..X-11 and the cut-over checklist. The summary below is the short version.

Add the dependency and declare the accounts and transfers (the Ruby `config/initializers/double_entry.rb`) as a
`DoubleEntryConfiguration` bean. Pass the bound `DoubleEntryProperties` to keep the `double-entry.*` settings:

```kotlin
@Configuration
class Ledger {
    @Bean
    fun doubleEntryConfiguration(properties: DoubleEntryProperties) =
        doubleEntry(properties) {
            accounts {
                define("bank")
                define("checking", scopeIdentifier = { (it as User).id }, positiveOnly = true)
            }
            transfers { define(from = "bank", to = "checking", code = "deposit") }
        }
}
```

Instead of the bean, one or more `DoubleEntryConfigurer` beans may contribute definitions (they are applied in `@Order`).
With neither, startup fails with `No DoubleEntryConfiguration bean defined: declare one with doubleEntry { … }`.

The auto-configuration then provides the **`DoubleEntry`** bean, the host API (`DoubleEntry.account/transfer/balance/
lock_accounts` in Ruby). There is no static access; inject the bean:

```kotlin
@Service
class Deposits(private val doubleEntry: DoubleEntry) {
    fun deposit(user: User, amount: Money): TransferResult =
        doubleEntry.transfer(amount, from = doubleEntry.account("bank"), to = doubleEntry.account("checking", user), code = "deposit")

    fun balance(user: User): Money = doubleEntry.balance("checking", scope = user)
}
```

> **Transactions (D-007).** `transfer` and `lockAccounts` open their own transaction and must be the **outermost**
> transaction, as in Ruby. Calling them from a `@Transactional` method (or inside any active transaction) throws
> `LockMustBeOutermostTransaction`. Do not annotate callers with `@Transactional`; put further database work of the
> same unit inside a `doubleEntry.lockAccounts(a, b) { … }` block instead (transfers inside it reuse its locks).

The auto-configuration also binds `double-entry.*` (documented in the jar's configuration metadata), runs the library
migrations (below) and registers the library's JPA entities and Spring Data repositories by adding
`com.softberries.doubleentry` to Boot's auto-configuration packages. A host that declares `@EntityScan` or
`@EnableJpaRepositories` itself must include that package. A host bean replaces the library's for: `doubleEntryClock`
(by name), `DoubleEntrySchemaMigrator`, `DoubleEntryConfiguration`, `LockingSettings` and `DoubleEntry`.

## Environment variables

The library reads no environment variables, and neither does the Ruby gem's `lib/`. Configuration is Spring properties
with the prefix `double-entry`: `account-identifier-max-length`, `scope-identifier-max-length`,
`code-max-length`, `default-currency` (default `USD`) and `flyway.enabled` (default `true`). Database credentials belong to the host (`spring.datasource.*`).

| Variable | Ruby use | Kotlin |
|---|---|---|
| `DB` | picks the spec database (`mysql`/`postgres`/`sqlite`) | not used (tooling only); PostgreSQL only (D-002) |
| `POSTGRES_HOST` | PostgreSQL host for specs | not used (tooling only); Testcontainers |
| `MYSQL_HOST` | MySQL host for specs | not used (tooling only) |
| `CI` | skips ruby-prof reports | not used (tooling only) |
| `BUNDLE_GEMFILE` | CI Rails-version gemfile | not used (tooling only); Gradle version catalog |
| `TESTCONTAINERS_HOST_OVERRIDE` | — | build only: host where Docker publishes ports (e.g. `127.0.0.1` with a Docker sidecar) |
| `TESTCONTAINERS_RYUK_DISABLED` | — | build only: `true` where the Ryuk reaper container cannot run |
| `SAMPLE_HOST_DB_URL`, `SAMPLE_HOST_DB_USERNAME`, `SAMPLE_HOST_DB_PASSWORD` | — | `sample-host` only: its datasource |

## Database schema and Flyway coexistence

The Ruby install generator (`rails generate double_entry:install`) and its initializer are not ported. The schema becomes
a Flyway migration shipped in the jar, and configuration moves to Spring properties and beans (M-005, M-014).

- Migrations live in `classpath:db/double_entry/migration` and are recorded in the library's own history table
  **`double_entry_schema_history`**. `V1__double_entry_baseline.sql` is exactly the generator schema with `--json-metadata`
  (tables `double_entry_lines`, `double_entry_account_balances`, `double_entry_line_checks`). V2 (M-008) makes the
  balance index `UNIQUE NULLS NOT DISTINCT`. No other migration exists without a backlog change.
- **Existing Ruby databases** (D-003): when `double_entry_lines` exists and `double_entry_schema_history` does not, the
  library records a baseline at version 1 and does not re-run V1. Rows written by Ruby stay as they are.
- **Empty schema, or a schema with only host tables**: V1 runs.
- The library runs its migrations when the application context starts, before Hibernate validates the schema.
  Set `double-entry.flyway.enabled=false` to manage the schema yourself (then apply the SQL from the jar).
- **Host requirements:**
  - If the host also uses Flyway in the same schema, it must keep its own history table (the default
    `flyway_schema_history` is fine) and never use `double_entry_schema_history`. Two Flyway instances must never share
    a history table.
  - The host Flyway sees the double_entry tables as a non-empty schema. If the host's history table does not exist yet,
    set `spring.flyway.baseline-on-migrate=true` (with `spring.flyway.baseline-version=0` if its own V1 must still run).
  - Use `spring.jpa.hibernate.ddl-auto=validate` (or `none`); never let Hibernate create or update these tables.
- Legacy installations that use the `double_entry_line_metadata` table (`--no-json-metadata`) are not supported; convert
  them to the jsonb `metadata` column first (X-02, D-004).

### Cut-over checklist

- [ ] PostgreSQL 15 or newer (`NULLS NOT DISTINCT`, D-005).
- [ ] The Ruby app's ActiveRecord `default_timezone` is `:utc` (U-014). The Kotlin library always writes UTC timestamps
      (`hibernate.jdbc.time_zone=UTC` is set by the library).
- [ ] The host accepts UTC JDBC timestamps for its own entities too. The library sets `hibernate.jdbc.time_zone=UTC`
      through a `HibernatePropertiesCustomizer`, which applies to the host's whole persistence unit and overrides a
      host-configured `spring.jpa.properties.hibernate.jdbc.time_zone`.
- [ ] Production indexes match the generator names (U-005). Baselining never touches existing indexes.
- [ ] No `double_entry_line_metadata` table in use (D-004).

## Balance validation (LineCheck)

`LineCheckService.perform(fixer)` checks the lines written since the last run (running balances, then the cached
balance of every touched account) and stores a row in `double_entry_line_checks`. Pass the `AccountFixer` bean to
repair what it finds. The library schedules nothing (as the Ruby gem); run it from the host, e.g. hourly to daily,
outside any transaction:

```kotlin
@Component
class LineCheckJob(private val lineCheck: LineCheckService, private val fixer: AccountFixer) {
    @Scheduled(cron = "0 0 * * * *")
    fun run() {
        lineCheck.perform(fixer)
    }
}
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).
