# The showcase web application on dev: the port, the run lookup, and a seed that ends today

**Date:** 2026-09-18
**Status:** proposed, not implemented. Awaiting the owner's review before a plan is written.
**Supersedes:** `docs/superpowers/specs/2026-09-18-forecast-web-and-showcase-ui-design.md` as it exists on
`origin/claude/forecast-web-showcase`, which was written against a base the module has left. That branch
stays as the record of the work; nothing on it is deleted by this document.

## 1. Why

`origin/claude/forecast-web-showcase` carries a working showcase application — a Spring Boot host and a
React front end that call the module the way the WorkloadHub server will. It was branched from `c9bdc1c`
(2026-09-17) at 14:42 on 2026-09-18, by which time `dev` had already been 29 commits and about 17 hours
past that base. The branch therefore describes a module that no longer exists.

The gap is not gradual. Measured as the whole diff between each `dev` commit and the showcase's head, it
grows in two steps, both in the PostgreSQL-only wave of the night of 2026-09-17 to 2026-09-18:

| `dev` commit | what it did | files differing | lines differing |
|---|---|---|---|
| `aafc3ea` | the wave's design document | 104 | 9,282 |
| `fc3eeb1` | every database test on PostgreSQL | 157 | 12,583 |
| `e392603` | five migrations collapsed into one PostgreSQL `V1` | 168 | 13,016 |
| **`8a67e79`** | **real JDBC types bound, SQLite removed from the module** | **188** | **14,604** |
| `d0e632e` | the seeded test data committed as a fixture | 207 | 27,041 |
| **`3472c17`** | **seed, export code, driver and sample host moved to `forecast-tools`** | **231** | **27,508** |
| `d28b1c1` | `dev` head | 244 | 29,349 |

`d0e632e` is the largest line jump but it is a 2.8 MB generated SQL fixture, not a structural change. The
two commits that matter are `8a67e79` and `3472c17`: after them the showcase's Java imports classes that
have moved package and module, or that no longer exist at all.

A trial merge of the showcase into `dev` head produces only five textual conflicts — `CLAUDE.md`,
`docs/backlog.md`, `scripts/check.sh`, `server/README.md`, `server/pom.xml` — all documentation and build
configuration. Git reports success on everything else, and the result compiles on neither side. The real
work is invisible to a merge, which is why this is a written port rather than a merge or a replay of the
showcase's eight commits.

Replaying those eight commits one at a time was considered and rejected for four reasons:

1. They are not eight increments. `13c058e` and `57c0ed4` (identical timestamps) carry 7,849 of the 8,856
   added lines; the rest are documentation, a gate step, a test fix and a fix wave.
2. The fix wave `bddcfe5` patches 16 of the 20 files those two commits created, so the hardest adaptation
   would be done twice.
3. The commit boundaries do not match the adaptation boundaries. What needs adapting is a list of files
   spread across four commits, not any one commit.
4. Three of the eight are pure documentation written against a module with no `forecast-tools` and a
   SQLite-capable core. Replaying them would reintroduce false statements; `0a0a606` in particular records
   that the work "sits on its own branch, not on dev", which stops being true the moment it lands.

The bulk of the branch needs no adaptation at all: 48 front-end files (TypeScript and React) and most of
the 26 web Java files speak to `ForecastService` and know nothing of the seed or the engine.

## 2. The owner's rulings of 2026-09-18

Four decisions were taken while this document was being drafted. They are the constraints; the rest of the
document follows from them.

**A. `forecast-web` depends on `forecast-core` and nothing else of ours.** No `forecast-tools`, no
`sqlite-jdbc`. The showcase is a production-shaped consumer of the module: it connects to a database
somebody else filled. `forecast-tools` exists precisely so that the seed and the driver stay out of what a
host consumes, and the showcase will not be the exception that makes that boundary meaningless.

**B. Seeding is not the application's job.** The database is filled beforehand with the existing driver,
exactly as `server/examples/run-host-example.sh` already documents for the sample host. `DemoDatabase` is
deleted rather than ported.

**C. The module gains a run lookup rather than the host keeping a shadow table.** See section 5.

**D. A seed ends on the day it is generated, and its last weeks are as dense as its middle.** The
first half is already the behaviour (6.4); the work is the second half. The application then reads the
system clock and needs no pinned date. See section 6.

