# PostgreSQL only, one final migration, and a tools module for what the host never runs

**Date:** 2026-09-17
**Status:** implemented, landed on dev on 2026-09-18 (plan
`docs/superpowers/plans/2026-09-17-postgresql-only-and-tools-module.md`, closing notes there)
**Supersedes:** the two-engine parts of `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`
(the SQLite experiment path of its sections 2, 3 and 12, and the paired migrations of its section 3); the
"Over-engineering survey (2026-09-12)" item 1 in `docs/backlog.md`, which this closes; the SQLite-based
"Running experiments" and "The development container" instructions of `server/README.md`.

## 1. Why, and the owner's rulings

An investigation of the whole module on 2026-09-17 (three package audits over `seed/`, `ai/`, and
`data/`, `store/`, `features/`, `facts/`, `model/`, `backtest/`, `eval/`, `capacity/` and `calendar/`, plus a
reading of `service/`, `run/`, `lifecycle/`, `web/`, `api/` and the two single-file programs) found the
forecast logic itself lean and the weight elsewhere: a synthetic seed generator of 2,348 lines and a
992-line PostgreSQL schema dump ship inside the library jar although nothing on the host's path uses them,
every JDBC statement carries a two-engine `Dialect`, five migrations exist twice, and the store's row
helpers are written three times because each engine returns values differently.

The owner ruled:

| | Subject | Ruling |
|---|---|---|
| A | what ships | Everything the host does not run leaves the library: the seed, the import and export code, the WorkloadHub schema script, the experiment driver and the sample host go to a second module that is run by hand. |
| B | the database | **PostgreSQL is the only database, everywhere.** The host's database is PostgreSQL; SQLite was only ever a local convenience. It is removed from the code, the resources, the tests, the scripts and the documentation. |
| C | migrations | The five migrations collapse into one final `V1`. A new database with newly seeded data replaces every existing one, so no migration history has to be preserved and Flyway's checksum validation is not a constraint. |
| D | local database | A PostgreSQL runs locally in a container under the same engine as the development box, and the experiment driver and the sample host connect to it. |
| E | tests | The suite is kept through this change and pruned where the audits found redundancy, dead subjects or engine wrappers. Coverage of the hard rules, the capacity arithmetic, the leakage test and the facts contract stays. The gate must get faster by measured cost, not by count. |
| F | the seeded fixture | Core tests read seeded rows from one generated SQL file committed under the core's test resources, produced by the tools module and checked fresh by it. |
| G | Docker | A gate without a reachable container engine fails; it does not skip. |

Rulings E, F and G were put to the owner explicitly and accepted on 2026-09-17.

## 2. Modules

### 2.1 `forecast-core`, the only artifact the host adds

Unchanged in purpose. It loses: the `seed` package; `ExportEnvelope`, `ExportFiles`, `ExportImporter`,
`ExportExporter` and `SqlExportWriter` from `data`; `WorkloadHubSchema` and `Dialect` from `store`; the
`schema/` resources; the `db/forecast/sqlite/` resources; the `sqlite-jdbc` dependency. What remains of
`ExportFiles` in core is its Jackson mapper, which ten core call sites use for facts, narratives, usage and
the run's backtest JSON: it becomes `com.workloadhub.forecast.Json`, a final class beside `Numbers` with one
`mapper()` method and the same configuration (indented output, LF indenters). The tools module reads the
same mapper.

The `optional` `postgresql` driver dependency stays optional: the host supplies its own driver, as today.

### 2.2 `forecast-tools`, run by hand, never shipped

A new Maven module `server/forecast-tools`, depending on `forecast-core`, the PostgreSQL driver and Spring
Boot's JDBC starter, holding:

| Package | Content | From |
|---|---|---|
| `com.workloadhub.forecast.tools.seed` | the eighteen seed classes, unchanged in behaviour | `forecast-core` `seed/` |
| `com.workloadhub.forecast.tools.export` | `ExportEnvelope`, `ExportFiles` (file reading and writing, minus the mapper), `ExportImporter`, `ExportExporter`, `SqlExportWriter`, `WorkloadHubSchema` and the resource `schema/workloadhub-postgresql.sql` | `forecast-core` `data/` and `store/` |
| `com.workloadhub.forecast.tools` | `Experiment`, the driver, now a compiled class with a `main` | `server/tools/Experiment.java` |
| `com.workloadhub.forecast.examples` | `HostExample`, the sample host, now a compiled class with a `main` | `server/examples/HostExample.java` |

