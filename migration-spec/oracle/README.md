# Runtime oracle — double_entry

double_entry is a library (no HTTP, no UI), so the oracle records **in-process API behaviour** instead of HTTP samples or screenshots (docs/unknowns.md U-001, decision D-001).

- `api/json/<scenario>.json`: `json_metadata = true` (generator default, the Kotlin target mode). `api/table/<scenario>.json`: legacy `json_metadata = false` (out of scope, D-004).
  Each file has: `input` (description), `result` (serialised return value) or `exception` (class, message, ancestors), `db_after` (all rows of the double_entry tables),
  `notifications` (ActiveSupport events), and `sql` (the statements executed, from the AR log).
- `index.json` lists all 121 scenarios with their outcome per mode. `environment.txt` has the versions (Ruby 4.0.5, AR 8.1.4, money 7.1.1, PostgreSQL 17.11).
- `concurrency/jack_hammer_*.txt`: `script/jack_hammer` runs on PG (processes, processes + 4 balance flushes, threads).
- Reproduce: `tools/oracle.sh` (needs PG 17 on 127.0.0.1:5432, trust auth; uses `tools/oracle.rb` and the generator output in `inventory/generated/`).
- Timestamps in rows are wall-clock unless the scenario freezes time (T0 = 2026-01-15T10:00:00Z). IDs restart at 1 for every scenario.
- Rows for deviations X-01..X-10 (docs/decisions.md) show **Ruby** behaviour and are not the Kotlin expectation.