**E. `V1` is edited in place; there is no `V2`.** Nothing has been run locally yet, so no database has
applied the migration and there is no checksum to break. `V1` remains the module's schema as it stands on
the day it first runs. See section 5.3.

## 3. The branch and the base

Work starts from `dev` at `d28b1c1` and lands on `dev`, per the project's convention that everything lands
there first. `origin/claude/forecast-web-showcase` is not merged, not rebased and not deleted; it is read
from with `git checkout <ref> -- <path>` and otherwise left alone as the record of where this code came
from.

One commit of that branch is independent of everything else here and lands first, on its own:

- `a915ad4` **"fix(test): the leave-days property's own floating-point arithmetic"** — a genuine fix to
  `LeaveDaysPropertyTest` and `LeaveDaysTest`. `dev` has not touched either file since `c9bdc1c`, so it
  cherry-picks clean. It is stranded on a branch that may never land, and it is a real bug; it goes in
  first with its own gate run, regardless of what happens to the rest of this document.

## 4. Part A — the port

### 4.1 What the module graph becomes

```
forecast-core   the product the server installs
forecast-tools  the seed, the export code, the driver, the runnable sample host (developer-only)
forecast-web    the showcase: Spring Boot + React, depends on forecast-core only        (new)
```

`server/pom.xml` lists three modules. `forecast-web/pom.xml` declares `workloadhub-forecast-core`, the
Spring Boot starters it already uses, and `org.postgresql:postgresql` at runtime scope. It does **not**
declare `sqlite-jdbc`, and the `sqlite.version` property the showcase reintroduced into the parent pom is
not restored.

### 4.2 What is deleted rather than ported

- **`DemoDatabase`** — the whole class. It was the hardest file in the port (nine references to code that
  has moved or been deleted, including `WorkloadHubSchema.createSqlite`, which exists nowhere on `dev`).
  Under ruling B it has no reason to exist.
- **`DemoConfiguration.demoDatabase()` and `DemoConfiguration.dataSource()`** — Spring Boot's own
  `spring.datasource.*` provides the `DataSource`, as it will in production. What remains of the class is
  the clock bean and the UI-resources bean; it is renamed, since nothing in it is a demo *database* any
  more.
- **`ForecastWebProperties.database`** (a filesystem path) and the whole **`forecast-web.seed.*`** block —
  the application does not seed and has no file.
- **The `Dialect` parameter** threaded through `ForecastAccess` and its callers. The showcase carried it
  only to paper over SQLite and PostgreSQL placeholder syntax:

  ```java
  jdbc.sql("SELECT role FROM users WHERE id = " + dialect.placeholder("uuid")).param(userId.toString())
  ```

  `Dialect` is one of the classes `8a67e79` deleted. With one engine the line is `... WHERE id = ?` with a
  `UUID` parameter, and the field goes. This is a deletion, not a port.
- **`RunRegistry` and its table** — section 5.

### 4.3 Import rewrites

Mechanical, about twelve files:

| the showcase imports | where `dev` keeps it |
|---|---|
| `com.workloadhub.forecast.seed.SeedGenerator`, `.SeedConfig` | `forecast-tools`, `…forecast.tools.seed.*` — **no longer referenced at all** |
| `com.workloadhub.forecast.data.ExportEnvelope`, `.ExportImporter`, `.ExportFiles` | `forecast-tools`, `…forecast.tools.export.*` — **no longer referenced at all** |
| `com.workloadhub.forecast.store.WorkloadHubSchema` | `forecast-tools`, `…forecast.tools.export.*` — **no longer referenced at all** |
| `com.workloadhub.forecast.store.Dialect` | deleted on `dev`; the showcase's use of it goes (4.2) |
| `com.workloadhub.forecast.Json` | moved to the root package by `35bbcf3`; the web module has its own `api.Json`, so the two must not be confused |
| `com.workloadhub.forecast.ai.FakeGateway`, `…store.DatabaseTestSupport`, `…testing.SeededData` | still in `forecast-core`'s **test** tree; reached through the test-jar (section 7) |

The first three rows collapse to nothing once `DemoDatabase` is gone: after ruling A no production source
file in `forecast-web` names anything outside `forecast-core`.

### 4.4 How the application gets its database

Plain `spring.datasource.*` in `application.yml`, defaulting to the local PostgreSQL that
`scripts/postgres.sh` serves — `jdbc:postgresql://localhost:5432/workloadhub`, user and password
`workloadhub`, schema `task_service`. Overridable by environment variable as every other property is.

