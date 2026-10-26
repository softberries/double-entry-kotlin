# double-entry-kotlin

Kotlin/Spring Boot port of the [double_entry](https://github.com/softberries/double_entry) Ruby ledger gem
(version 2.0.2, commit `f1474f0`). It is a double-entry bookkeeping library: accounts, transfers between them, running
balances, account locking and a line-check validator. All of it runs inside the host application's process, against
PostgreSQL.

## Deliverable (decision D-001)

The Ruby gem is a library with no routes, UI, jobs or mailers. The Kotlin port therefore ships as:

- a **library jar** (`double-entry`) built on Spring Data JPA and Flyway. Host applications call its in-process API.
- a **Spring Boot auto-configuration** for it (added in M-014; until then the library's `<Feature>Configuration`
  classes are picked up by component scanning of `com.softberries.doubleentry`).

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
```

The Ruby release tasks (`rake release`, gem publication) are replaced by Gradle publishing in M-017.
CI (`.github/workflows/ci.yml`) runs the jobs `ktlint` and `build` on every push to `main` and on every pull request.

Running the sample host locally (needs a PostgreSQL database):

```
SAMPLE_HOST_DB_URL=jdbc:postgresql://localhost:5432/double_entry ./gradlew :sample-host:bootRun
curl -i localhost:8080/actuator/health   # 200 {"status":"UP"}; 503 {"status":"DOWN"} without a database
```

## Environment variables

The library reads no environment variables, and neither does the Ruby gem's `lib/`. Configuration is Spring properties
with the prefix `double-entry` (M-005). Database credentials belong to the host (`spring.datasource.*`).

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

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).