The packages are renamed rather than kept, so no package is split across two jars: `data` and `store` stay
whole in core. The module's tests are the tests of what moved: the seed tests including
`WorkFamilyPropertyTest`, `ExportImporterTest`, `ExportExporterTest`, `SqlExportWriterTest`,
`RoundTripTest`, `ExperimentFlowTest` (which now calls `Experiment.run` in process, no launcher, no
subprocess), and the fixture freshness test of section 5.3. `fixtures/mini-export.json` moves with them.

Two consequences. First, the gate compiles the driver and the sample host, which the single-file launcher
never did for the sample host. Second, `server/tools/core-classpath.sh` becomes
`server/tools/tools-classpath.sh`, printing the tools module's classes and runtime classpath, and
`experiment.sh` and `run-host-example.sh` run `java --class-path "$cp" <main class>` against it. The
launcher scripts keep their names and their place.

### 2.3 What the parent POM says

`server/pom.xml` lists both modules in build order (`forecast-core`, then `forecast-tools`), drops
`sqlite.version` and the `sqlite-jdbc` managed dependency, and keeps the rest. The gate is unchanged in
shape: `cd server && mvn -B -q verify` builds and tests both.

## 3. The database

### 3.1 One final migration

`db/forecast/postgresql/V1__forecast_tables.sql` replaces V1 to V5. It is the end state of the five, written
once, with nothing dropped and re-created and no `DEFAULT` that existed only to make `ADD COLUMN NOT NULL`
possible on a populated table:

```sql
CREATE TABLE forecast_runs (
  id            uuid PRIMARY KEY,
  team_id       uuid NOT NULL,
  requested_by  uuid NOT NULL,
  as_of         date NOT NULL,
  status        varchar(16) NOT NULL,
  mae           double precision,
  backtest_json text,
  error         text,
  created_at    timestamp NOT NULL,
  finished_at   timestamp
);
CREATE INDEX forecast_runs_team_idx ON forecast_runs (team_id, created_at);

CREATE TABLE forecast_member_windows (
  run_id             uuid NOT NULL REFERENCES forecast_runs(id),
  user_id            uuid NOT NULL,
  window_index       integer NOT NULL,
  window_start       date NOT NULL,
  window_end         date NOT NULL,
  demand_hrs         double precision NOT NULL,
  low_hrs            double precision NOT NULL,
  high_hrs           double precision NOT NULL,
  capacity_hrs       double precision NOT NULL,
  overload_hrs       double precision NOT NULL,
  working_days       integer NOT NULL,
  absence_hrs        double precision NOT NULL,
  backlog_excess_hrs double precision NOT NULL,
  due_excess_hrs     double precision NOT NULL,
  PRIMARY KEY (run_id, user_id, window_index)
);

CREATE TABLE forecast_member_days (
  run_id        uuid NOT NULL REFERENCES forecast_runs(id),
  user_id       uuid NOT NULL,
  day           date NOT NULL,
  window_index  integer NOT NULL,
  demand_hrs    double precision NOT NULL,
  capacity_hrs  double precision NOT NULL,
  overload_hrs  double precision NOT NULL,
  working_day   boolean NOT NULL,
  PRIMARY KEY (run_id, user_id, day)
);

CREATE TABLE forecast_current_days (
  team_id       uuid NOT NULL,
  user_id       uuid NOT NULL,
  day           date NOT NULL,
  run_id        uuid NOT NULL REFERENCES forecast_runs(id),
  demand_hrs    double precision NOT NULL,
  capacity_hrs  double precision NOT NULL,
  overload_hrs  double precision NOT NULL,
  forecast_at   timestamp NOT NULL,
  PRIMARY KEY (team_id, user_id, day)
);
CREATE INDEX forecast_current_days_team_idx ON forecast_current_days (team_id, day);

CREATE TABLE forecast_facts (
  run_id      uuid PRIMARY KEY REFERENCES forecast_runs(id),
  facts_json  text NOT NULL,
  created_at  timestamp NOT NULL
);

CREATE TABLE forecast_narratives (
  id                uuid PRIMARY KEY,
  run_id            uuid NOT NULL REFERENCES forecast_runs(id),
  language          varchar(2) NOT NULL,
  status            varchar(16) NOT NULL,
  model             text,
  narrative_json    text,
  raw_text          text,
  verification_json text NOT NULL,
  usage_json        text NOT NULL,
  error             text,
  attempts          integer NOT NULL,
  tool_calls        integer NOT NULL,
  created_at        timestamp NOT NULL
);
CREATE INDEX forecast_narratives_run_idx ON forecast_narratives (run_id, language, created_at);

ALTER TABLE users ADD COLUMN github_token varchar(512);
ALTER TABLE users ADD COLUMN github_token_updated_at timestamp;
```