Two halves, and the split is the point of the exercise:

- **The work tables** (`projects`, `tasks`, `task_history`, `time_logs`, `personal_leaves`) are filled
  beforehand by the driver. The application never writes them.
- **The module's own tables** (`forecast_runs`, `forecast_member_windows`, `forecast_member_days`,
  `forecast_current_days`, `forecast_facts`, `forecast_narratives`, and the two `users` columns) are
  created by `forecast-core`'s Flyway migration on start-up, unchanged. This is production behaviour and
  is not special-cased for the showcase.

### 4.5 `run.sh`

It loses every trace of seeding. It gains a pre-flight that runs before Maven: if the database does not
answer, or the work tables are empty, it prints the commands that fix it and exits non-zero —

```
bash scripts/postgres.sh up
bash server/tools/experiment.sh init-db
bash server/tools/experiment.sh seed --synthetic --users 40 --out /tmp/seed.json
bash server/tools/experiment.sh import /tmp/seed.json
```

— rather than letting a connection stack trace be the first thing a reader sees. The npm staleness check,
the Maven staleness check and `java -jar` are unchanged.

## 5. Part B — `findRun`, and deleting the host's shadow table

### 5.1 The finding

`RunRegistry` creates and maintains `forecast_web_runs`:

```sql
CREATE TABLE IF NOT EXISTS forecast_web_runs (
  run_id TEXT PRIMARY KEY, team_id TEXT NOT NULL, requested_by TEXT NOT NULL, started_at TEXT NOT NULL)
```

Every column is already in the module's own `forecast_runs`, created by `V1__forecast_tables.sql`:
`run_id`→`id`, `team_id`→`team_id`, `requested_by`→`requested_by`, `started_at`→`created_at`. The copy is
`TEXT`, so it cannot be joined to the original without a cast.

It does not exist because data is missing. It exists because `ForecastService` cannot answer "which team
does run *X* belong to?" for a run that is not finished, and that is the question a host must answer to
decide whether a caller may poll it:

| method | why it does not serve |
|---|---|
| `getRun(runId)` | throws `RUN_NOT_DONE` unless the status is `DONE` (`DefaultForecastService` lines 197–201) — and the case that matters is a run still computing |
| `listRuns(teamId, limit)` | takes the team, which is the thing being looked up |
| `progress(runId)` | `RunProgress` carries `runId`, phase, percent, message and label; no team, no requester |

The lookup already exists one layer down and is already public: `JdbcRunStore.find(UUID)` returns
`Optional<RunSummary>`, and `RunSummary` carries `id, teamId, requestedBy, asOf, status, mae, error,
createdAt, finishedAt`. `getRun` calls it and then throws the status check on top of it. It is simply not
on the interface.

### 5.2 The change

`ForecastService` gains:

```java
/** The run's summary whatever its status, for a host that must authorise a poll of a run still computing. */
Optional<RunSummary> findRun(UUID runId);
```

`DefaultForecastService.findRun` delegates to `store.find(runId)`. `getRun` is unchanged, including its
`RUN_NOT_DONE` behaviour: a host that wants the result still gets the same error, and a host that wants to
know whose run it is now has somewhere to ask.

`RunRegistry` is deleted, `forecast_web_runs` is never created, and `HostForecastFacade` reads
`service.findRun(runId)` in its place.

### 5.3 The second query, which is not free

`RunRegistry` also answers `latestRunOf(userId)` — the run this user started most recently, whatever its
state, which is what enforces the one-run-at-a-time rule. `forecast_runs` has `requested_by`, so the data
is there, but its only index is `forecast_runs_team_idx (team_id, created_at)`, and this query has no team.

This spec adds a second method rather than pretending the first one covers it:

```java
/** The most recent run this user requested, whatever its status and whatever the team. */
Optional<RunSummary> latestRunOf(UUID userId);
```

with its index added to `V1__forecast_tables.sql` itself, beside the one already there:

```sql
CREATE INDEX forecast_runs_team_idx ON forecast_runs (team_id, created_at);
CREATE INDEX forecast_runs_requested_by_idx ON forecast_runs (requested_by, created_at DESC);
```

