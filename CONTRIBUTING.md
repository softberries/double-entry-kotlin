# Contributing

Work is planned as backlog units (`M-###`, see `migration-spec/` and the GitHub issues). One PR per unit, branch
`orch/<KEY>`, titled `[<KEY>] <issue title>`.

## Build files

`settings.gradle.kts`, the `build.gradle.kts` files and `gradle/libs.versions.toml` are complete after M-001. Only M-017
(publication) edits them. If a unit needs a new dependency, change the backlog first.

## Releases

Coordinates are `group`/`version` in `gradle.properties` (`com.softberries:double-entry`, `3.x.y-SNAPSHOT` between
releases). To release, push a tag `vX.Y.Z` on `main`; `.github/workflows/release.yml` builds, tests and publishes to
GitHub Packages. `PublicationTest` (task `publicationTest`, part of `check`) checks the POM and jar contents on every build.

## Packages

Package by feature (DDD aggregate), never by layer:

```
com.softberries.doubleentry.{money, errors, instrumentation, config, account, ledger, balance, locking, transfer, validation, autoconfigure}
```

- Each package has exactly one `<Feature>Configuration` (`@Configuration(proxyBeanMethods = false)`), e.g.
  `config.ConfigConfiguration`, `balance.BalanceConfiguration`. Beans of a feature are declared there.
- `autoconfigure` holds the real `@AutoConfiguration` (M-014), which imports the feature configurations.
- Table names come from `config.DoubleEntryTables` (BR-102), e.g. `@Table(name = DoubleEntryTables.LINES)`.

## Tests

- JUnit 5, Kotest assertions, MockK (`springmockk` for Spring beans). Integration tests use Testcontainers.
- Integration tests extend `com.softberries.doubleentry.testing.PostgresIntegrationTest` (test fixtures):
  - one shared `postgres:17` container per JVM, migrated once by the library migrator;
  - `TRUNCATE double_entry_lines, double_entry_account_balances, double_entry_line_checks RESTART IDENTITY` before each
    test, so ids start at 1 as in the oracle;
  - the context boots `TestDoubleEntryApplication` (`@EnableAutoConfiguration`, no component scanning), i.e. the
    library is wired exactly as in a host, by `autoconfigure.DoubleEntryAutoConfiguration`. A new feature configuration
    must be added to that class's `@Import` list;
  - a test declares its accounts and transfers in an `@Import`ed `@TestConfiguration` with `DoubleEntryConfigurer`
    beans. Tests that need different definitions for the same identifier use separate test classes (separate contexts).
- Time: the library takes `created_at`/`updated_at` from the `java.time.Clock` bean **`doubleEntryClock`**
  (`ConfigConfiguration.DOUBLE_ENTRY_CLOCK`, default `Clock.systemUTC()`). In tests it is the `MutableClock` fixture
  (inherited field `clock`): `clock.set(MutableClock.ORACLE_T0)` freezes time at `2026-01-15T10:00:00Z`,
  `clock.advance(...)` moves it, and it is reset before each test. This replaces Timecop (U-008).
- Scoped accounts in tests use `TestUser(id)` instead of the Ruby test-only `users` table.
- Name the Ruby spec examples a test ports in its KDoc (e.g. `line_spec.rb [1:2:4]`). Where a test checks an oracle
  sample, read it from `migration-spec/oracle/` through `MigrationSpec`.
- Never skip, disable or weaken a test to get a green build.
- Mixed Ruby + Kotlin writers (D-017, M-016): `mixed.MixedWritersTest` (tag `mixed`) runs the Ruby gem in a container
  built from `double-entry/src/test/resources/ruby-writer` next to Kotlin threads on its own `postgres:17`. It is kept
  out of `test`/`check`; run it with `./gradlew :double-entry:mixedWritersTest` (CI job `mixed-writers`). The first run
  builds the image (~3 min); later runs reuse Docker's layer cache. To track another gem commit, change
  `DOUBLE_ENTRY_COMMIT` in the Dockerfile.

## Database

- Flyway location `classpath:db/double_entry/migration`, history table `double_entry_schema_history`. Ruby-created
  databases are baselined at version 1 (D-003). Only V1 (M-001) and V2 (M-008) exist; any further migration needs a
  backlog change.
- Hibernate: `hibernate.jdbc.time_zone=UTC` (set by the library), `ddl-auto=validate` (tests and `sample-host`).
- PostgreSQL only (D-002).

## Style

- `./gradlew ktlintFormat ktlintCheck build` must pass. Kotlin official code style, JVM 21.
- Idiomatic Kotlin: immutable data, null-safety, explicit transactions, validation at the edges.
- Comment only where the intent is not obvious, and cite the business rule (`BR-###`) or decision (`D-###`, `X-##`).