Every column a writer fills today is the same column; nothing the stores read or write changes. The comments
of the five files that explained why a column was added or dropped are history and go with them; the schema
diagram page of 2026-09-17 already shows the end state.

`ForecastMigrations` keeps `baselineOnMigrate`, `baselineVersion("0")`, `validateOnMigrate` and the
`task_service` schema, and loses `Dialect`: the location is `classpath:db/forecast/postgresql` and nothing
else. The package-private `run(dataSource, targetVersion)` test seam goes, because there is one version.

### 3.2 The store binds real types

`Dialect` existed because SQLite stores every value as text and PostgreSQL needs a cast for a string bound
to a `uuid`, `date` or `timestamp` column. With one engine the module binds the Java type and lets the driver
do its work: `UUID`, `LocalDate`, `LocalDateTime` and `Boolean` are passed as themselves through
`JdbcClient.param` and read back through `ResultSet.getObject(column, Type.class)`. Consequences, all in
`store/` and `data/ForecastRepository`:

- the twenty-four `dialect.` call sites go: no `placeholder`, `bool`, `asBoolean`, `boolLiteral`;
- the three private copies of `str`, `date`, `dateTime`, `num`, `uuid`, `dbl`, `time` and the two of `ph` and
  `ts` collapse into what `getObject` returns; a small package-private `store.JdbcValues` keeps the two
  readers that stay non-trivial (a nullable `Double` from a `Number`, a nullable `LocalDateTime` from a
  `Timestamp` where a driver hands one back);
- `DefaultForecastService.requireTeam` and `JdbcGitHubTokenStore` bind a `UUID` instead of a cast string;
- `JdbcRunStore` stops re-sorting in Java what its `ORDER BY user_id` already ordered (PostgreSQL orders
  `uuid` the way `Ids.UUID_ORDER` does), and its three hand-chunked batch loops become one call to
  `JdbcTemplate.batchUpdate(sql, List<Object[]>)`;
- the `Dialect` bean of `ForecastAutoConfiguration` goes; the `JdbcClient` bean stays.

This is the "consolidate the store's JDBC plumbing" item of the audit, done because the two-engine reason for
the plumbing is gone rather than as a separate cleanup.

### 3.3 Version

Everything runs PostgreSQL 18: the schema dump the module carries came from an 18 server on 2026-09-09. The
image tag lives in three places, `scripts/postgres.sh` and the two copies of the test support of section
5.1, all reading `postgres:18-alpine`; the tests move up from `postgres:16-alpine`. If the main developer
reports a different major on the host, those three lines change.

## 4. The local PostgreSQL and what connects to it

### 4.1 `scripts/postgres.sh`

A script in the style of `scripts/devbox.sh` (header is the help, `podman` first then `docker`, the same
`CONTAINER_ENGINE` variable):

```text
bash scripts/postgres.sh up        create the volume and start the container, or start it again
bash scripts/postgres.sh stop      stop it; `up` brings it back with its data
bash scripts/postgres.sh rm        remove the container; `rm --volume` also removes the data
bash scripts/postgres.sh status    is it running, which port, is the database reachable
bash scripts/postgres.sh psql      open psql inside the container
```