**`V1` is edited in place, and no `V2` is added** (owner, 2026-09-18). `e392603` collapsed the five paired
migrations into one final `V1` because nothing had shipped, and that is still true: no database anywhere
has run these migrations yet, so there is no applied checksum for Flyway to disagree with and no deployment
to migrate forward. `V1` stays what it says it is — the module's schema as it stands on the day it first
runs. The moment a database somebody keeps has applied it, this stops being available and the next change
is a `V2`; that day has not come.

### 5.4 What this is, and what it is not

This is a change to `forecast-core` — the shipped artifact, and an interface the WorkloadHub server will
be written against. That is a heavier decision than anything else in this document, and it is taken
deliberately: any host that runs forecasts asynchronously hits this hole, and the alternative is that every
such host keeps its own shadow copy of four columns the module already stores. The showcase found it by
being the first real consumer, which is what a showcase is for.

Two backlog entries of the showcase branch are closed by it rather than carried across:

- *"`forecast-web`'s run registry is created outside Flyway"* — there is no table, no
  `CREATE TABLE IF NOT EXISTS` at bean construction, no requirement that the production server hold DDL
  rights at start-up, and no race between two instances starting together, which on PostgreSQL would have
  been a live defect rather than the theoretical one the entry describes.
- *"`forecast-web` registers a run after starting it"* — there is nothing to register. The module writes
  the `forecast_runs` row inside `startRun`, so the window in which a run exists but cannot be authorised
  is gone.

## 6. Part C — a seed that ends today, and the taper that stops it working

### 6.1 The requirement

A generated seed ends on the day it is generated, and the work in its final weeks is as dense as the work
in its middle. The showcase then reads the system clock and no date is pinned anywhere.

### 6.2 Why the end date alone is not enough

The showcase branch recorded this (`docs/backlog.md` on that branch, 2026-09-18): on its demo population
(120 users, 52 weeks, seed 7, ending 2026-09-06) the members log about 3,000 h a week from March to June,
then 2,300 h in mid-July and 500 to 1,300 h through August; tasks created per month fall from 1,171 in June
to 291 in August and 84 in September. A run on the seed's last day therefore forecasts near zero for
everybody. The showcase worked around it by pinning its clock to **2026-06-28**, inside the rich months.

Moving `end` to today without fixing this moves the dead zone with it. The cause, traced for this document,
is a seed-generator defect and not a wind-down the generator intends:

1. `ProjectPlanner.plan` draws an ACTIVE project's start from the **first 60%** of the window:

   ```java
   int startIndex = rnd.between(0, Math.max(0, (int) (mondays.size() * 0.6) - 1));
   start = mondays.get(startIndex);
   end = start.plusWeeks(rnd.between(12, 40));
   ```

   No ACTIVE project starts in the last 40% of the history. For a 52-week seed nothing new begins after
   about week 31.
2. One project in six is PLANNING, starting `lastDay + 1..8 weeks`, so `activeOn` is false for it
   throughout the whole history. It never contributes work.
3. `Project.activeOn(day)` requires `status.equals("ACTIVE") && windowStart <= day < windowEnd`.
4. `WorkQueue.planArrivals` gives a member no work at all in a week with no active project:

   ```java
   List<Project> active = candidates.stream().filter(pr -> pr.activeOn(monday)).toList();
   if (active.isEmpty()) {
       continue;
   }
   ```

So projects started in the first 60% reach their `windowEnd` through the tail, nothing replaces them,
members run out of candidate projects, and arrivals — and therefore logged hours — drain to nothing. The
backlog entry left it open whether the taper was intended; it is not. It is points 1 and 4 meeting.

### 6.3 The fix

- ACTIVE project starts are drawn across the **whole** window, not its first 60%.
- Each department is guaranteed at least one ACTIVE project covering **every** week of the window, the last
  week included, by construction: starts are staggered so that coverage is continuous, and a project whose
  `windowEnd` would fall inside the window is followed by another. The existing clamp of `windowEnd` to
  `lastDay + 1 week` stays, so projects genuinely run past the as-of date, which is what makes the
  `planned_hrs_h` and due-date features mean anything at the horizon.
- The PLANNING fraction stays. It is realistic, the module reads `projects.status`, and the feature matrix
  expects some. It simply does not count toward the coverage guarantee.

### 6.4 `end` already defaults to today

**Checked against the code on 2026-09-18: no change is needed here.** `Experiment.seed` already reads

```java
SeedConfig cfg = new SeedConfig(weeks, args.date("end") == null ? LocalDate.now() : args.date("end"),
        args.whole("seed", 42), synthetic, users);
```

(`Experiment.java:190`), and `Args.date` returns `null` for an absent option, so omitting `--end` already
ends the seed on the day it is generated. The flag stays for anybody reproducing a past run. The only
change this section asks for is one line of `USAGE`, which lists `[--end ISO_DATE]` without saying what
the default is.

An earlier draft of this document listed the default as work to do. It was wrong, and the error is
recorded here rather than silently removed, because it is the reason the requirement looked larger than it
is: **the whole of ruling D is section 6.3.** Ending the seed today was never the problem; the taper was.

**The committed fixture is already excluded, and stays so.** `experiment.sh fixture` calls
`SeedGenerator.generate(null, SeedGenerator.FIXTURE)` explicitly (`Experiment.java:214`), so it never sees
`--end`. `SeedGenerator.FIXTURE` stays `new SeedConfig(30, LocalDate.of(2026, 9, 6), 11, true, 36)` and
`SeededData.AS_OF` stays `2026-09-06`. Every database test in `forecast-core` reads that fixture and needs
the same bytes on every machine on every day; a fixture that ended "today" would change under the gate
daily and `FixtureFreshnessTest` would fail every night.

The fixture will nevertheless change **once**, because 6.3 changes the generator and `FixtureFreshnessTest`
compares the committed file against what the generator now produces. That regeneration is a task of this
plan, not a side effect to be discovered mid-gate.

## 7. Part D — the clock, and testing

### 7.1 The clock

With 6.3 and 6.4 in place, `forecast-web.clock.today` defaults to blank: the application follows the system
date. The pinned `2026-06-28` and the comment explaining the taper are removed from `application.yml` and
from the web README.

The mechanism stays. `DemoClock`, `forecast-web.clock.adjustable` and `POST /api/system/clock` are kept,
because moving the clock is how the showcase demonstrates the accuracy page: `accuracy(teamId, from, to)`
compares forecasts made *before* each past weekday against the hours logged on it, so a freshly seeded
database with no run history has nothing to score until somebody makes a run, moves the clock forward and
looks again. That is a feature of the showcase, not a workaround for a defective seed, and it survives.

### 7.2 Testing

`forecast-web`'s tests stop seeding anything in process. They use
`SeededData.freshDataSource()` from `forecast-core`'s test-jar: a PostgreSQL database from Testcontainers,
loaded from the committed fixture and migrated. No seed generator appears anywhere in the web module's
dependency graph, test scope included.

This keeps the showcase's one addition to `forecast-core/pom.xml` — a `maven-jar-plugin` `test-jar`
execution. It is test scope, never shipped, and does not breach ruling A. `dev` and the showcase edited
different parts of that file, so it merges without conflict.

**One blocker to clear first.** `DatabaseTestSupport.FIXTURES` is the *relative* path
`src/test/resources/fixtures`, resolved against whichever module is running. From `forecast-web`'s tests
the working directory is `server/forecast-web/`, where that path does not exist. The two fixture files are
already test resources, so they are on the test classpath and ride along inside the test-jar: the fix is
for `DatabaseTestSupport` to read them from the classpath rather than from a relative path.
`experiment.sh fixture` keeps writing them by path, since generation and consumption are different
directions. This lands as its own commit, before the bulk import, with its own reason.

For scale: `seeded-rows.sql` is 2.8 MB and `workloadhub-schema.sql` is 34 KB. Both inside a test-jar is
acceptable; it is worth knowing rather than discovering.

New tests this spec requires:

- **A per-week property on the seed's tail.** The existing `WorkFamilyPropertyTest` measures the mean over
  all weeks, which is exactly why it could not see the taper. The new property is per week over the last
  quarter of the window: every counted member has at least one ACTIVE candidate project in every week, and
  the mean member-week hours of the final eight weeks are within a stated band of the mean over the middle
  of the window. This is the guard that makes 6.3 stay fixed.
- **`findRun` and `latestRunOf`** in `DefaultForecastServiceTest`: a run in `RUNNING` returns its summary
  from `findRun` while `getRun` still throws `RUN_NOT_DONE`; `latestRunOf` returns the most recent run
  across teams and empty for a user with none.
- **`ForecastMigrationsTest`** covers the second index in `V1`.
- The web module's existing tests are ported, not rewritten: `ForecastWebIntegrationTest`, `TestBeans`,
  `ForecastAccessTest`, `HostForecastFacadeTest`. `DemoDatabaseTest` is deleted with its subject, and
  `RunRegistryTest` with its.