It runs `postgres:18-alpine` as container `whf-postgres` with the named volume `whf-pg` on
`/var/lib/postgresql` (**corrected 2026-09-18**: not `/var/lib/postgresql/data`, which the image's own
entrypoint reads as leftover data from before an upgrade and refuses to start on), publishes `5432` on the
host, and sets `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` to `workloadhub`. `WHF_PG_IMAGE`, `WHF_PG_CONTAINER`, `WHF_PG_VOLUME` and `WHF_PG_PORT`
override those, in the way `devbox.sh` names its own. The development box runs with `--network host`, so from
inside it the database is `localhost:5432`, the same address as from Windows.

The schema `task_service` inside that database is created by the driver's `init-db`, not by the script: the
script knows nothing about WorkloadHub.

### 4.2 Connection settings, one rule for both programs

The driver and the sample host connect the same way: `--url`, `--user` and `--password` on the command line,
each falling back to `WHF_DB_URL`, `WHF_DB_USER` and `WHF_DB_PASSWORD` in the environment, each falling back to
`jdbc:postgresql://localhost:5432/workloadhub`, `workloadhub` and `workloadhub`, which is what
`postgres.sh up` creates. The sample host passes them to Spring as `spring.datasource.url`, `username` and
`password`, so the host-side example now builds its `DataSource` the way the real server does, from
properties, instead of by hand from a file path: the example's one "real" bean becomes the one the server
also has for free.

`--db FILE` is gone from both, and so is `/data/workloadhub.db`. `WHF_DATA` and the `/data` mount of the
development box stay for exports and seed files, which must still live outside the repository.

### 4.3 The driver's commands

```text
usage: experiment.sh <command> [options]
  init-db  [--force]
           Create the schema task_service with the 24 WorkloadHub tables and the module's tables in
           the database at --url. Refuses when task_service already holds tables; --force drops the
           schema first.
  import   <export.json>
           Load a WorkloadHub JSON export (real or seeded), replacing existing rows.
  export   <out.json>
           Write the database's WorkloadHub tables as a JSON export.
  seed     --out FILE [--export FILE] [--synthetic] [--users N] [--weeks N]
           [--end ISO_DATE] [--seed N] [--format json|sql] [--force]
           Unchanged: generate an export with weeks of realistic history.
  fixture  [--out FILE]
           Regenerate forecast-core's seeded test fixture (section 5.3).
Connection: --url, --user, --password, else WHF_DB_URL, WHF_DB_USER, WHF_DB_PASSWORD, else the local
database scripts/postgres.sh creates.
```

`init-db` runs the WorkloadHub script, whose first statement is `CREATE SCHEMA task_service`, then
`ForecastMigrations.run`. It refuses when the schema already exists; `--force` runs
`DROP SCHEMA task_service CASCADE` first. The refusal without `--force` is the same protection the SQLite
version gave a file that existed. The `fixture` command writes to the path `experiment.sh` passes it, the
core's `fixtures/seeded-workloadhub.sql` resolved from the script's own location, so it works from any
working directory; `--out FILE` overrides it. The real-mode privacy guard of
`seed` (no output inside a git repository without `--force`) is unchanged.

The three-command way to a working database is now `postgres.sh up`, `experiment.sh init-db`,
`experiment.sh seed --synthetic --out /data/seed.json`, `experiment.sh import /data/seed.json`; the README
says so in that order.

### 4.4 The WorkloadHub schema script

`schema/workloadhub-postgresql.sql` moves to the tools module as it is: the cleaned `pg_dump`, run by
`init-db` and by the fixture command. `server/tools/translate-schema.py` shrinks to its cleaning half
(strip psql directives, `SET` lines, `OWNER` statements and dump banners) and writes one file; its SQLite
translation goes, and so does `SchemaFilesTest`, whose only job was to keep the two files in step. Trimming
the dump itself (the banner comments, the separate `ALTER TABLE ... ADD CONSTRAINT` statements) is cosmetic
and out of scope.

## 5. Tests