## 8. Documentation

Rewritten against `dev`'s state, never replayed:

- `server/forecast-web/README.md` — the three seeding commands, no file, no pinned clock, the system clock.
- `server/README.md` — a section for the showcase beside "Running experiments", pointing at the same local
  PostgreSQL that `run-host-example.sh` already uses.
- `CLAUDE.md` — `forecast-web` in the layout and in "where the project stands"; the seed's end date and the
  taper fix in the same paragraph, since both change what a reader should expect from a fresh seed.
- `docs/backlog.md` — section 9.
- The showcase branch's own design and plan documents are re-dated and rewritten to describe what actually
  landed. `0a0a606` ("this work sits on its own branch, not on dev") is dropped; it stops being true.

## 9. Backlog: carried, closed, opened

**Closed by this work** (they never reach `dev`'s backlog): the run registry outside Flyway, and
registering a run after starting it — both by section 5.

**Carried across, rewritten for one engine:**

- *Narration keys clear only when the call returns.* The in-flight set that refuses a second narration of
  the same run and language is cleared in the submitted task's `finally`, so a Copilot call that never
  returns keeps that run and language refused for the life of the process. `whf.copilot.timeout-seconds`
  bounds the call, so this needs the SDK to hang past its own timeout. A start timestamp per key, treating
  an entry older than the timeout as free, would close it. Unchanged by the port.

**Closed by section 6, and recorded as closed rather than deleted:** the seed's taper over its last eight
weeks. The backlog entry left open whether it was intended; 6.2 answers that it was not, and names the two
lines that cause it.

**Opened:**

- *The showcase's host layer duplicates the sample host in `forecast-core`'s tests.*
  `samplehost/ForecastAccess` (58 lines) and `samplehost/HostForecastFacade` (152 lines) in core's test tree
  against `forecastweb/host/ForecastAccess` (85) and `forecastweb/host/HostForecastFacade` (200). The web
  copies add reason strings a page can show, and until now also the `Dialect` divergence. With `Dialect`
  gone (4.2) they are close enough that keeping both is a choice rather than an accident. Not resolved
  during the port; worth a look afterwards.

## 10. Out of scope

- Merging or rebasing `origin/claude/forecast-web-showcase`. It is read from and left alone.
- Any change to the front end beyond what compiling and the new clock default require. The 48 TypeScript
  and React files come across as they are.
- Restoring CI. It stays paused by the owner's decision of 2026-09-14; the gate is `bash scripts/check.sh`
  run by hand in the development container.
- Any redesign of the accuracy page, the narration flow or the role rules. The showcase's behaviour is
  ported, not revisited.
- The derived-arithmetic gap in `NumberVerifier` (`docs/backlog.md`, "Java migration"). Unrelated and still
  open.

## 11. Order of work

1. Cherry-pick `a915ad4` (the leave-days floating-point fix). Gate.
2. `DatabaseTestSupport` reads the two fixtures from the classpath (7.2).
3. `ForecastService.findRun` and `latestRunOf`, the `requested_by` index added to `V1`, with their
   tests (section 5).
4. The seed: ACTIVE project starts across the whole window with continuous coverage, `--end` defaulting to
   today, the per-week tail property, and the fixture regenerated once (section 6).
5. Bulk import of `server/forecast-web/`, plus hand-merged `server/pom.xml`, `scripts/check.sh` and
   `scripts/check.ps1`. Does not compile; committed as its own step so the adaptation reads as a diff.
6. Build wiring: `forecast-web/pom.xml` against `forecast-core` only; no `sqlite-jdbc`; the parent pom's
   `sqlite.version` not restored.
7. Delete `DemoDatabase` and `RunRegistry`; strip `DemoConfiguration` and rename it; drop `Dialect` from
   `ForecastAccess` and its callers; rewrite the remaining imports (4.2, 4.3).
8. `application.yml`, the blank clock default, and `run.sh`'s pre-flight (4.4, 4.5, 7.1).
9. The web module's tests onto `SeededData` (7.2).
10. Documentation and backlog (sections 8 and 9).
11. Whole-branch review, one fix wave, the gate green by hand in the development container.

Steps 1 to 4 touch only `forecast-core` and `forecast-tools` and are independently landable: if the port is
deferred, the leave-days fix, the run lookup and the seed's tail are worth having on their own.