### 5.1 One database engine in the tests

`DatabaseTestSupport` loses `sqliteInMemory` and `keepAlive`, and `postgresOrSkip` becomes `postgres()`:
one `PostgreSQLContainer` per JVM as today, a fresh database per call as today, and instead of an assumption
that skips, an `IllegalStateException` whose message names the cause ("no container engine is reachable:
the gate needs Docker or podman; see server/README.md, The development container"). `scripts/check.sh` and
`scripts/check.ps1` test for the engine before Maven and fail with the same message, so the failure is the
first line, not the last of seventeen minutes; `.github/workflows/ci.yml` needs nothing, its runner has
Docker.

The fourteen test files that build an in-memory SQLite today are: eight that stay in core and use
`postgres()` (`ForecastAutoConfigurationTest`, `SampleHostApplication`, `ForecastRepositoryTest`,
`JdbcNarrativeStoreTest`, `JdbcGitHubTokenStoreTest`, `JdbcRunStoreTest`, `ForecastMigrationsTest`,
`SeededData`), three that move to the tools module (`SeedGeneratorTest`, `ExportImporterTest`,
`RoundTripTest`), two that are deleted (`DialectTest`, `SchemaFilesTest`, section 5.2), and
`DatabaseTestSupport` itself. The tools module's tests carry their own copy of `DatabaseTestSupport`: it is
thirty lines fixed to one engine, and a second copy is cheaper than a test-jar of core published for one
class. The two copies are kept identical by hand; the image tag is the one thing that would drift, and
section 3.3 names both places.

To keep the count of `CREATE DATABASE` calls down, a test class that needs several empty databases takes one
per class and truncates the module's tables between tests, rather than one per method. The plan measures the
gate before and after; the number is recorded in its closing notes.

### 5.2 The tests that go

With SQLite: `DialectTest`, `SchemaFilesTest`, and the stepwise-upgrade half of `ForecastMigrationsTest`
(one test remains: a fresh database migrates and holds the six module tables, the history table
`forecast_schema_history` and the two `users` columns).
`JdbcRunStoreTest`'s eight engine wrapper methods become the four scenarios they wrapped.

With the dead code the audits found and this change deletes (each with the test that only exercised it):
`Truth.SOURCE` and `Truth.realisedHours` with `TruthTest` and the twin-equality test in `WeeklySeriesTest`;
`Plan.absenceHours` with its two assertions in `AbsencePlannerTest`; `WorkQueue.Result.assignedHours` with
the tautological assertion in `WorkQueueTest`; `ForecastData.userById` and `memberById`; `MemberRow.withLeft`;
`ForecastWindow.contains`; `Backtest.Result.residuals(int)`; `XgboostHours.NAME` and `name()`;
`WeeklySeries.est`, `members` and `Cell.freshTasks`; `FeatureMatrix.codebooks`; `Metrics.mae` (callers use
`Numbers.mae`); the unused `origin` parameter of `Horizon.maxHorizon`; `SeedCalendar.holidays`;
`Rhythm.calendar`, `people` and `teams`; `Project.existing` and `family`; `Person.email`; the no-op
end-of-run loop in `WorkQueue` (lines 307 to 312 as of `c9bdc1c`) and `w.reopened = false` beside it;
`TaskFacts.assignmentFallback` stays, because `LifecycleTest` pins the fallback rule through it.
`Truncation` stays: `FeatureLeakageTest` is the proof that no future row reaches a feature.

As redundancy, per the audits: in `WorkQueueTest`, part (a) and part (f) of
`defaultRatesCoverAllModesSubTasksAndDataIntegrity` (the weaker assignee check and the third determinism test)
and `realisticDataset()`, whose "any week over 44 h" half is weaker than `WorkFamilyPropertyTest`'s
over-capacity rate and whose "any day over 8.8 h" half moves onto the shared fixture; in
`SeedGeneratorTest`, the key-count assertion that `syntheticModeStillWritesEveryTable` subsumes; in
`NarratorTest`, the usage-arithmetic re-assertions that `UsageTest` owns; in `PromptsTest`, the top-level
fields test that `ContractSchemaTest` subsumes, keeping its one unique assertion (`additionalProperties`
false) there.

Nothing else is removed. The verifier's negative tests, the capacity and leave properties, the contract and
facts-stability tests, the runner properties and the seed population property stay as they are.

### 5.3 The seeded fixture

Twenty-five core test classes build their data through the seed, and the seed now lives in a module that
depends on core; Maven allows no cycle, and moving those tests out of core would cost them their
package-private access. The fixture is the way through:

- `forecast-core/src/test/resources/fixtures/` holds two generated files (**amended 2026-09-18**, where this
  section first said one): `workloadhub-schema.sql`, the WorkloadHub schema script the fixture command
  copies, and `seeded-rows.sql`, the rows of the synthetic seed that `SeededData` builds today (36 users,
  30 weeks, seed 11, last day 2026-09-06), in the `INSERT ... ON CONFLICT` form `SqlExportWriter` already
  writes. Two files rather than one, so a test that needs only the empty schema does not load 2.8 MB of
  rows; both are written by the fixture command and both are checked by the freshness test.
- `experiment.sh fixture` writes them (the `fixture` command of section 4.3), from the same generator and
  the same constants, so they are reproducible byte for byte.
- `FixtureFreshnessTest` in the tools module regenerates them in memory and compares; a difference fails the
  gate with "run `bash server/tools/experiment.sh fixture` and commit the result". The test writes nothing.
- `SeededData` in core runs the two files once per JVM into a fresh database, then `ForecastMigrations.run`, and
  loads `ForecastData` through the real repository as today. `SeededFacts` and the sample-host tests are
  unchanged above it.
- The files are checked in with `-diff` in `.gitattributes`, so a regeneration shows as a changed binary,
  not as ten thousand changed lines. Measured on 2026-09-17 with the current generator at `c9bdc1c`: the
  seed's SQL export is 2.8 MB uncompressed and 0.54 MB as git stores it, for 1,506 tasks, 4,496 history
  rows, 4,739 time logs and 66 leaves; the schema script adds about 40 KB. A JSON export of the same seed is
  twice the size, which is one reason the fixture is SQL.

Seed constants are pinned in one place, `SeedGenerator`'s defaults and `SeededData`'s three numbers, and the
freshness test is what keeps the committed file honest: a change to the seed is a change to the fixture in
the same commit, and the core tests that read it see the new rows in the same gate.

### 5.4 Faster, by measured cost

Three changes the audits identified, each keeping every assertion: `SeedGeneratorTest` caches its
40-user generation instead of running it eleven times; `FactsBuilderTest` builds a `Prepared` by hand for
the three tests that only need a `TeamOutcome` shape, instead of fitting a booster; `FeatureBuilderTest`
builds the seeded matrix once per class. Property `tries` are untouched. The gate is timed in the development
container before the first task and after the last, from `TEST-*.xml`, never from the `.txt` files.

## 6. The sample host

`HostExample` moves to the tools module as a compiled class with the same options (`--team`, `--as-of`,
`--narrate`, `--lang`) minus `--db`, plus the three connection options of section 4.2. Its `DataSource` bean
goes: Spring Boot builds the pool from `spring.datasource.*`, which is exactly what the server does, so the
comment that marked that bean "real" now says the server has nothing to write for it at all. The fixed
`Clock` bean stays, still marked "example only". `run-host-example.sh` runs it against the tools classpath.
The test-side `samplehost/` package in core is unchanged except for the database it opens.

## 7. Scripts, launcher and container

- `server/tools/tools-classpath.sh` replaces `core-classpath.sh`: compiles both modules when stale
  (`mvn -B -q -pl forecast-tools -am -DskipTests compile`), resolves the tools module's runtime classpath,
  prints classes of both modules plus the dependencies.
- `experiment.sh` and `run-host-example.sh` keep their names and usage headers, updated for the connection
  options and the new `fixture` command.
- `scripts/devbox.sh`'s header stops describing `/data` as the place for databases; the `CONTAINER_SOCK`
  paragraph says the socket is now required by the gate, not only by the PostgreSQL tests; `status` reports
  whether `whf-postgres` is running by asking the engine, as a convenience.
- `scripts/container/Containerfile` drops `sqlite3` from the packages it installs; `postgresql-client` stays.
- `scripts/check.sh` and `scripts/check.ps1` gain the engine pre-check of section 5.1.

## 8. Documentation

- `CLAUDE.md`: the opening paragraph (no more "a single-file Java driver runs the same code on SQLite"), the
  "Layout" block (two modules, the tools module's content, the launcher scripts), "Toolchain" (Docker or
  podman is required by the gate; `postgres.sh`; the fixture command), "Hard rules" (unchanged), "Where the
  project stands" (one paragraph for this change), and the "Read these first" list gets this spec.
- `server/README.md`: "Prerequisites", "Build and test", "The development container", "Running experiments"
  (rewritten around `postgres.sh` and the connection rule), "What the seed writes" (unchanged content, new
  paths), "Using the module from the WorkloadHub server" ("What the module adds to the host's dependency
  tree" loses SQLite and the seed), and a short "The tools module" section.
- `docs/backlog.md`: survey item 1 closes with a pointer here; a new "Landed" entry; a new open item for the
  audits' remaining duplication folds outside the store (section 10).
- `docs/design/2026-09-17-workloadhub-schema-diagram.html`: the sentence on how the module's tables arrive
  says one migration, `V1`; the tables themselves are already drawn in their end state.
- `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md` gets a status line pointing here for
  its SQLite and paired-migration parts, in the way the 2026-09-12 design was marked history.
- `docs/superpowers/plans/2026-09-09-java-foundation-and-seed.md` and the other plans that mention
  `--db`, `workloadhub.db` or `sqliteInMemory` are history and are not edited; each already carries the
  date that places it.

## 9. Measurements to record in the plan's closing notes

- the fixture's size on disk and in git, against the 2.8 MB and 0.54 MB of section 5.3;
- the gate's wall time before and after, from `TEST-*.xml`;
- the number of tests before and after, with the removed ones listed against section 5.2;
- the size of `forecast-core`'s jar before and after.

## 10. Out of scope

- The audits' duplication folds outside `store/` (the `NumberVerifier` double parse, the gateway's version
  readers and timeout blocks, the shared sign-in check between `Narrator` and the service, the seed's row
  builder and person-map rebuilds, the arithmetic twins in `features/`, `facts/`, `capacity/` and
  `calendar/`): a follow-up plan, recorded in the backlog with the audit's file and line references.
- Items 2 to 4 of the 2026-09-12 survey: the CLI subprocess path to Copilot, `Clustering`, the speculative
  settings. `whf.flyway.enabled` stays in any case, because the host may own its migrations.
- Trimming the `pg_dump` text.
- Restoring CI's push triggers; the owner's pause of 2026-09-14 stands.
- Any change to the model, the features, the facts contract, the narrator or the product skills.

## 11. Order of work

1. Delete the dead code of section 5.2 with its tests, in core and in the seed, before anything moves, so the
   moves are smaller. Gate.
2. The final `V1`, the store on real types, `Dialect` and every SQLite artifact out of core's main and test
   sources, `DatabaseTestSupport.postgres()` with the hard failure, the engine pre-check in the two gate
   scripts. Gate, with Docker.
3. `Json` in core; the `forecast-tools` module with the renamed packages; `Experiment` and `HostExample` as
   classes; the launcher scripts; the `fixture` command; the committed fixture and `FixtureFreshnessTest`;
   `SeededData` on the fixture. Gate.
4. `scripts/postgres.sh`; the connection rule in the driver and the sample host; `init-db` on a schema;
   `HostExample` on `spring.datasource.*`; the container and devbox changes. Checked by hand in the
   development box: `postgres.sh up`, `init-db`, `seed`, `import`, `run-host-example.sh --team`.
5. The pruning and speed-ups of sections 5.2 and 5.4; the gate timed.
6. Documentation of section 8 and the measurements of section 9.

Then the standing workflow: a review per task, a whole-branch review, one fix wave, the gate green by hand in
the development container, `main` fast-forwarded to `dev`.
