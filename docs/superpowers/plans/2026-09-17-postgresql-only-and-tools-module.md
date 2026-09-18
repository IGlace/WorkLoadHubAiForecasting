# PostgreSQL Only and Tools Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make PostgreSQL the module's only database with one final migration, move everything the host never runs into a second Maven module, and give the developer a local PostgreSQL container that the driver and the sample host connect to.

**Architecture:** `forecast-core` stays the only shipped artifact, PostgreSQL only: one `V1` migration, JDBC on real types, no `Dialect`. A new `forecast-tools` module holds the seed, the import and export code, the WorkloadHub schema script, the experiment driver and the sample host as compiled classes. Core tests get their seeded rows from two committed SQL files that the tools module generates and checks fresh, so no module depends on the other in the wrong direction.

**Tech Stack:** Java 21, Maven 3.9, Spring Boot 4.1 (`JdbcClient`, `JdbcTemplate`), Flyway, PostgreSQL 18 (`postgres:18-alpine`), Testcontainers, JUnit 6, jqwik, XGBoost4J, copilot-sdk-java.

**Spec:** `docs/superpowers/specs/2026-09-17-postgresql-only-and-tools-module-design.md`

## Global Constraints

- Every text file is LF (`.gitattributes`: `* text=auto eol=lf`). Never write CRLF.
- The gate is `cd server && mvn -B -q verify`, run by hand; CI is paused. Read results from `forecast-core/target/surefire-reports/TEST-*.xml` (and `forecast-tools/target/surefire-reports/TEST-*.xml` once the module exists), never by summing the `.txt` files. Wipe both report directories before a run you intend to trust.
- The gate needs a reachable container engine from task 3 onward (spec ruling G). A gate without one fails; it never skips.
- PostgreSQL 18 everywhere: the image tag `postgres:18-alpine` appears in exactly three places when the plan is done (`scripts/postgres.sh`, `forecast-core`'s `DatabaseTestSupport`, `forecast-tools`'s `DatabaseTestSupport`).
- The local database the scripts create: URL `jdbc:postgresql://localhost:5432/workloadhub`, user `workloadhub`, password `workloadhub`, schema `task_service`. The driver and the sample host read `--url`, `--user`, `--password`, then `WHF_DB_URL`, `WHF_DB_USER`, `WHF_DB_PASSWORD`, then those defaults.
- The seeded fixture is the synthetic seed of 36 users, 30 weeks, seed 11, last day 2026-09-06 (`SeedGenerator.FIXTURE`), committed as two files under `server/forecast-core/src/test/resources/fixtures/`: `workloadhub-schema.sql` and `seeded-rows.sql`. (The spec's section 5.3 says one file; this plan uses two so that tests needing only the empty schema do not load 2.8 MB of rows. Task 11 records the amendment in the spec.)
- No model identifier in any commit message, code comment or document. Commit messages: imperative subject, a body that says why.
- The hard rules of `CLAUDE.md` are untouched: no change to the model, the features, the facts contract, the narrator or the product skills.
- Package renames are the only code change the moves carry: behaviour of the seed, the importer, the exporter and the SQL writer is identical, and the seeded output stays byte for byte the same.

---

## File map

**Deleted from `forecast-core` main:** `store/Dialect.java`, `store/WorkloadHubSchema.java` (moved), `data/ExportEnvelope.java`, `data/ExportFiles.java`, `data/ExportImporter.java`, `data/ExportExporter.java`, `data/SqlExportWriter.java` (all moved), the whole `seed/` package (moved), `eval/Metrics.java`'s `mae`, `resources/db/forecast/sqlite/`, `resources/db/forecast/postgresql/V2..V5`, `resources/schema/` (moved).

**Created in `forecast-core`:** `Json.java` (root package), `store/JdbcValues.java`, `resources/db/forecast/postgresql/V1__forecast_tables.sql` (rewritten), test `store/DatabaseTestSupport.java` (rewritten), test `testing/SeededData.java` (rewritten), test resources `fixtures/workloadhub-schema.sql` and `fixtures/seeded-rows.sql` (generated).

**Created module `server/forecast-tools`:** `pom.xml`; main `com.workloadhub.forecast.tools.Experiment`, `com.workloadhub.forecast.tools.seed.*` (18 files), `com.workloadhub.forecast.tools.export.{ExportEnvelope, ExportFiles, ExportImporter, ExportExporter, SqlExportWriter, WorkloadHubSchema}`, `com.workloadhub.forecast.examples.HostExample`, resource `schema/workloadhub-postgresql.sql`; tests moved from core plus `FixtureFreshnessTest`, `tools/testing/DatabaseTestSupport`, resource `fixtures/mini-export.json`.

**Scripts:** `scripts/postgres.sh` (new), `scripts/check.sh` and `scripts/check.ps1` (engine pre-check), `server/tools/tools-classpath.sh` (replaces `core-classpath.sh`), `server/tools/experiment.sh`, `server/examples/run-host-example.sh`, `server/tools/translate-schema.py` (cleaning half only), `scripts/container/Containerfile`, `scripts/devbox.sh` (header and `status`).

**Docs:** `CLAUDE.md`, `server/README.md`, `docs/backlog.md`, `docs/design/2026-09-17-workloadhub-schema-diagram.html`, `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md` (status line), the spec of this plan (fixture amendment, closing notes).

## Task order and why

1. Dead code outside the seed. 2. Dead code in the seed. Both before anything moves, so the moves are smaller.
2b. (Added during execution.) Real mode plans projects for the application's own teams: the first PostgreSQL run of the suite found a foreign-key failure on unmodified `dev` that SQLite had hidden.
2c. (Added during execution.) Real mode refuses an export missing a status or type: the second foreign-key failure behind the same test, reached once 2b landed.
3. Every database test on PostgreSQL, the hard failure without an engine, the gate pre-check. SQLite still exists in main.
4. The final `V1` migration; the SQLite migrations deleted.
5. `Dialect` out; the stores, the repository, the service, the importer, the exporter, the driver and the sample host on PostgreSQL types and URLs; every SQLite artifact and dependency gone.
6. `Json` in the root package.
7. The seeded fixture and core tests on it, with the seed still in core, so the gate stays green with no module yet.
8. The `forecast-tools` module: the moves, the renames, the driver and the sample host as classes, the launcher classpath.
9. `scripts/postgres.sh`, the container and devbox changes, the hand check.
10. Test pruning and speed-ups; the gate timed.
11. Documentation and the measurements.

Each task ends with the gate green and a commit. The gate from task 3 on needs Docker or podman reachable.

---

### Task 1: Delete the dead code outside the seed

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/eval/Truth.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/features/WeeklySeries.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/features/FeatureMatrix.java:61-63`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/backtest/Backtest.java:40-45`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/model/XgboostHours.java:18,34-36`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/eval/Metrics.java:9-11`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/ForecastData.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/rows/MemberRow.java:23-25`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/calendar/ForecastWindow.java:16-18`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/calendar/Horizon.java:52-54`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/run/ForecastRunner.java:126`
- Test: `eval/TruthTest.java`, `features/WeeklySeriesTest.java`, `features/FeatureBuilderTest.java:180`, `model/XgboostHoursTest.java:65`, `eval/MetricsTest.java`, `capacity/CapacityRuleTest.java:119`, `calendar/HorizonTest.java:55`, `ForecastAutoConfigurationTest.java:160` (all under `server/forecast-core/src/test/java/com/workloadhub/forecast/`)

**Interfaces:**
- Produces: `Horizon.maxHorizon(int windows)` (the `origin` parameter is gone); `WeeklySeries.Cell(double estHours, double freshHours, int nTasks)`; `Truth` with `realisedHoursByDay` only; `ForecastData` without `memberById()` and `userById()`; `Numbers.mae` as the one MAE.

- [ ] **Step 1: Confirm each symbol has no main caller**

Run from `server/`:

```bash
for s in 'Truth.realisedHours(' 'Truth.SOURCE' '\.est(' '\.members()' 'freshTasks' 'codebooks()' 'residuals(int' 'XgboostHours.NAME' '\.name()' 'Metrics.mae' 'memberById()' 'userById()' 'withLeft(' '\.contains(' 'maxHorizon('; do
  echo "== $s"; grep -rn "$s" forecast-core/src/main --include=*.java | grep -v 'Truth.java\|WeeklySeries.java\|FeatureMatrix.java\|Backtest.java\|XgboostHours.java\|Metrics.java\|ForecastData.java\|MemberRow.java\|ForecastWindow.java\|Horizon.java'
done
```

Expected: `.members()` and `.name()` and `.contains(` show unrelated hits (other classes' methods); read each hit and confirm none is `WeeklySeries.members()`, `XgboostHours.name()` or `ForecastWindow.contains(`. `Metrics.mae` shows its callers in main, if any (replace them in step 6). `maxHorizon(` shows `ForecastRunner.java:126` only.

- [ ] **Step 2: Shrink `Truth`**

Replace the whole file with:

```java
package com.workloadhub.forecast.eval;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.features.MemberDay;
import java.util.SortedMap;
import java.util.TreeMap;

/** What the forecast is measured against: the hours people logged, per member and day. */
public final class Truth {

    private Truth() {
    }

    /** The hours people logged, per member and day, rounded to six decimals. */
    public static SortedMap<MemberDay, Double> realisedHoursByDay(ForecastData data) {
        SortedMap<MemberDay, Double> out = new TreeMap<>();
        for (TimeLogRow l : data.timeLogs()) {
            out.merge(new MemberDay(l.userId(), l.day()), l.hours(), Double::sum);
        }
        out.replaceAll((k, v) -> Math.round(v * 1e6) / 1e6);
        return out;
    }
}
```

In `TruthTest.java` delete `sumsLogsPerUserAndMondayWeek` and `seededTruthCoversEveryLog`; keep `sumsLogsPerUserAndDay`; remove the now-unused imports (`MemberWeek`, `SeededData`, `SortedMap`, `assertTrue`).

In `WeeklySeriesTest.java` delete `theSeriesAgreesWithTruth` and the `Truth`, `SortedMap`, `MemberWeek` imports if nothing else uses them.

- [ ] **Step 3: Shrink `WeeklySeries`**

In `WeeklySeries.java`:
- `Cell` becomes `public record Cell(double estHours, double freshHours, int nTasks)`, `ZERO = new Cell(0.0, 0.0, 0)`, and `plus` becomes:

```java
        Cell plus(TaskFacts f) {
            return new Cell(estHours + f.estimate(), freshHours + (f.fresh() ? f.estimate() : 0.0), nTasks + 1);
        }
```
- Delete the `members` field, its assignment, the `members()` accessor and the `est(UUID)` method. The constructor and `build` keep their `members` parameter (it supplies the id set).
- Replace the comment `// Same rounding as eval.Truth.realisedHours, ...` with `// Rounded to six decimals, the same rounding as eval.Truth.realisedHoursByDay.`

In `WeeklySeriesTest.java`: every `new WeeklySeries.Cell(a, b, c, d)` becomes `new WeeklySeries.Cell(a, b, c)` (four sites); delete the line asserting `s.est(ANA.id())`.

Run: `grep -rn 'WeeklySeries.Cell(' forecast-core/src --include=*.java` and fix any other four-argument site the same way.

- [ ] **Step 4: Delete `FeatureMatrix.codebooks()`, `Backtest.Result.residuals(int)`, `XgboostHours.NAME` and `name()`**

- `FeatureMatrix.java`: delete the `codebooks()` accessor (the private field stays; XGBoost's categorical encoding reads it). `FeatureBuilderTest.java:180`: delete the line `assertEquals(data.members().size(), m.codebooks().get("member_id").size());`.
- `Backtest.java`: delete the `residuals(int h)` method and its javadoc inside `Result`.
- `XgboostHours.java`: delete `public static final String NAME = "xgboost";` and the `name()` method. `XgboostHoursTest.java:65`: delete `assertEquals("xgboost", xgb.name());`.

- [ ] **Step 5: Delete `ForecastData.memberById()` and `userById()`, `MemberRow.withLeft`, `ForecastWindow.contains`**

- `ForecastData.java`: delete the fields `memberById` and `userById`, their two `index(...)` assignments in the constructor, and the two accessors.
- `CapacityRuleTest.java:119`: replace `MemberRow m = d.memberById().get(l.employeeId());` with

```java
            MemberRow m = d.members().stream().filter(x -> x.id().equals(l.employeeId())).findFirst().orElse(null);
```
- `MemberRow.java`: delete `withLeft`.
- `ForecastWindow.java`: delete `contains`.

- [ ] **Step 6: `Metrics.mae` goes; `Numbers.mae` is the one definition**

Run `grep -rn 'Metrics.mae' forecast-core/src --include=*.java`. Replace every hit with `Numbers.mae` (import `com.workloadhub.forecast.Numbers` where needed), then delete the `mae` method from `Metrics.java`. `MetricsTest.pointMetrics` keeps its `bias` assertion and its two `mae` assertions now read `Numbers.mae(...)`.

- [ ] **Step 7: `Horizon.maxHorizon(int)`**

`Horizon.java`: the method becomes

```java
    /**
     * The largest horizon a run of this many windows reaches, for every run day: the origin is always the
     * previous week's Monday, so the run day's own week is horizon 1, and {@code 5 * windows} weekdays
     * starting inside horizon 1 or 2 end no later than the last weekday of horizon {@code windows + 1}.
     */
    public static int maxHorizon(int windows) {
        return windows + 1;
    }
```

`ForecastRunner.java:126`: `Backtest.origins(origin, firstWeek, Horizon.maxHorizon(windows))`. `HorizonTest.java:55`: `Horizon.maxHorizon(count)`. `ForecastAutoConfigurationTest.java:160`: `assertEquals(7, Horizon.maxHorizon(6));` (drop the now-unused `Weeks`/`asOf` pieces of that line if nothing else uses them).

- [ ] **Step 8: Gate**

Run from `server/`: `rm -rf forecast-core/target/surefire-reports && mvn -B -q verify`
Expected: exit 0. Then `grep -h '<testsuite' forecast-core/target/surefire-reports/TEST-*.xml | sed -E 's/.*name="([^"]+)".*tests="([0-9]+)".*failures="([0-9]+)".*errors="([0-9]+)".*/\1 \2 \3 \4/' | awk '{t+=$2; f+=$3; e+=$4} END {print t" tests, "f" failures, "e" errors"}'` prints 0 failures and 0 errors; the test count is 450 minus the tests deleted here (three in `TruthTest`/`WeeklySeriesTest`).

- [ ] **Step 9: Commit**

```bash
git add -A server/forecast-core
git commit -m "Delete unused code the audits found outside the seed

Truth.realisedHours and its twin test, WeeklySeries.est/members/freshTasks,
FeatureMatrix.codebooks, Backtest.Result.residuals(int), XgboostHours.name,
Metrics.mae, ForecastData.memberById/userById, MemberRow.withLeft,
ForecastWindow.contains and the unused origin parameter of Horizon.maxHorizon
had no caller in main code; each test that only exercised one of them goes
with it (spec 2026-09-17, section 5.2)."
```

---

### Task 2: Delete the dead code in the seed

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/WorkQueue.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/AbsencePlanner.java:20-33`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/SeedCalendar.java:85-87`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/Rhythm.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/Project.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/ProjectPlanner.java` (the two `new Project(` sites)
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/Person.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/Directory.java` (the `new Person(` site)
- Test: `seed/WorkQueueTest.java`, `seed/AbsencePlannerTest.java`, `seed/RhythmTest.java`, `seed/ProjectPlannerTest.java`, `seed/DirectoryTest.java` and every other test file with `new Person(` or `new Project(`

**Interfaces:**
- Produces: `WorkQueue.Result(taskRows, historyRows, timeLogRows, nextTaskNumber, workedHours)`; `Person(id, fullName, jobTitle, department, deptCode, managerId, role, family, joined, left)` (no `email`); `Project(id, key, name, teamId, ownerId, status, windowStart, windowEnd)` (no `existing`, no `family`).
- Byte-identical seeded output: none of these changes adds or removes a `SeedRandom` draw. Step 1 pins that before anything changes.

- [ ] **Step 1: Pin the seeded output before touching the seed**

Run from `server/` (the classpath script compiles core first):

```bash
cp="$(bash tools/core-classpath.sh)" && java --class-path "$cp" tools/Experiment.java seed --synthetic --users 36 --weeks 30 --end 2026-09-06 --seed 11 --out /tmp/seed-before.json && sha256sum /tmp/seed-before.json
```

Keep the hash; step 8 compares against it.

- [ ] **Step 2: `WorkQueue`**

- Delete the `Result` component `assignedHours` (the record becomes `Result(taskRows, historyRows, timeLogRows, nextTaskNumber, workedHours)`), the field `private final Map<UUID, Map<LocalDate, Double>> assignedHours = new TreeMap<>();`, the line `assignedHours.computeIfAbsent(p.id(), ...)` in `assign`, and pass `q.workedHours` as the last argument in `run`.
- Delete the block

```java
        // tasks still queued keep their state; remaining reflects the logs
        for (Work w : queue) {
            if (w.row.get("original_estimate_hrs") != null) {
                w.row.put("remaining_estimate_hrs", Math.max(0.0, w.estimate - w.logged));
            }
        }
```

  (every writer of `w.logged` already writes the same value into the row on the same line, and a never-logged task's row starts at its estimate, so the loop changes nothing).
- Delete `w.reopened = false;` inside `if (rnd.chance(rates.reopen()))` (the field default is already `false`); keep the `w.reopenOn = LocalDate.MIN;` line and its comment.

In `WorkQueueTest.openWorkRemainsAtTheEndAndAssignedHoursMatchEstimates`: rename it `openWorkRemainsAtTheEnd`, delete the `fromRows`/`fromMap` block (from `double fromRows = 0;` through `assertEquals(fromRows, fromMap, 1e-6);`), keep the three other assertions.

- [ ] **Step 3: `AbsencePlanner.Plan.absenceHours`, `SeedCalendar.holidays()`, `Rhythm`**

- `AbsencePlanner.java`: delete the `absenceHours(LocalDate monday)` method. `AbsencePlannerTest.java`: delete the three lines `LocalDate monday = SeedConfig.mondayOf(anyAbsent);`, `assertTrue(plan.absenceHours(monday) >= ...);` and the `presentDays` line plus its `assertEquals(...absenceHours(monday), 1e-9);` (four lines in all, the ones at 76-79 as of `aafc3ea`); keep `assertEquals(0.0, plan.hoursPresent(p, anyAbsent));`.
- `SeedCalendar.java`: delete the `holidays()` accessor (the field stays; `isWorkingDay` reads it).
- `Rhythm.java`: delete the fields `people` and `teams` and their two constructor assignments (the constructor keeps both parameters and still reads `people.keySet()` and iterates `teams`); delete the `calendar()` accessor. In `RhythmTest.targetIsBaseTimesFactorsAndLeadersAreHalved`, replace `r.calendar()` with the calendar the test's `rhythm(...)` helper built: read the helper at the top of `RhythmTest`; it builds a `SeedCalendar` (from `AbsencePlannerTest.cal()` or a local `cal()`), so pass that same expression.

- [ ] **Step 4: `Project` without `existing` and `family`, `Person` without `email`**

- `Project.java`: the record becomes `Project(UUID id, String key, String name, UUID teamId, UUID ownerId, String status, LocalDate windowStart, LocalDate windowEnd)`.
- `ProjectPlanner.java`: at both `new Project(` sites drop the last two arguments.
- `Person.java`: the record becomes `Person(UUID id, String fullName, String jobTitle, String department, String deptCode, UUID managerId, String role, WorkFamily family, LocalDate joined, LocalDate left)`; `withRole` drops `email`.
- `Directory.java`: at its `new Person(` site drop the email argument.
- Tests: run `grep -rn 'new Person(\|new Project(' forecast-core/src/test --include=*.java`; at every `new Person(` drop the third argument (the `"x@example.test"` string); at the one `new Project(` in `WorkQueueTest` drop the last two arguments. `ProjectPlannerTest`: delete `assertEquals(WorkFamily.CALIBRATION, p.family());` and replace `projects.stream().filter(Project::existing).findFirst().orElseThrow()` with `projects.stream().filter(p -> p.key().equals("WH")).findFirst().orElseThrow()`.

- [ ] **Step 5: Stale text in the seed**

`SeedGenerator.java`: the class javadoc `/** Orchestrates the seed: directory, calendar, absences, projects, rhythm, work queue, capacity, envelope. */` loses `capacity, `; the step comment `// 6. project rows with the next task number` becomes `// 5. ...` and `// 7. envelope in table order` becomes `// 6. ...`.

- [ ] **Step 6: Compile and run the seed tests**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='com.workloadhub.forecast.seed.*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: exit 0.

- [ ] **Step 7: Gate**

`rm -rf forecast-core/target/surefire-reports && mvn -B -q verify` from `server/`; exit 0; read `TEST-*.xml` as in task 1.

- [ ] **Step 8: Prove the seeded output is byte-identical**

```bash
cp="$(bash tools/core-classpath.sh)" && java --class-path "$cp" tools/Experiment.java seed --synthetic --users 36 --weeks 30 --end 2026-09-06 --seed 11 --out /tmp/seed-after.json && sha256sum /tmp/seed-before.json /tmp/seed-after.json
```

Expected: the two hashes are equal. If they differ, a draw was added or removed: find it with `diff <(jq -S . /tmp/seed-before.json) <(jq -S . /tmp/seed-after.json) | head` and fix before committing.

- [ ] **Step 9: Commit**

```bash
git add -A server/forecast-core
git commit -m "Delete the seed's leftovers from the capacity removal

WorkQueue's end-of-run remaining loop could not change a row, Result.assignedHours,
Plan.absenceHours, SeedCalendar.holidays, Rhythm.calendar and two write-only
fields, Project.existing/family and Person.email had no reader in main code
since CapacityWriter and the absences table went (spec 2026-09-17, section 5.2).
The seeded export is byte-identical before and after."
```

---

### Task 2b: Real mode uses the application's own teams (added by ruling during execution)

**Why this task exists.** Task 1's gate, the first ever run of the PostgreSQL tests since the 2026-09-17 seed change landed (that landing ran without Docker), found `SqlExportWriterTest.aFiveTableScriptLandsTwiceOnPostgresql` failing on unmodified `dev`: `insert or update on table "projects" violates foreign key constraint ... Key (team_id)=(...) is not present in table "teams"`. Cause: `Directory.derive` invents department teams and manager teams (`rnd.uuid()`) in both modes, and `ProjectPlanner.plan` gives every invented department team two to four projects; in real mode the seed writes only `projects`, `tasks`, `task_history`, `time_logs` and `personal_leaves` (spec 2026-09-17 personal leaves, section 7.1), so the invented teams never reach the database and every new project points at a team that does not exist. SQLite never enforced the foreign key, which is why this passed until now. From Task 3 on PostgreSQL is mandatory, so this must be fixed first.

**Ruling.** In real mode the application's teams are the structure: `Directory.derive` builds its `Team` values from the export's `teams` and `team_members` rows and invents none; a team with no `parent_team_id` is a department team (it receives the projects), a team with one is a member team of that department. Synthetic mode is unchanged and its output stays byte-identical. A counted person who belongs to no team gets no work (the module does not count such a user either: `ForecastRepository` requires a membership).

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/Directory.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/WorkQueue.java` (`run`)
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/SeedGeneratorTest.java` (new test), `seed/DirectoryTest.java` (its config), `data/SqlExportWriterTest.java` (the failing test, unchanged, must pass)

**Interfaces:**
- Consumes: `Directory.derive(userRows, teamRows, memberRows, cfg, rnd)`, `Directory.Result`, `Team(id, name, managerId, parentId, memberIds, department, deptCode)`, `SeedConfig.synthetic()`, `Rhythm.teamOf(Person)`.
- Produces: in real mode `Directory.Result.teams()` holds exactly the export's teams, and `userRows`, `teamRows`, `teamMemberRows` are the input rows unchanged; `WorkQueue.run` simulates only counted people that have a team.

- [ ] **Step 1: Reproduce on HEAD**

From `server/`: `mvn -B -q -pl forecast-core test -Dtest=SqlExportWriterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `aFiveTableScriptLandsTwiceOnPostgresql` errors with the foreign-key message above (Docker is running on this machine; the test starts PostgreSQL through Testcontainers).

Also pin the synthetic output before the change, from `server/`:

```bash
cp="$(bash tools/core-classpath.sh)" && java --class-path "$cp" tools/Experiment.java seed --synthetic --users 36 --weeks 30 --end 2026-09-06 --seed 11 --out /tmp/seed-2b-before.json && sha256sum /tmp/seed-2b-before.json
```

- [ ] **Step 2: The failing test for the rule**

Add to `SeedGeneratorTest`:

```java
    /** Real mode writes no teams, so every project it plans must belong to a team the export already has. */
    @Test
    void realModeProjectsBelongToTheExportsOwnTeams() throws Exception {
        ExportEnvelope input = ExportFiles.read(java.nio.file.Path.of("src/test/resources/fixtures/mini-export.json"));
        ExportEnvelope env = SeedGenerator.generate(input, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        Set<String> teams = ids(input, "teams");
        for (var p : env.rows("projects")) {
            assertTrue(p.get("team_id") == null || teams.contains(p.get("team_id")), "project " + p.get("key") + " points at an invented team");
        }
        assertFalse(env.rows("tasks").isEmpty(), "the export's team still gets work");
        for (var t : env.rows("tasks")) {
            assertTrue(ids(env, "projects").contains(t.get("project_id")));
        }
    }
```

Run: `mvn -B -q -pl forecast-core test -Dtest=SeedGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the new test fails on the first assertion (an invented team id).

- [ ] **Step 3: `Directory.derive` in real mode**

In `Directory.java`, immediately after step 3 (the roles block ends with the `heads` loop) and before the comment `// 4. department teams`, insert:

```java
        // Real mode: the application's own teams are the structure, and the seed writes none (design
        // 2026-09-17, section 7.1), so nothing may be invented here. A team without a parent is a department
        // team and receives the projects; a team with one is a member team of that department.
        if (!cfg.synthetic()) {
            return new Result(new ArrayList<>(people.values()), applicationTeams(teamRows, memberRows, people), userRows, teamRows, memberRows);
        }
```

and add the method beside `existingTeams`:

```java
    /** The export's teams as the generator's Team values: department when parentless, deptCode from the members' majority. */
    static List<Team> applicationTeams(List<LinkedHashMap<String, Object>> teamRows, List<LinkedHashMap<String, Object>> memberRows,
            Map<UUID, Person> people) {
        List<Team> out = new ArrayList<>();
        for (LinkedHashMap<String, Object> t : teamRows) {
            UUID id = UUID.fromString((String) t.get("id"));
            List<UUID> members = new ArrayList<>();
            Map<String, Integer> codes = new TreeMap<>();
            for (LinkedHashMap<String, Object> m : memberRows) {
                if (!id.toString().equals(m.get("team_id"))) {
                    continue;
                }
                UUID member = UUID.fromString((String) m.get("user_id"));
                members.add(member);
                Person p = people.get(member);
                if (p != null && p.deptCode() != null) {
                    codes.merge(p.deptCode(), 1, Integer::sum);
                }
            }
            String code = codes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            UUID parent = t.get("parent_team_id") == null ? null : UUID.fromString((String) t.get("parent_team_id"));
            out.add(new Team(id, (String) t.get("name"), t.get("manager_id") == null ? null : UUID.fromString((String) t.get("manager_id")),
                    parent, members, parent == null, code));
        }
        return out;
    }
```

`existingTeams` and the synthetic path stay exactly as they are (the synthetic output must not change by a byte).

- [ ] **Step 4: `WorkQueue.run` skips counted people without a team**

In `WorkQueue.run`, `counted.removeIf(p -> !p.counted());` becomes `counted.removeIf(p -> !p.counted() || rhythm.teamOf(p) == null);` with the comment `// a counted person in no team gets no work: the module does not count such a user either`. In synthetic mode everyone is in a team, so this removes nobody there.

- [ ] **Step 5: `DirectoryTest` runs the derivation it tests in synthetic mode**

`DirectoryTest.cfg()` returns `new SeedConfig(20, LocalDate.of(2026, 9, 6), 42L, true, 0)` (the derivation of teams from managers and departments is the synthetic mode's; a real export brings its own teams).

- [ ] **Step 6: Prove it**

`mvn -B -q -pl forecast-core test -Dtest='SeedGeneratorTest,DirectoryTest,SqlExportWriterTest,RoundTripTest,WorkQueueTest' -Dsurefire.failIfNoSpecifiedTests=false` passes, including `aFiveTableScriptLandsTwiceOnPostgresql`.

Byte-identity of the synthetic seed:

```bash
cp="$(bash tools/core-classpath.sh)" && java --class-path "$cp" tools/Experiment.java seed --synthetic --users 36 --weeks 30 --end 2026-09-06 --seed 11 --out /tmp/seed-2b-after.json && sha256sum /tmp/seed-2b-before.json /tmp/seed-2b-after.json
```

Expected: equal hashes.

- [ ] **Step 7: Gate**

`rm -rf forecast-core/target/surefire-reports && mvn -B -q verify` from `server/`; exit 0 this time (Task 1 ended with exactly this one error). Read `TEST-*.xml`: 0 failures, 0 errors.

- [ ] **Step 8: Commit**

```bash
git add -A server/forecast-core
git commit -m "Give real-mode projects to the application's own teams

Real mode writes five work tables and no teams, yet the seed still invented
department and manager teams and planned every project for them, so each new
project pointed at a team the database never receives. PostgreSQL's foreign
key caught it the first time the PostgreSQL tests ran after that change
landed; SQLite never enforced it. In real mode the export's teams are now the
structure, a parentless one being the department that receives the projects,
and a counted user in no team gets no work. Synthetic output is byte-identical."
```

---

### Task 2c: Real mode refuses an export that lacks a status or a type (added by ruling during execution)

**Why this task exists.** With Task 2b landed, `SqlExportWriterTest.aFiveTableScriptLandsTwiceOnPostgresql` reaches a second, independent foreign-key failure: `tasks.task_status_id` (and `task_type_id`) not present in `task_statuses`. Cause: `SeedGenerator.generate` substitutes `ReferenceData.statusRows()` and `typeRows()` whenever the input export does not name every status and type the generator writes (`Reference.covers` is false), in both modes. In synthetic mode that is right, because the synthetic envelope writes `task_statuses` and `task_types` itself. In real mode the seed writes only the five work tables (spec 2026-09-17 personal leaves, section 7.1), so every task then points at status and type rows the database does not have. The test fixture `mini-export.json` carries two statuses and one type, which is why the substitution fires there. SQLite never enforced the key.

**Ruling.** A real export always carries the application's own statuses and types, because the application defines them; real mode therefore refuses an export that lacks any of the statuses or types the seed writes, with an `IllegalArgumentException` naming every missing one, and never substitutes. Synthetic mode keeps the substitution. The fixture gains the full set of nine statuses and twelve types so the real-mode tests use the export's own ids. The 36-user synthetic seed (no input) stays byte-identical.

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/SeedGenerator.java` (the `Reference.covers` branch)
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/Reference.java` (`from` reports every missing name; new `missing`)
- Modify: `server/forecast-core/src/test/resources/fixtures/mini-export.json` (complete `task_statuses` and `task_types`)
- Test: `seed/SeedGeneratorTest.java` (new test), `seed/ReferenceTest.java` if it pins the old message, `data/SqlExportWriterTest.java` (the failing test, unchanged, must pass)

**Interfaces:**
- Consumes: `Reference.from(statusRows, typeRows)`, `Reference.covers(...)`, `ReferenceData.STATUSES` (name, category, order, description) and `ReferenceData.TYPES`, `SeedConfig.synthetic()`.
- Produces: `Reference.missing(statusRows, typeRows)` returning `""` when complete, else `task_statuses lacks 'A', 'B'; task_types lacks 'C'` (each part only when non-empty); `SeedGenerator.generate(input, cfg)` throws `IllegalArgumentException("real mode needs an export that carries every task status and type the seed writes: " + missing)` in real mode when the export is incomplete.

- [ ] **Step 1: Reproduce on HEAD and pin the synthetic seed**

From `server/`: `mvn -B -q -pl forecast-core test -Dtest=SqlExportWriterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `aFiveTableScriptLandsTwiceOnPostgresql` errors with a foreign-key message naming `task_status_id` or `task_type_id`.

```bash
cp="$(bash tools/core-classpath.sh)" && java --class-path "$cp" tools/Experiment.java seed --synthetic --users 36 --weeks 30 --end 2026-09-06 --seed 11 --out /tmp/seed-2c-before.json && sha256sum /tmp/seed-2c-before.json
```

- [ ] **Step 2: The failing tests**

Add to `SeedGeneratorTest`:

```java
    /** A real export carries the application's own statuses and types; one that lacks any is refused, never patched with invented ids. */
    @Test
    void realModeRefusesAnExportMissingAStatusOrType() throws Exception {
        ExportEnvelope real = ExportFiles.read(java.nio.file.Path.of("src/test/resources/fixtures/mini-export.json"));
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>(real.data());
        data.put("task_statuses", real.rows("task_statuses").stream().filter(s -> !"Done".equals(s.get("name"))).toList());
        data.put("task_types", real.rows("task_types").stream().filter(t -> !"Bug".equals(t.get("name"))).toList());
        ExportEnvelope partial = new ExportEnvelope(real.database(), real.schema(), real.exportedAt(), real.excludedTables(), data);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SeedGenerator.generate(partial, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0)));
        assertTrue(e.getMessage().contains("task_statuses lacks 'Done'") && e.getMessage().contains("task_types lacks 'Bug'"), e.getMessage());
    }

    /** Real mode writes no statuses or types, so every task must use the ids the export already has. */
    @Test
    void realModeTasksUseTheExportsOwnStatusesAndTypes() throws Exception {
        ExportEnvelope input = ExportFiles.read(java.nio.file.Path.of("src/test/resources/fixtures/mini-export.json"));
        ExportEnvelope env = SeedGenerator.generate(input, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        Set<String> statuses = ids(input, "task_statuses");
        Set<String> types = ids(input, "task_types");
        assertFalse(env.rows("tasks").isEmpty());
        for (var t : env.rows("tasks")) {
            assertTrue(statuses.contains(t.get("task_status_id")), "task " + t.get("key") + " uses an invented status");
            assertTrue(types.contains(t.get("task_type_id")), "task " + t.get("key") + " uses an invented type");
        }
    }
```

(`assertThrows` from `org.junit.jupiter.api.Assertions`.) Run `mvn -B -q -pl forecast-core test -Dtest=SeedGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: both fail (the first because nothing is thrown, the second because the fixture is incomplete and ids are substituted).

- [ ] **Step 3: `Reference` reports every missing name**

In `Reference.from`, replace the two loops that throw at the first missing name with one collection:

```java
        List<String> missingStatuses = STATUSES.stream().filter(s -> !statuses.containsKey(s)).toList();
        List<String> missingTypes = TYPES.stream().filter(t -> !types.containsKey(t)).toList();
        if (!missingStatuses.isEmpty() || !missingTypes.isEmpty()) {
            throw new IllegalArgumentException(describe(missingStatuses, missingTypes));
        }
        return new Reference(statuses, types);
```

with

```java
    private static String describe(List<String> missingStatuses, List<String> missingTypes) {
        List<String> parts = new ArrayList<>();
        if (!missingStatuses.isEmpty()) {
            parts.add("task_statuses lacks " + missingStatuses.stream().map(s -> "'" + s + "'").collect(java.util.stream.Collectors.joining(", ")));
        }
        if (!missingTypes.isEmpty()) {
            parts.add("task_types lacks " + missingTypes.stream().map(t -> "'" + t + "'").collect(java.util.stream.Collectors.joining(", ")));
        }
        return String.join("; ", parts);
    }

    /** What the rows lack, in the words of {@link #from}'s exception, or the empty string when they cover everything. */
    public static String missing(List<LinkedHashMap<String, Object>> statusRows, List<LinkedHashMap<String, Object>> typeRows) {
        try {
            from(statusRows, typeRows);
            return "";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }
```

`covers` stays as it is. If `ReferenceTest` pins the old single-name message (`grep -n "lacks" src/test/java/com/workloadhub/forecast/seed/ReferenceTest.java`), update its expected text to the new form (`task_statuses lacks 'X'` still appears verbatim for a single missing status, so most assertions hold unchanged).

- [ ] **Step 4: `SeedGenerator` refuses in real mode**

Replace

```java
            if (!Reference.covers(statuses, types)) {
                // a partial export (tests, early installs): use the reference rows instead
                statuses = ReferenceData.statusRows();
                types = ReferenceData.typeRows();
            }
```

with

```java
            if (!Reference.covers(statuses, types)) {
                if (!synthetic) {
                    // Real mode writes neither task_statuses nor task_types (design 2026-09-17, section 7.1), so a
                    // substitute would point every task at rows the database does not have: refuse instead. A real
                    // export always carries them all, because the application defines them.
                    throw new IllegalArgumentException("real mode needs an export that carries every task status and type the seed writes: "
                            + Reference.missing(statuses, types));
                }
                // a partial export in synthetic mode (tests, early installs): the synthetic envelope writes both tables itself
                statuses = ReferenceData.statusRows();
                types = ReferenceData.typeRows();
            }
```

- [ ] **Step 5: Complete the fixture**

In `server/forecast-core/src/test/resources/fixtures/mini-export.json`, `task_statuses` currently holds `To Do` (id `60000000-0000-0000-0000-000000000001`) and `In Progress` (id `...0002`); add the other seven statuses of `ReferenceData.STATUSES` (`Open`, `In Review`, `Testing`, `Blocked`, `On Hold`, `Done`, `Closed`) as rows of the same shape (`id`, `name`, `active`, `category`, `sort_order`, `description`, `created_at`, `updated_at`), ids `60000000-0000-0000-0000-000000000003` to `...0009` in that order, `category` and `sort_order` copied from `ReferenceData.STATUSES`, `active` true, `description` null, the same two timestamps as the existing rows. `task_types` holds `Task` (id `70000000-0000-0000-0000-000000000001`); add the other eleven of `ReferenceData.TYPES` (`Story`, `Bug`, `Epic`, `Improvement`, `New Feature`, `Change Request`, `Incident`, `Risk`, `Spike`, `Test`, `Sub-task`), ids `...0002` to `...0012` in that order, same shape as the existing row (`icon`, `description`, `subtask_type_id` null). Keep the file's indentation and LF endings; `python3 -m json.tool` must parse it.

Then `grep -rn 'task_statuses\|task_types' src/test/java --include=*.java | grep -i 'size()\|count'` and check that no test pins the fixture's old counts (two and one); if one does, update it to nine and twelve.

- [ ] **Step 6: Prove it**

`mvn -B -q -pl forecast-core test -Dtest='SeedGeneratorTest,ReferenceTest,SqlExportWriterTest,RoundTripTest,ExportImporterTest,ExportFilesTest,ExperimentFlowTest' -Dsurefire.failIfNoSpecifiedTests=false` passes, including `aFiveTableScriptLandsTwiceOnPostgresql`.

```bash
cp="$(bash tools/core-classpath.sh)" && java --class-path "$cp" tools/Experiment.java seed --synthetic --users 36 --weeks 30 --end 2026-09-06 --seed 11 --out /tmp/seed-2c-after.json && sha256sum /tmp/seed-2c-before.json /tmp/seed-2c-after.json
```

Expected: equal hashes.

- [ ] **Step 7: Gate**

`rm -rf forecast-core/target/surefire-reports && mvn -B -q verify` from `server/`; expected exit 0 and, in `TEST-*.xml`, 0 failures and 0 errors. If a third pre-existing PostgreSQL failure appears, do not fix it: report it with its message under concerns.

- [ ] **Step 8: Commit**

```bash
git add -A server/forecast-core
git commit -m "Refuse a real-mode export that lacks a task status or type

Real mode writes neither task_statuses nor task_types, yet an incomplete
export had its missing rows silently replaced by the seed's own, so every task
pointed at ids the database does not have; PostgreSQL's foreign key caught it
once the previous invented reference was fixed. A real export carries them all,
so real mode now refuses an incomplete one and names what is missing; synthetic
mode keeps the substitution because it writes both tables. The test fixture
gains the full nine statuses and twelve types."
```

---

### Task 3: Every database test on PostgreSQL; the gate fails without an engine

**Files:**
- Rewrite: `server/forecast-core/src/test/java/com/workloadhub/forecast/store/DatabaseTestSupport.java`
- Modify: `scripts/check.sh`, `scripts/check.ps1`
- Modify (tests): `store/JdbcRunStoreTest.java`, `store/JdbcNarrativeStoreTest.java`, `store/ForecastMigrationsTest.java`, `store/JdbcGitHubTokenStoreTest.java`, `store/DialectTest.java`, `store/SchemaFilesTest.java`, `data/ForecastRepositoryTest.java`, `data/ExportImporterTest.java`, `data/RoundTripTest.java`, `data/SqlExportWriterTest.java`, `ForecastAutoConfigurationTest.java`, `samplehost/SampleHostApplication.java`, `testing/SeededData.java`, `seed/SeedGeneratorTest.java`

**Interfaces:**
- Produces: `DatabaseTestSupport.postgres()` (a fresh empty database, throws `IllegalStateException` without an engine), `postgresWithSchema()` (plus the 24 WorkloadHub tables), `postgresMigrated()` (plus the module's tables). `sqliteInMemory()` and `postgresOrSkip()` no longer exist.

- [ ] **Step 1: Rewrite `DatabaseTestSupport`**

```java
package com.workloadhub.forecast.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The one PostgreSQL container of this JVM and a fresh database on it per call. There is no other engine:
 * without a reachable Docker or podman the gate fails, it never skips (spec 2026-09-17, ruling G).
 */
public final class DatabaseTestSupport {

    /** The same major as the host's database and the schema dump; scripts/postgres.sh reads the same tag. */
    static final String IMAGE = "postgres:18-alpine";

    private static PostgreSQLContainer<?> postgres;

    private DatabaseTestSupport() {
    }

    /** A new, empty database on the shared container. */
    public static synchronized DataSource postgres() {
        if (postgres == null) {
            boolean reachable;
            try {
                reachable = DockerClientFactory.instance().isDockerAvailable();
            } catch (Throwable t) {
                reachable = false;
            }
            if (!reachable) {
                throw new IllegalStateException("no container engine is reachable: the gate needs Docker or podman"
                        + " (server/README.md, \"The development container\")");
            }
            postgres = new PostgreSQLContainer<>(IMAGE);
            postgres.start();
        }
        String db = "t_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + db);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1"));
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        ds.setCurrentSchema("task_service,public");
        return ds;
    }

    /** A new database holding schema task_service and the 24 WorkloadHub tables, nothing else. */
    public static DataSource postgresWithSchema() {
        DataSource ds = postgres();
        WorkloadHubSchema.createPostgresql(ds);
        return ds;
    }

    /** A new database with the WorkloadHub tables and the module's own tables. */
    public static DataSource postgresMigrated() {
        DataSource ds = postgresWithSchema();
        ForecastMigrations.run(ds);
        return ds;
    }
}
```

- [ ] **Step 2: Switch the store tests**

`JdbcRunStoreTest`: delete `sqlite()`; the ten `@Test` wrapper methods (five scenarios, each once per engine) become five, one per scenario:

```java
    @Test
    void lifecycle() {
        lifecycle(DatabaseTestSupport.postgresMigrated());
    }

    @Test
    void aLaterRunOverwritesOnlyTheDaysItCovers() {
        aLaterRunOverwritesOnlyTheDaysItCovers(DatabaseTestSupport.postgresMigrated());
    }

    @Test
    void tiedCreatedAtOrdersByIdDescending() {
        tiedCreatedAtOrdersByIdDescending(DatabaseTestSupport.postgresMigrated());
    }

    @Test
    void failInterruptedMarksQueuedAndRunningRowsOnly() {
        failInterruptedMarksQueuedAndRunningRowsOnly(DatabaseTestSupport.postgresMigrated());
    }

    @Test
    void runDaysJoinTheRunDayOfEveryDoneRunInTheRange() {
        runDaysJoinTheRunDayOfEveryDoneRunInTheRange(DatabaseTestSupport.postgresMigrated());
    }
```

(a `@Test` method and the scenario method it calls share a name but not a parameter list, which Java allows; keep the scenario methods' `DataSource` parameter). Delete the `WorkloadHubSchema` import if unused.

`JdbcNarrativeStoreTest`: delete `sqlite()` and `sqliteLifecycle`; `postgresLifecycle` becomes `@Test void lifecycle() { lifecycle(DatabaseTestSupport.postgresMigrated()); }`.

`ForecastMigrationsTest`: delete the two `Sqlite` tests; `v3AppliesOnADatabaseThatRanV1AndV2Postgresql` uses `DatabaseTestSupport.postgresWithSchema()`, `migratesPostgresql` too (drop the `createPostgresql` lines they carried).

`JdbcGitHubTokenStoreTest`: `sqliteWithFixture()` becomes `postgresWithFixture()`:

```java
    static DataSource postgresWithFixture() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        new ExportImporter(ds).importAll(ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json")), true);
        ForecastMigrations.run(ds);
        return ds;
    }
```

`DialectTest`: delete every test method whose body calls `DatabaseTestSupport.sqliteInMemory()` (find them with `grep -n sqliteInMemory`); a test that opens PostgreSQL with `postgresOrSkip()` calls `postgres()` instead. If nothing is left but the placeholder tests, keep those.

`SchemaFilesTest`: delete the test that calls `WorkloadHubSchema.createSqlite` (line 74 as of `aafc3ea`); the one at line 80 uses `DatabaseTestSupport.postgres()` then `createPostgresql`.

- [ ] **Step 3: Switch the data, configuration and sample-host tests**

Mechanical rule for every remaining file: a pair `DatabaseTestSupport.sqliteInMemory()` + `WorkloadHubSchema.createSqlite(ds)` becomes one call `DatabaseTestSupport.postgresWithSchema()`; a pair `postgresOrSkip()` + `createPostgresql(ds)` becomes `postgresWithSchema()`; a bare `postgresOrSkip()` becomes `postgres()`; a triple that also runs `ForecastMigrations.run(ds)` becomes `postgresMigrated()`.

Apply it in `ForecastRepositoryTest` (two sites), `ExportImporterTest`, `RoundTripTest`, `SqlExportWriterTest`, `SeedGeneratorTest.realModeOfTheFixtureImportsIntoSqlite` (rename it `realModeOfTheFixtureImports`), `ForecastAutoConfigurationTest` (six `sqliteInMemory` sites plus the static `DS`, which becomes `static final DataSource DS = DatabaseTestSupport.postgresWithSchema();` and its test drops its own `createSqlite` line; the assertion `assertEquals(Dialect.SQLITE, ctx.getBean(Dialect.class))` becomes `assertEquals(Dialect.POSTGRESQL, ...)`), `SampleHostApplication` (`DataSource ds = DatabaseTestSupport.postgresWithSchema();` then the importer line), `SeededData.dataSource()` (`dataSource = DatabaseTestSupport.postgresWithSchema();` then the importer line).

Then `grep -rn 'sqliteInMemory\|postgresOrSkip\|createSqlite' forecast-core/src/test` must print nothing.

- [ ] **Step 4: The engine pre-check in the two gate scripts**

`scripts/check.sh`: before the `if command -v mvn` block insert

```bash
# The PostgreSQL tests are the database tests: without an engine they fail, they do not skip, so say so at
# the door instead of seventeen minutes in. Inside the development box the engine is the mounted socket.
engine_reachable() {
    local sock="${DOCKER_HOST#unix://}"
    if [ -n "${DOCKER_HOST:-}" ] && [ -S "$sock" ]; then return 0; fi
    if [ -S /var/run/docker.sock ]; then return 0; fi
    if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then return 0; fi
    if command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1; then return 0; fi
    return 1
}
if ! engine_reachable; then
    echo "no container engine is reachable: the gate needs Docker or podman (server/README.md, The development container)" >&2
    exit 1
fi
```

and change the header comment's `A step whose tool is missing is skipped with a message.` to `A step whose tool is missing is skipped with a message; a missing container engine fails the gate.`

`scripts/check.ps1`: after `$server = Join-Path $root "server"` insert

```powershell
# The database tests need an engine and fail without one; refuse at the door rather than after the build.
$engine = $false
foreach ($cli in @("docker", "podman")) {
    if (Get-Command $cli -ErrorAction SilentlyContinue) {
        & $cli info *> $null
        if ($LASTEXITCODE -eq 0) { $engine = $true; break }
    }
}
if (-not $engine) {
    Write-Host "no container engine is reachable: the gate needs Docker or podman (server/README.md, The development container)" -ForegroundColor Red
    exit 1
}
```

- [ ] **Step 5: Gate**

`rm -rf forecast-core/target/surefire-reports && mvn -B -q verify` from `server/`; exit 0. In `TEST-*.xml` the `skipped` attribute of every suite is 0 (the count that used to be 14 without Docker and 1 with it).

Then prove ruling G once: `DOCKER_HOST=unix:///nonexistent TESTCONTAINERS_RYUK_DISABLED=true mvn -B -q -pl forecast-core test -Dtest=JdbcNarrativeStoreTest -Dsurefire.failIfNoSpecifiedTests=false`; expected: a failure whose message starts with `no container engine is reachable`. Unset the variable afterwards.

- [ ] **Step 6: Commit**

```bash
git add -A server/forecast-core scripts/check.sh scripts/check.ps1
git commit -m "Run every database test on PostgreSQL and fail the gate without an engine

The host's database is PostgreSQL and SQLite was only a local convenience, so
the tests stop opening one: DatabaseTestSupport.postgres() gives a fresh
database on the JVM's one container, and a missing engine is an error, not an
assumption that skips most of the suite (spec 2026-09-17, section 5.1). The two
gate scripts check for the engine first."
```

---

### Task 4: One final `V1` migration

**Files:**
- Rewrite: `server/forecast-core/src/main/resources/db/forecast/postgresql/V1__forecast_tables.sql`
- Delete: `server/forecast-core/src/main/resources/db/forecast/postgresql/V2__narratives_with_status.sql`, `V3__windows_and_days.sql`, `V4__weekly_hours.sql`, `V5__pressure_facts.sql`, and the whole `server/forecast-core/src/main/resources/db/forecast/sqlite/` directory
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/store/ForecastMigrations.java`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/store/ForecastMigrationsTest.java`

**Interfaces:**
- Produces: `ForecastMigrations.run(DataSource)` only; the package-private `run(DataSource, String targetVersion)` is gone.

- [ ] **Step 1: Write the test first**

Replace `ForecastMigrationsTest` with:

```java
package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ForecastMigrationsTest {

    static TreeSet<String> tables(DataSource ds) throws Exception {
        TreeSet<String> out = new TreeSet<>();
        try (Connection c = ds.getConnection();
                ResultSet rs = c.getMetaData().getTables(null, null, "forecast_%", new String[] {"TABLE"})) {
            while (rs.next()) {
                out.add(rs.getString("TABLE_NAME"));
            }
        }
        return out;
    }

    static boolean hasColumn(DataSource ds, String table, String column) throws Exception {
        try (Connection c = ds.getConnection(); ResultSet rs = c.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    /** One migration creates the six module tables, the history table and the two users columns; running it twice is a no-op. */
    @Test
    void migratesAFreshDatabaseInOneStep() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        ForecastMigrations.run(ds);
        ForecastMigrations.run(ds);
        assertEquals(new TreeSet<>(List.of("forecast_current_days", "forecast_facts", "forecast_member_days", "forecast_member_windows",
                "forecast_narratives", "forecast_runs", "forecast_schema_history")), tables(ds));
        assertTrue(hasColumn(ds, "users", "github_token"));
        assertTrue(hasColumn(ds, "users", "github_token_updated_at"));
        assertTrue(hasColumn(ds, "forecast_runs", "mae"));
        assertFalse(hasColumn(ds, "forecast_runs", "champion_model"), "the tournament columns never existed in the final V1");
        assertTrue(hasColumn(ds, "forecast_narratives", "status"));
        assertTrue(hasColumn(ds, "forecast_narratives", "raw_text"));
        assertTrue(hasColumn(ds, "forecast_narratives", "tool_calls"));
        assertTrue(hasColumn(ds, "forecast_member_windows", "backlog_excess_hrs"));
        assertTrue(hasColumn(ds, "forecast_member_windows", "due_excess_hrs"));
        assertTrue(hasColumn(ds, "forecast_member_days", "working_day"));
        assertTrue(hasColumn(ds, "forecast_current_days", "forecast_at"));
        for (String table : List.of("forecast_member_windows", "forecast_member_days", "forecast_current_days")) {
            assertFalse(hasColumn(ds, table, "open_hrs"), table + " never had the open/new/planned split");
        }
        try (Connection c = ds.getConnection(); ResultSet rs = c.createStatement().executeQuery(
                "SELECT COUNT(*) FROM forecast_schema_history WHERE version IS NOT NULL")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "exactly one versioned migration applied");
        }
    }
}
```

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=ForecastMigrationsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL on the last assertion (five versioned rows today).

- [ ] **Step 2: The final `V1`**

Replace `V1__forecast_tables.sql` with exactly the SQL of spec section 3.1 (the six `CREATE TABLE`s, the three indexes and the two `ALTER TABLE users ADD COLUMN`s, in that order). Then:

```bash
git rm server/forecast-core/src/main/resources/db/forecast/postgresql/V2__narratives_with_status.sql \
       server/forecast-core/src/main/resources/db/forecast/postgresql/V3__windows_and_days.sql \
       server/forecast-core/src/main/resources/db/forecast/postgresql/V4__weekly_hours.sql \
       server/forecast-core/src/main/resources/db/forecast/postgresql/V5__pressure_facts.sql
git rm -r server/forecast-core/src/main/resources/db/forecast/sqlite
```

- [ ] **Step 3: `ForecastMigrations` without the version seam and without the SQLite location**

```java
package com.workloadhub.forecast.store;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Creates the module's own tables, with a history table the host's migrations never see. */
public final class ForecastMigrations {

    public static final String HISTORY_TABLE = "forecast_schema_history";
    static final String LOCATION = "classpath:db/forecast/postgresql";

    private ForecastMigrations() {
    }

    public static void run(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(LOCATION)
                .table(HISTORY_TABLE)
                // The module's tables land in the host's own schema, which already holds the WorkloadHub
                // tables before this ever runs; baseline that pre-existing state at version 0 so V1 (the
                // module's one migration) still applies on top of it.
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .validateOnMigrate(true)
                .schemas("task_service")
                .defaultSchema("task_service")
                .load()
                .migrate();
    }
}
```

`Dialect.flywayLocation()` now has no caller; leave it for task 5, which deletes the class.

- [ ] **Step 4: Run the test, then the gate**

`mvn -B -q -pl forecast-core test -Dtest=ForecastMigrationsTest -Dsurefire.failIfNoSpecifiedTests=false` passes; then `rm -rf forecast-core/target/surefire-reports && mvn -B -q verify` from `server/` exits 0.

- [ ] **Step 5: Commit**

```bash
git add -A server/forecast-core
git commit -m "Collapse the five migrations into one final V1 for PostgreSQL

A new database with newly seeded data replaces every existing one, so the
history of V1 to V5 has nothing left to preserve: V1 now creates the end state
in one step, with no column added only to be dropped and no DEFAULT that existed
to make ADD COLUMN NOT NULL possible. The SQLite twins go with it (spec
2026-09-17, section 3.1)."
```

---

### Task 5: `Dialect` out; the module, the driver and the sample host on PostgreSQL types

**Files:**
- Delete: `server/forecast-core/src/main/java/com/workloadhub/forecast/store/Dialect.java`, `server/forecast-core/src/main/resources/schema/workloadhub-sqlite.sql`, test `store/DialectTest.java`, test `store/SchemaFilesTest.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/store/JdbcValues.java`
- Rewrite: `store/JdbcRunStore.java`, `store/JdbcNarrativeStore.java`, `store/JdbcGitHubTokenStore.java`, `data/ForecastRepository.java`
- Modify: `service/DefaultForecastService.java`, `ForecastAutoConfiguration.java`, `store/WorkloadHubSchema.java`, `data/ExportImporter.java`, `data/ExportExporter.java`, `server/tools/Experiment.java`, `server/examples/HostExample.java`, `server/tools/translate-schema.py`, `server/forecast-core/pom.xml`, `server/pom.xml`
- Test: `store/JdbcRunStoreTest.java` (one `Dialect` use), `store/JdbcNarrativeStoreTest.java`, `store/JdbcGitHubTokenStoreTest.java`, `data/ForecastRepositoryTest.java`, `ForecastAutoConfigurationTest.java`, `service/DefaultForecastServiceTest.java`, `samplehost/ForecastAccess.java`, `samplehost/JavaHostIntegrationTest.java`, `testing/SeededData.java`, `ExperimentFlowTest.java`

**Interfaces:**
- Produces: `new JdbcRunStore(DataSource)`, `new JdbcNarrativeStore(DataSource)`, `new JdbcGitHubTokenStore(JdbcClient, AesGcmCipher)`, `new ForecastRepository(JdbcClient)`, `new DefaultForecastService(dataSource, runner, store, progress, threads, tokens, narratives, narrator, gateway, clock)`, `new ForecastAccess(JdbcClient)`; `JdbcValues.micros(LocalDateTime)`; `WorkloadHubSchema.createPostgresql(DataSource)` (no `createSqlite`), `WorkloadHubSchema.readResource(String)` public; the driver's `--url`, `--user`, `--password` options and `init-db [--force]` on a schema.
- Every `UUID`, `LocalDate`, `LocalDateTime` and `Boolean` is bound as itself and read back with `ResultSet.getObject(column, Type.class)`.

- [ ] **Step 1: `JdbcValues`**

```java
package com.workloadhub.forecast.store;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** The one thing every store still normalises by hand: a timestamp bound at the precision PostgreSQL keeps. */
final class JdbcValues {

    private JdbcValues() {
    }

    /** PostgreSQL's timestamp holds microseconds; truncate before binding so what is read back equals what was written. */
    static LocalDateTime micros(LocalDateTime t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.MICROS);
    }
}
```

- [ ] **Step 2: `JdbcRunStore` on real types**

Replace the file with:

```java
package com.workloadhub.forecast.store;

import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.MemberDayForecast;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.api.RunSummary;
import com.workloadhub.forecast.eval.RunDayForecast;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The module's own tables: one row per run, the window and day tables, the current forecast and the facts. */
public final class JdbcRunStore {

    public static final UUID NIL = new UUID(0L, 0L);
    /** The error a run left behind by a restart carries (design 2026-09-11, section 4.2). */
    public static final String INTERRUPTED = "interrupted by a restart";
    static final int ERROR_MAX = 500;

    private static final String RUN_COLUMNS = "id, team_id, requested_by, as_of, status, mae, error, created_at, finished_at";
    private static final String WINDOW_COLUMNS = "user_id, window_index, window_start, window_end, demand_hrs, low_hrs, high_hrs,"
            + " capacity_hrs, overload_hrs, working_days, absence_hrs, backlog_excess_hrs, due_excess_hrs";
    private static final String DAY_COLUMNS = "user_id, day, window_index, demand_hrs, capacity_hrs, overload_hrs, working_day";

    private static final RowMapper<RunSummary> SUMMARY = (rs, i) -> new RunSummary(rs.getObject("id", UUID.class),
            rs.getObject("team_id", UUID.class), rs.getObject("requested_by", UUID.class), rs.getObject("as_of", LocalDate.class),
            RunStatus.valueOf(rs.getString("status")), rs.getObject("mae", Double.class), rs.getString("error"),
            rs.getObject("created_at", LocalDateTime.class), rs.getObject("finished_at", LocalDateTime.class));
    private static final RowMapper<MemberWindowForecast> WINDOW = (rs, i) -> new MemberWindowForecast(rs.getObject("user_id", UUID.class),
            rs.getInt("window_index"), rs.getObject("window_start", LocalDate.class), rs.getObject("window_end", LocalDate.class),
            rs.getDouble("demand_hrs"), rs.getDouble("low_hrs"), rs.getDouble("high_hrs"), rs.getDouble("capacity_hrs"),
            rs.getDouble("overload_hrs"), rs.getInt("working_days"), rs.getDouble("absence_hrs"), rs.getDouble("backlog_excess_hrs"),
            rs.getDouble("due_excess_hrs"));
    private static final RowMapper<MemberDayForecast> DAY = (rs, i) -> new MemberDayForecast(rs.getObject("user_id", UUID.class),
            rs.getObject("day", LocalDate.class), rs.getInt("window_index"), rs.getDouble("demand_hrs"), rs.getDouble("capacity_hrs"),
            rs.getDouble("overload_hrs"), rs.getBoolean("working_day"));

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;

    public JdbcRunStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public UUID create(RunRequest request, LocalDate asOf, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO forecast_runs (id, team_id, requested_by, as_of, status, created_at) VALUES (?, ?, ?, ?, ?, ?)")
                .param(id).param(request.teamId()).param(request.requestedBy() == null ? NIL : request.requestedBy())
                .param(asOf).param(RunStatus.QUEUED.name()).param(JdbcValues.micros(createdAt))
                .update();
        return id;
    }

    public void markRunning(UUID runId) {
        jdbc.sql("UPDATE forecast_runs SET status = ? WHERE id = ?").param(RunStatus.RUNNING.name()).param(runId).update();
    }

    public void fail(UUID runId, String error, LocalDateTime finishedAt) {
        String line = error == null ? "" : error.strip().lines().findFirst().orElse("");
        if (line.length() > ERROR_MAX) {
            line = line.substring(0, ERROR_MAX);
        }
        jdbc.sql("UPDATE forecast_runs SET status = ?, error = ?, finished_at = ? WHERE id = ?")
                .param(RunStatus.FAILED.name()).param(line).param(JdbcValues.micros(finishedAt)).param(runId).update();
    }

    /** After a restart nothing can still be running a QUEUED or RUNNING row (design 2026-09-11, section 4.2): fail them all, return how many. */
    public int failInterrupted(LocalDateTime now) {
        return jdbc.sql("UPDATE forecast_runs SET status = ?, error = ?, finished_at = ? WHERE status IN (?, ?)")
                .param(RunStatus.FAILED.name()).param(INTERRUPTED).param(JdbcValues.micros(now))
                .param(RunStatus.QUEUED.name()).param(RunStatus.RUNNING.name()).update();
    }

    /** {@code mae} is {@code null}, not NaN, when the run had no scored backtest origin. */
    public void finish(UUID runId, Double mae, String backtestJson, List<MemberWindowForecast> windows, List<MemberDayForecast> days, String factsJson,
            LocalDateTime finishedAt) {
        LocalDateTime at = JdbcValues.micros(finishedAt);
        tx.executeWithoutResult(status -> {
            jdbc.sql("UPDATE forecast_runs SET status = ?, mae = ?, backtest_json = ?, finished_at = ? WHERE id = ?")
                    .param(RunStatus.DONE.name()).param(mae == null || mae.isNaN() ? null : mae).param(backtestJson)
                    .param(at).param(runId).update();
            jdbcTemplate.batchUpdate("INSERT INTO forecast_member_windows (run_id, " + WINDOW_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    windows.stream().map(r -> new Object[] {runId, r.userId(), r.windowIndex(), r.windowStart(), r.windowEnd(), r.demandHrs(),
                            r.lowHrs(), r.highHrs(), r.capacityHrs(), r.overloadHrs(), r.workingDays(), r.absenceHrs(), r.backlogExcessHrs(),
                            r.dueExcessHrs()}).toList());
            jdbcTemplate.batchUpdate("INSERT INTO forecast_member_days (run_id, " + DAY_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    days.stream().map(r -> new Object[] {runId, r.userId(), r.day(), r.windowIndex(), r.demandHrs(), r.capacityHrs(),
                            r.overloadHrs(), r.workingDay()}).toList());
            UUID teamId = jdbc.sql("SELECT team_id FROM forecast_runs WHERE id = ?").param(runId).query(UUID.class).single();
            // Every day of the run overwrites the team's current forecast for that member and day (all of them lie after the run day).
            jdbcTemplate.batchUpdate("INSERT INTO forecast_current_days (team_id, user_id, day, run_id, demand_hrs, capacity_hrs,"
                    + " overload_hrs, forecast_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (team_id, user_id, day) DO UPDATE SET"
                    + " run_id = excluded.run_id, demand_hrs = excluded.demand_hrs, capacity_hrs = excluded.capacity_hrs,"
                    + " overload_hrs = excluded.overload_hrs, forecast_at = excluded.forecast_at",
                    days.stream().map(r -> new Object[] {teamId, r.userId(), r.day(), runId, r.demandHrs(), r.capacityHrs(), r.overloadHrs(), at})
                            .toList());
            jdbc.sql("INSERT INTO forecast_facts (run_id, facts_json, created_at) VALUES (?, ?, ?)")
                    .param(runId).param(factsJson).param(at).update();
        });
    }

    public Optional<RunSummary> find(UUID runId) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM forecast_runs WHERE id = ?").param(runId).query(SUMMARY).optional();
    }

    public List<RunSummary> list(UUID teamId, int limit) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM forecast_runs WHERE team_id = ? ORDER BY created_at DESC, id DESC LIMIT " + Math.max(1, limit))
                .param(teamId).query(SUMMARY).list();
    }

    /** PostgreSQL orders uuid by its bytes, which is the order of its canonical text and of {@code Ids.UUID_ORDER}: no re-sort here. */
    public List<MemberWindowForecast> memberWindows(UUID runId) {
        return jdbc.sql("SELECT " + WINDOW_COLUMNS + " FROM forecast_member_windows WHERE run_id = ? ORDER BY user_id, window_index")
                .param(runId).query(WINDOW).list();
    }

    public List<MemberDayForecast> memberDays(UUID runId) {
        return jdbc.sql("SELECT " + DAY_COLUMNS + " FROM forecast_member_days WHERE run_id = ? ORDER BY user_id, day")
                .param(runId).query(DAY).list();
    }

    /** The team's current forecast between two days inclusive, by member then day. */
    public List<CurrentDayForecast> currentDays(UUID teamId, LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT team_id, user_id, day, run_id, demand_hrs, capacity_hrs, overload_hrs, forecast_at FROM forecast_current_days"
                + " WHERE team_id = ? AND day >= ? AND day <= ? ORDER BY user_id, day")
                .param(teamId).param(from).param(to)
                .query((rs, i) -> new CurrentDayForecast(rs.getObject("team_id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("day", LocalDate.class), rs.getObject("run_id", UUID.class), rs.getDouble("demand_hrs"),
                        rs.getDouble("capacity_hrs"), rs.getDouble("overload_hrs"), rs.getObject("forecast_at", LocalDateTime.class)))
                .list();
    }

    /** Every day row of the team's DONE runs between two days inclusive, with the run day it was made on; by run, member, day. */
    public List<RunDayForecast> runDays(UUID teamId, LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT d.run_id, r.as_of, d.user_id, d.day, d.window_index, d.demand_hrs, d.capacity_hrs, d.overload_hrs, d.working_day"
                + " FROM forecast_member_days d JOIN forecast_runs r ON r.id = d.run_id"
                + " WHERE r.team_id = ? AND r.status = ? AND d.day >= ? AND d.day <= ? ORDER BY d.run_id, d.user_id, d.day")
                .param(teamId).param(RunStatus.DONE.name()).param(from).param(to)
                .query((rs, i) -> new RunDayForecast(rs.getObject("run_id", UUID.class), rs.getObject("as_of", LocalDate.class), DAY.mapRow(rs, i)))
                .list();
    }

    public Optional<String> facts(UUID runId) {
        return jdbc.sql("SELECT facts_json FROM forecast_facts WHERE run_id = ?").param(runId).query(String.class).optional();
    }

    public Optional<String> backtestJson(UUID runId) {
        return jdbc.sql("SELECT backtest_json FROM forecast_runs WHERE id = ?").param(runId).query(String.class).optional()
                .filter(s -> s != null);
    }
}
```

Note on `backtestJson`: `query(String.class).optional()` on a row whose column is NULL yields an `Optional` of null in some Spring versions; the `.filter` keeps the old behaviour (empty when null). `facts_json` is `NOT NULL`, so `facts` needs none.

- [ ] **Step 3: `JdbcNarrativeStore` and `JdbcGitHubTokenStore`**

`JdbcNarrativeStore.java`:

```java
package com.workloadhub.forecast.store;

import com.workloadhub.forecast.ai.NarrationOutcome;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.NarrativeStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** One row per narration in forecast_narratives, whatever its status. */
public final class JdbcNarrativeStore {

    private static final String COLUMNS = "id, run_id, language, status, model, narrative_json, raw_text, verification_json, usage_json, error,"
            + " attempts, tool_calls, created_at";
    private static final RowMapper<NarrativeResult> ROW = (rs, i) -> new NarrativeResult(rs.getObject("id", UUID.class),
            rs.getObject("run_id", UUID.class), rs.getString("language"), NarrativeStatus.valueOf(rs.getString("status")), rs.getString("model"),
            rs.getString("narrative_json"), rs.getString("raw_text"), rs.getString("verification_json"), rs.getString("usage_json"),
            rs.getString("error"), rs.getInt("attempts"), rs.getInt("tool_calls"), rs.getObject("created_at", LocalDateTime.class));

    private final JdbcClient jdbc;

    public JdbcNarrativeStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public NarrativeResult save(UUID runId, String language, NarrationOutcome outcome, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO forecast_narratives (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .param(id).param(runId).param(language).param(outcome.status().name()).param(outcome.model())
                .param(outcome.narrativeJson()).param(outcome.rawText()).param(outcome.verificationJson()).param(outcome.usageJson())
                .param(outcome.error()).param(outcome.attempts()).param(outcome.toolCalls().size()).param(JdbcValues.micros(createdAt))
                .update();
        return find(id).orElseThrow();
    }

    public Optional<NarrativeResult> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM forecast_narratives WHERE id = ?").param(id).query(ROW).optional();
    }

    /** The newest narration of that language for the run, whatever its status. */
    public Optional<NarrativeResult> latest(UUID runId, String language) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM forecast_narratives WHERE run_id = ? AND language = ? ORDER BY created_at DESC, id DESC LIMIT 1")
                .param(runId).param(language).query(ROW).optional();
    }
}
```

`JdbcGitHubTokenStore.java`: constructor `JdbcGitHubTokenStore(JdbcClient jdbc, AesGcmCipher cipher)`; delete the `dialect` field and `idPlaceholder()`; the three statements become

```java
        int updated = jdbc.sql("UPDATE users SET github_token = ?, github_token_updated_at = ? WHERE id = ?")
                .param(c.encrypt(t)).param(LocalDateTime.now().withNano(0)).param(userId).update();
```

```java
        return jdbc.sql("SELECT github_token FROM users WHERE id = ?").param(userId).query(String.class).optional()
                .filter(v -> v != null && !v.isBlank());
```

```java
        jdbc.sql("UPDATE users SET github_token = NULL, github_token_updated_at = NULL WHERE id = ?").param(userId).update();
```

- [ ] **Step 4: `ForecastRepository` on real types**

Replace the file with:

```java
package com.workloadhub.forecast.data;

import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.LeaveRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.data.rows.TransitionRow;
import com.workloadhub.forecast.data.rows.UserRef;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads the WorkloadHub tables into typed rows. Read only. */
public final class ForecastRepository {

    private static final Set<String> COUNTED_ROLES = Set.of("MEMBER", "TEAM_LEADER");

    private final JdbcClient jdbc;

    public ForecastRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ForecastData loadAll() {
        Map<String, String> categoryByName = new TreeMap<>();
        Map<UUID, String> categoryById = new HashMap<>();
        jdbc.sql("SELECT id, name, category FROM task_statuses").query((rs, i) -> {
            categoryByName.put(rs.getString("name"), rs.getString("category"));
            categoryById.put(rs.getObject("id", UUID.class), rs.getString("category"));
            return null;
        }).list();
        Map<UUID, String> typeNameById = new HashMap<>();
        jdbc.sql("SELECT id, name FROM task_types").query((rs, i) -> typeNameById.put(rs.getObject("id", UUID.class), rs.getString("name"))).list();
        List<TeamRow> teams = jdbc.sql("SELECT id, name, manager_id, parent_team_id FROM teams")
                .query((rs, i) -> new TeamRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getObject("manager_id", UUID.class),
                        rs.getObject("parent_team_id", UUID.class)))
                .list();
        Map<UUID, TeamRow> teamById = new HashMap<>();
        teams.forEach(t -> teamById.put(t.id(), t));
        Map<UUID, List<UUID>> teamsOfUser = new HashMap<>();
        Map<UUID, LocalDate> joinedOfUser = new HashMap<>();
        jdbc.sql("SELECT team_id, user_id, joined_at FROM team_members").query((rs, i) -> {
            UUID user = rs.getObject("user_id", UUID.class);
            teamsOfUser.computeIfAbsent(user, k -> new ArrayList<>()).add(rs.getObject("team_id", UUID.class));
            joinedOfUser.merge(user, rs.getObject("joined_at", LocalDateTime.class).toLocalDate(), (a, b) -> a.isBefore(b) ? a : b);
            return null;
        }).list();
        List<UserRef> users = new ArrayList<>();
        List<MemberRow> members = new ArrayList<>();
        jdbc.sql("SELECT id, full_name, email, username, role, job_title, active, deactivated_at FROM users").query((rs, i) -> {
            UUID id = rs.getObject("id", UUID.class);
            users.add(new UserRef(id, rs.getString("full_name"), rs.getString("email"), rs.getString("username")));
            List<UUID> teamIds = teamsOfUser.getOrDefault(id, List.of());
            if (!rs.getBoolean("active") || !COUNTED_ROLES.contains(rs.getString("role")) || teamIds.isEmpty()) {
                return null;
            }
            List<UUID> sorted = teamIds.stream().sorted(Ids.UUID_ORDER).toList();
            UUID primary = sorted.stream()
                    .filter(t -> teamById.containsKey(t) && teamById.get(t).parentId() != null)
                    .findFirst().orElse(sorted.get(0));
            LocalDateTime left = rs.getObject("deactivated_at", LocalDateTime.class);
            members.add(new MemberRow(id, rs.getString("full_name"), rs.getString("email"), rs.getString("role"), rs.getString("job_title"),
                    sorted, primary, joinedOfUser.get(id), left == null ? null : left.toLocalDate()));
            return null;
        }).list();
        List<ProjectRow> projects = jdbc.sql("SELECT id, key, name, status, team_id FROM projects WHERE archived = FALSE")
                .query((rs, i) -> new ProjectRow(rs.getObject("id", UUID.class), rs.getString("key"), rs.getString("name"), rs.getString("status"),
                        rs.getObject("team_id", UUID.class)))
                .list();
        List<TaskRow> tasks = jdbc.sql("SELECT id, key, title, project_id, assignee_id, reporter_id, parent_task_id, task_type_id,"
                + " task_status_id, priority, original_estimate_hrs, remaining_estimate_hrs, created_date, started_date, finished_date,"
                + " due_date, planned_week, reopened_from_done FROM tasks WHERE archived = FALSE")
                .query((rs, i) -> {
                    LocalDate pw = rs.getObject("planned_week", LocalDate.class);
                    return new TaskRow(rs.getObject("id", UUID.class), rs.getString("key"), rs.getString("title"), rs.getObject("project_id", UUID.class),
                            rs.getObject("assignee_id", UUID.class), rs.getObject("reporter_id", UUID.class), rs.getObject("parent_task_id", UUID.class),
                            typeNameById.get(rs.getObject("task_type_id", UUID.class)), categoryById.get(rs.getObject("task_status_id", UUID.class)),
                            rs.getString("priority"), rs.getObject("original_estimate_hrs", Double.class), rs.getObject("remaining_estimate_hrs", Double.class),
                            rs.getObject("created_date", LocalDateTime.class), rs.getObject("started_date", LocalDateTime.class),
                            rs.getObject("finished_date", LocalDateTime.class), rs.getObject("due_date", LocalDate.class),
                            pw == null ? null : Weeks.mondayOf(pw), rs.getBoolean("reopened_from_done"), false);
                })
                .list();
        List<TransitionRow> transitions = jdbc.sql("SELECT task_id, user_id, field_name, old_value, new_value, changed_at FROM task_history")
                .query((rs, i) -> new TransitionRow(rs.getObject("task_id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("field_name"),
                        rs.getString("old_value"), rs.getString("new_value"), rs.getObject("changed_at", LocalDateTime.class)))
                .list();
        List<TimeLogRow> logs = jdbc.sql("SELECT task_id, user_id, log_date, hours FROM time_logs")
                .query((rs, i) -> new TimeLogRow(rs.getObject("task_id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("log_date", LocalDate.class), rs.getDouble("hours")))
                .list();
        List<LeaveRow> leaves = new ArrayList<>();
        List<LeaveRow> pendingLeaves = new ArrayList<>();
        jdbc.sql("SELECT employee_id, start_date, end_date, begin_time, end_time, absence_hours, status, leave_type"
                + " FROM personal_leaves WHERE status IN ('APPROVED', 'PENDING')").query((rs, i) -> {
                    LeaveRow row = new LeaveRow(rs.getObject("employee_id", UUID.class), rs.getObject("start_date", LocalDate.class),
                            rs.getObject("end_date", LocalDate.class), rs.getObject("begin_time", LocalTime.class), rs.getObject("end_time", LocalTime.class),
                            rs.getObject("absence_hours", Double.class), rs.getString("status"), rs.getString("leave_type"));
                    ("APPROVED".equals(row.status()) ? leaves : pendingLeaves).add(row);
                    return null;
                }).list();
        List<HolidayRow> holidays = jdbc.sql("SELECT start_date, end_date, status, active, title FROM holidays")
                .query((rs, i) -> new HolidayRow(rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                        "CONFIRMED".equals(rs.getString("status")), rs.getBoolean("active"), rs.getString("title")))
                .list();
        return new ForecastData(members, teams, projects, tasks, transitions, logs, leaves, pendingLeaves, holidays, users, categoryByName);
    }
}
```

The two behaviour differences from before are intended and equivalent: `archived` tasks are excluded in SQL instead of in Java (the row type still carries `false`), and `planned_week` is read as a date and snapped to its Monday as before.

- [ ] **Step 5: The service, the configuration, the sample-host access class, the tests**

- `DefaultForecastService`: delete the `dialect` field, its constructor parameter and its import; `requireTeam` becomes

```java
    private void requireTeam(UUID teamId) {
        boolean exists = !JdbcClient.create(dataSource).sql("SELECT id FROM teams WHERE id = ?").param(teamId).query().listOfRows().isEmpty();
        if (!exists) {
            throw ForecastException.of("TEAM_NOT_FOUND", "team " + teamId + " does not exist");
        }
    }
```

  and both `new ForecastRepository(JdbcClient.create(dataSource), dialect)` become `new ForecastRepository(JdbcClient.create(dataSource))`.
- `ForecastAutoConfiguration`: delete the `forecastDialect` bean and the `Dialect` import; `gitHubTokenStore(JdbcClient jdbc, ForecastProperties properties, ForecastMigrationsRunner migrated)` returns `new JdbcGitHubTokenStore(jdbc, cipher)`; `jdbcRunStore(DataSource dataSource, ForecastMigrationsRunner migrated)` returns `new JdbcRunStore(dataSource)`; `jdbcNarrativeStore` likewise; `forecastService(...)` drops the `Dialect` parameter and argument.
- Test side: `ForecastAccess(JdbcClient jdbc)` with every `dialect.placeholder("uuid")` replaced by `?` and every `.param(x.toString())` on a UUID by `.param(x)`; `JavaHostIntegrationTest`: `new ForecastAccess(jdbc)`, drop the `Dialect` import; `DefaultForecastServiceTest.build` and `boot`: drop every `Dialect` line, `new JdbcGitHubTokenStore(JdbcClient.create(ds), AesGcmCipher.fromBase64Key(KEY))`, `new DefaultForecastService(ds, new ForecastRunner(new CapacityRule(40), 2), runs, tracker, 1, t, new JdbcNarrativeStore(ds), new Narrator(...), g, Clock...)`, `new JdbcRunStore(ds)` at its four sites; `JdbcRunStoreTest.runDaysJoinTheRunDayOfEveryDoneRunInTheRange`: the raw update becomes `JdbcClient.create(ds).sql("UPDATE forecast_runs SET status = ? WHERE id = ?").param(RunStatus.RUNNING.name()).param(reopened).update()`, and every `new JdbcRunStore(ds, Dialect.of(ds))` becomes `new JdbcRunStore(ds)`; `JdbcNarrativeStoreTest.lifecycle`: `new JdbcRunStore(ds)`, `new JdbcNarrativeStore(ds)`; `JdbcGitHubTokenStoreTest.store`: `new JdbcGitHubTokenStore(JdbcClient.create(ds), AesGcmCipher...)` and the no-key test `new JdbcGitHubTokenStore(JdbcClient.create(ds), null)`; `ForecastRepositoryTest`: `new ForecastRepository(JdbcClient.create(ds))`; `SeededData.data()`: `new ForecastRepository(JdbcClient.create(ds))`; `ForecastAutoConfigurationTest`: delete the `Dialect` assertion line and import.
- Delete `store/DialectTest.java` and `store/SchemaFilesTest.java` (`git rm`).

- [ ] **Step 6: The importer, the exporter and the schema class on PostgreSQL only**

`WorkloadHubSchema.java`: delete `createSqlite`; make `readResource` `public static`; the `BOOLEAN_COLUMNS` javadoc becomes `/** Columns that are boolean in the schema: an export carries them as JSON booleans. */`; the `createPostgresql` javadoc and body stay.

`ExportImporter.java`: delete the `dialect` field and import; `columns()` keeps only the `task_service` schema (`if (schema != null && !schema.equals("task_service")) continue;`); add

```java
    /** A string bound into a uuid, date, time or timestamp column needs the cast; PostgreSQL will not coerce it. */
    private static String placeholder(String columnTypeName) {
        String t = columnTypeName.toLowerCase(Locale.ROOT);
        if (t.equals("uuid") || t.startsWith("timestamp") || t.equals("date") || t.startsWith("time")) {
            return "CAST(? AS " + (t.startsWith("timestamp") ? "timestamp" : t.startsWith("time") ? "time" : t) + ")";
        }
        return "?";
    }
```

  and use it in `insert` (`sql.append(i == 0 ? "" : ", ").append(placeholder(schemaColumns.get(cols.get(i))))`); in `bind`, `ps.setObject(index, dialect.bool(b))` becomes `ps.setBoolean(index, b)` and the boolean-from-number branch `ps.setBoolean(index, num.intValue() != 0)`; the class javadoc drops `on either engine`.

`ExportExporter.java`: delete the `dialect` field and import; `String db = "postgresql";`; in `jsonValue` delete the `WorkloadHubSchema.isBoolean` branch (the `Boolean` branch below it already returns the value).

`server/tools/translate-schema.py`: delete `sqlite_type`, `translate_sqlite` and the second `write_text` line; the docstring becomes `Clean the WorkloadHub PostgreSQL dump into the schema file the tools module ships: the dump without psql directives, SET lines, OWNER statements and dump banners. Run it again whenever the owner's schema changes and commit the output.`

`git rm server/forecast-core/src/main/resources/schema/workloadhub-sqlite.sql`.

- [ ] **Step 7: The driver on a PostgreSQL URL**

In `server/tools/Experiment.java`:
- Replace the `org.xerial.sqlite.SQLiteDataSource` import with `org.postgresql.ds.PGSimpleDataSource`; add `java.sql.Connection`, `java.sql.ResultSet`, `java.sql.Statement` imports.
- The `USAGE` text becomes

```text
usage: experiment.sh <command> [options]
  init-db  [--force]
           Create the schema task_service with the 24 WorkloadHub tables and the module's tables in
           the database at --url. Refuses when task_service already exists; --force drops it first.
  import   <export.json>
           Load a WorkloadHub JSON export (real or seeded), replacing existing rows.
  export   <out.json>
           Write the database's WorkloadHub tables as a JSON export.
  seed     --out FILE [--export FILE] [--synthetic] [--users N] [--weeks N]
           [--end ISO_DATE] [--seed N] [--format json|sql] [--force]
           Generate an export with weeks of realistic history, from a real export or a
           synthetic directory. Real mode (no --synthetic) needs --export and refuses to
           write inside a git repository without --force: its output holds personal data.
Connection: --url, --user, --password; else WHF_DB_URL, WHF_DB_USER, WHF_DB_PASSWORD; else
jdbc:postgresql://localhost:5432/workloadhub, workloadhub, workloadhub, which scripts/postgres.sh
creates. Run this inside the development container (bash scripts/devbox.sh shell).
```

- Every `Args.parse(rest, Set.of("db", ...), ...)` and `Set.of("db")` gains `"url", "user", "password"` in place of `"db"` (`seed` gains the three too, though it never connects, so a typo is refused the same way everywhere).
- Replace `static DataSource dataSource(Path db)` and `Args.db()` with

```java
    static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/workloadhub";

    static String setting(Args args, String option, String variable, String fallback) {
        if (args.has(option)) {
            return args.value(option);
        }
        String env = System.getenv(variable);
        return env == null || env.isBlank() ? fallback : env;
    }

    static String url(Args args) {
        return setting(args, "url", "WHF_DB_URL", DEFAULT_URL);
    }

    static DataSource dataSource(Args args) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url(args));
        ds.setUser(setting(args, "user", "WHF_DB_USER", "workloadhub"));
        ds.setPassword(setting(args, "password", "WHF_DB_PASSWORD", "workloadhub"));
        ds.setCurrentSchema("task_service,public");
        return ds;
    }
```

- `initDb` becomes

```java
    private static int initDb(Args args) throws Exception {
        args.noFiles();
        DataSource ds = dataSource(args);
        boolean exists;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT 1 FROM information_schema.schemata WHERE schema_name = 'task_service'")) {
            exists = rs.next();
        }
        if (exists) {
            if (!args.flag("force")) {
                System.err.println("schema task_service already exists in " + url(args) + "; use --force to drop and recreate it");
                return 2;
            }
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP SCHEMA task_service CASCADE");
            }
        }
        WorkloadHubSchema.createPostgresql(ds);
        ForecastMigrations.run(ds);
        System.out.println("Created schema task_service in " + url(args) + " with the WorkloadHub schema and the forecast tables");
        return 0;
    }
```

- `importExport` and `export` call `dataSource(args)` instead of `dataSource(args.db())`; the `java.nio.file.Path`-based `db` handling is gone. Delete the `Files.delete(db)` logic that came with `--force` on a file.

`ExperimentFlowTest`: the two database tests take a fresh database from the shared container and pass its coordinates:

```java
    private static String[] connection(DataSource ds) {
        PGSimpleDataSource pg = (PGSimpleDataSource) ds;
        return new String[] {"--url", pg.getUrl(), "--user", pg.getUser(), "--password", pg.getPassword()};
    }
```

  (`DatabaseTestSupport.postgres()` returns a `PGSimpleDataSource`; import `org.postgresql.ds.PGSimpleDataSource` and `com.workloadhub.forecast.store.DatabaseTestSupport`.) `initImportAndExportRoundTripAnExport` becomes

```java
    @Test
    void initImportAndExportRoundTripAnExport(@TempDir Path dir) throws Exception {
        String[] db = connection(DatabaseTestSupport.postgres());
        assertOk(experiment(concat(db, "init-db")), "Created");
        assertEquals(2, experiment(concat(db, "init-db")).exit(), "refuses to recreate the schema without --force");
        assertOk(experiment(concat(db, "init-db", "--force")), "Created");
        assertOk(experiment(concat(db, "import", FIXTURE.toString())), "Imported");
        Path out = dir.resolve("out.json");
        assertOk(experiment(concat(db, "export", out.toString())), "rows)");
        assertTrue(Files.readString(out).contains("\"CT2-CAL-1\""));
    }
```

  with `static String[] concat(String[] a, String... b)` building one array; `seedWritesJsonAndSqlThatImports` uses a second `connection(DatabaseTestSupport.postgres())` for its `init-db` and `import` calls; the negative test `assertEquals(2, experiment("eval", "--db", "x.db").exit(), ...)` becomes `experiment("eval").exit()` and `experiment("import", "--db", "x.db")` becomes `experiment("import")` (import needs a file); the driver options a child JVM sees stay the same. The child JVM connects to the container the parent started; both run on this machine.

- [ ] **Step 8: The sample host on `spring.datasource.*`**

In `server/examples/HostExample.java`:
- Delete the `dataSource` bean and the `org.xerial.sqlite.SQLiteDataSource`, `javax.sql.DataSource` and `@Value`-only imports it needed (keep `@Value` for the clock).
- The help text: `usage: run-host-example.sh [--url JDBC_URL] [--user USER] [--password PASSWORD] [--team UUID] [--as-of ISO_DATE] [--narrate] [--lang en|fr]` and the `--db` line becomes three lines: `--url       the PostgreSQL database (default WHF_DB_URL, else jdbc:postgresql://localhost:5432/workloadhub)`, `--user      (default WHF_DB_USER, else workloadhub)`, `--password  (default WHF_DB_PASSWORD, else workloadhub)`.
- In `main`, replace `String db = opts.getOrDefault("db", "/data/workloadhub.db");` and the `"example.db", db,` property with

```java
        String url = setting(opts, "url", "WHF_DB_URL", "jdbc:postgresql://localhost:5432/workloadhub");
        String user = setting(opts, "user", "WHF_DB_USER", "workloadhub");
        String password = setting(opts, "password", "WHF_DB_PASSWORD", "workloadhub");
```

  and, in the `properties(Map.of(...))` call, the entries

```java
                        // The host's own pool, built by Spring Boot from these: exactly what the server has already.
                        "spring.datasource.url", url,
                        "spring.datasource.username", user,
                        "spring.datasource.password", password,
                        // The WorkloadHub tables and the module's live in task_service.
                        "spring.datasource.hikari.data-source-properties.currentSchema", "task_service,public",
```

  (`Map.of` takes at most ten pairs; switch the call to `Map.ofEntries(Map.entry(...), ...)`) plus the helper

```java
    private static String setting(Map<String, String> opts, String option, String variable, String fallback) {
        if (opts.containsKey(option) && !opts.get(option).isEmpty()) {
            return opts.get(option);
        }
        String env = System.getenv(variable);
        return env == null || env.isBlank() ? fallback : env;
    }
```

- The class javadoc's "Real" bullet becomes: `<b>Real.</b> Nothing but the properties: the module's auto-configuration builds ForecastService and everything under it on top of the DataSource Spring Boot makes from spring.datasource.*, which the server already has. Every ForecastService call in Calls is real.` The comment `Run it inside the development container, against the seeded SQLite database` becomes `... against the local PostgreSQL scripts/postgres.sh runs`.
- `namesOf`: `names.put(UUID.fromString((String) row.get("id")), ...)` becomes `names.put((UUID) row.get("id"), ...)` and `leaderOf` binds `.param(team)` (PostgreSQL returns and takes real `UUID`s).

- [ ] **Step 9: Dependencies**

`server/pom.xml`: delete the `<sqlite.version>` property and the `sqlite-jdbc` line in `dependencyManagement`.
`server/forecast-core/pom.xml`: delete both `sqlite-jdbc` dependencies (the optional runtime one and the test one).

`grep -rni 'sqlite' server/forecast-core/src server/tools server/examples server/pom.xml server/forecast-core/pom.xml` must print nothing.

- [ ] **Step 10: Gate, and the driver by hand**

`rm -rf forecast-core/target/surefire-reports && mvn -B -q verify` from `server/`; exit 0.

The driver's hand check against a real database waits for task 9, which creates one; for now the driver is proven by its own test class: `mvn -B -q -pl forecast-core test -Dtest=ExperimentFlowTest -Dsurefire.failIfNoSpecifiedTests=false` passes.

- [ ] **Step 11: Commit**

```bash
git add -A server scripts
git commit -m "Bind real JDBC types and remove SQLite from the module

With one database engine the stores bind UUID, LocalDate, LocalDateTime and
Boolean as themselves and read them back typed, so Dialect, its cast
placeholders, the three copies of the row helpers, the hand-chunked batches and
the Java re-sorts of what SQL already ordered all go. The driver and the sample
host connect to a PostgreSQL URL (spec 2026-09-17, sections 3.2 and 4.2)."
```

---

### Task 6: `Json` in the root package

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/Json.java`
- Modify: `data/ExportFiles.java`, `facts/FactsBuilder.java`, `ai/NumberVerifier.java`, `ai/FactsTools.java`, `ai/NarrativeContract.java`, `ai/Usage.java`, `service/DefaultForecastService.java`, `server/examples/HostExample.java`
- Test: `ai/ContractSchemaTest.java`, `ai/NarrativeContractTest.java`, `ai/NarratorTest.java`, `ai/NumberVerifierPropertyTest.java`, `ai/NumberVerifierTest.java`, `ai/PromptsTest.java`, `samplehost/JavaHostIntegrationTest.java`, `samplehost/SampleHostIntegrationTest.java`, `service/DefaultForecastServiceTest.java`, `testing/SeededFacts.java`

**Interfaces:**
- Produces: `Json.mapper()` returning the one configured `JsonMapper`; `ExportFiles.mapper()` no longer exists.

- [ ] **Step 1: `Json`**

```java
package com.workloadhub.forecast;

import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one Jackson mapper of the module: the facts, the narratives, the usage and the backtest JSON are all
 * written with it. Indented, with "\n" line endings regardless of platform, so a written file is byte-identical
 * on Windows and Linux: the default pretty printer's indenter otherwise uses {@code line.separator}.
 */
public final class Json {

    private static final DefaultIndenter LF_INDENTER = new DefaultIndenter("  ", "\n");
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .defaultPrettyPrinter(new DefaultPrettyPrinter()
                    .withObjectIndenter(LF_INDENTER)
                    .withArrayIndenter(LF_INDENTER))
            .build();

    private Json() {
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }
}
```

- [ ] **Step 2: Every call site**

Run from `server/`:

```bash
grep -rl 'ExportFiles.mapper()' forecast-core/src examples tools --include=*.java | xargs sed -i 's/ExportFiles\.mapper()/Json.mapper()/g'
```

Then in each changed file add `import com.workloadhub.forecast.Json;` (and drop the `ExportFiles` import where nothing else uses it). In `ExportFiles.java` delete `LF_INDENTER`, `MAPPER` and `mapper()`, and replace the remaining `MAPPER.` uses with `Json.mapper().`; keep the imports it still needs. `mvn -B -q -pl forecast-core compile test-compile` shows any file that still lacks the import.

- [ ] **Step 3: Gate and commit**

`rm -rf forecast-core/target/surefire-reports && mvn -B -q verify` from `server/`; exit 0.

```bash
git add -A server
git commit -m "Move the module's Jackson mapper to Json in the root package

Ten core call sites read the facts and narratives through the mapper that
ExportFiles happened to hold; ExportFiles is leaving for the tools module, and
the mapper is not export code (spec 2026-09-17, section 2.1)."
```

---

### Task 7: The seeded fixture, and core tests reading it

**Files:**
- Create: `server/forecast-core/src/test/resources/fixtures/workloadhub-schema.sql`, `server/forecast-core/src/test/resources/fixtures/seeded-rows.sql` (both generated), `server/forecast-core/src/test/java/com/workloadhub/forecast/FixtureFreshnessTest.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/SeedGenerator.java` (the `FIXTURE` constant), `server/tools/Experiment.java` (the `fixture` command), `server/tools/experiment.sh`, `.gitattributes`
- Rewrite: `testing/SeededData.java`, `store/DatabaseTestSupport.java` (`postgresWithSchema` reads the fixture's schema file, `runScript` added)
- Modify (tests): `data/ForecastRepositoryTest.java`, `store/JdbcGitHubTokenStoreTest.java`, `samplehost/SampleHostApplication.java`, `ForecastAutoConfigurationTest.java`, `testing/SeededFacts.java`, `ExperimentFlowTest.java`

**Interfaces:**
- Produces: `SeedGenerator.FIXTURE` (`new SeedConfig(30, LocalDate.of(2026, 9, 6), 11, true, 36)`); `SeededData.dataSource()` (one per JVM, migrated), `SeededData.freshDataSource()` (a new one per call, migrated), `SeededData.data()`, `SeededData.asOf()`; `DatabaseTestSupport.runScript(DataSource, Path)`; the driver's `fixture --out DIR`.
- `SeededData.envelope()` no longer exists: a test that compared against the envelope compares against the database.

- [ ] **Step 1: The constant and the command**

`SeedGenerator.java`, after `REAL_MODE_TABLES`:

```java
    /**
     * The seed forecast-core's tests read (36 users, 30 weeks, seed 11, last day 2026-09-06), committed as
     * forecast-core/src/test/resources/fixtures/seeded-rows.sql and regenerated by `experiment.sh fixture`.
     * FixtureFreshnessTest fails the gate when the committed file no longer matches this generator.
     */
    public static final SeedConfig FIXTURE = new SeedConfig(30, java.time.LocalDate.of(2026, 9, 6), 11, true, 36);
```

`server/tools/Experiment.java`: add `fixture` to the `USAGE` text (`  fixture  [--out DIR]` / `           Regenerate forecast-core's seeded test fixture: workloadhub-schema.sql and seeded-rows.sql in DIR.`), to the `switch` (`case "fixture" -> fixture(Args.parse(rest, Set.of("out", "url", "user", "password"), Set.of()));`) and the method:

```java
    private static int fixture(Args args) throws Exception {
        args.noFiles();
        Path dir = Path.of(args.require("out"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("workloadhub-schema.sql"), WorkloadHubSchema.readResource("/schema/workloadhub-postgresql.sql"),
                StandardCharsets.UTF_8);
        ExportEnvelope env = SeedGenerator.generate(null, SeedGenerator.FIXTURE);
        try (Writer w = Files.newBufferedWriter(dir.resolve("seeded-rows.sql"), StandardCharsets.UTF_8)) {
            SqlExportWriter.write(env, w);
        }
        System.out.println("Wrote " + dir.resolve("workloadhub-schema.sql") + " and " + dir.resolve("seeded-rows.sql")
                + " (" + env.rows("tasks").size() + " tasks, " + env.rows("time_logs").size() + " time logs)");
        return 0;
    }
```

`server/tools/experiment.sh`: before the `exec` line add

```bash
# `fixture` writes forecast-core's committed test fixture; the path is the repository's, whatever the
# working directory, unless --out says otherwise.
if [ "${1:-}" = fixture ] && ! printf '%s\n' "$@" | grep -qx -- '--out'; then
    set -- "$@" --out "$here/../forecast-core/src/test/resources/fixtures"
fi
```

and add the line `#   bash server/tools/experiment.sh fixture                                  # regenerate the committed test fixture` to its header.

- [ ] **Step 2: Generate and commit the two files**

From the repository root: `bash server/tools/experiment.sh fixture`. Expected output names both files with 1506 tasks and 4739 time logs. Then:

```bash
ls -la server/forecast-core/src/test/resources/fixtures/
head -3 server/forecast-core/src/test/resources/fixtures/seeded-rows.sql
```

Expected: `seeded-rows.sql` about 2.8 MB starting with `BEGIN;` and `SET search_path TO task_service;`; `workloadhub-schema.sql` about 40 KB starting with the dump's header.

Append to `.gitattributes`:

```text
# The seeded test fixture is generated (server/tools/experiment.sh fixture) and 2.8 MB; a regeneration
# shows as one changed file, not as ten thousand changed lines.
server/forecast-core/src/test/resources/fixtures/*.sql -diff
```

- [ ] **Step 3: `DatabaseTestSupport` reads the fixture's schema**

Add to `DatabaseTestSupport`:

```java
    /** The two generated files core's tests read: the WorkloadHub schema and the seeded rows (`experiment.sh fixture`). */
    public static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    /** Runs a whole SQL file in one statement; the driver splits it on semicolons and honours BEGIN and COMMIT inside it. */
    public static void runScript(DataSource ds, Path file) {
        String sql;
        try {
            sql = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("script failed: " + file + ": " + e.getMessage(), e);
        }
    }
```

and change `postgresWithSchema()` to

```java
    /** A new database holding schema task_service and the 24 WorkloadHub tables, from the committed schema file. */
    public static DataSource postgresWithSchema() {
        DataSource ds = postgres();
        runScript(ds, FIXTURES.resolve("workloadhub-schema.sql"));
        return ds;
    }
```

(imports: `java.io.IOException`, `java.nio.charset.StandardCharsets`, `java.nio.file.Files`, `java.nio.file.Path`). `WorkloadHubSchema` is no longer referenced from this class.

- [ ] **Step 4: `SeededData` on the fixture**

```java
package com.workloadhub.forecast.testing;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.ForecastRepository;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.ForecastMigrations;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The seeded dataset every core test reads: the synthetic seed of 36 users over 30 weeks (seed 11, last day
 * 2026-09-06), committed as fixtures/seeded-rows.sql by `experiment.sh fixture` and kept fresh by the tools
 * module's FixtureFreshnessTest. One migrated database per JVM, loaded through the real repository.
 */
public final class SeededData {

    /** The fixture's last day (SeedGenerator.FIXTURE in forecast-tools). */
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 6);
    private static DataSource dataSource;
    private static ForecastData data;

    private SeededData() {
    }

    /** The JVM's shared copy: read it, or write only what the test clears again. */
    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            dataSource = freshDataSource();
        }
        return dataSource;
    }

    /** A new database with the WorkloadHub tables, the seeded rows and the module's tables, for a test that changes rows. */
    public static DataSource freshDataSource() {
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        DatabaseTestSupport.runScript(ds, DatabaseTestSupport.FIXTURES.resolve("seeded-rows.sql"));
        ForecastMigrations.run(ds);
        return ds;
    }

    public static synchronized ForecastData data() {
        if (data == null) {
            data = new ForecastRepository(JdbcClient.create(dataSource())).loadAll();
        }
        return data;
    }

    public static LocalDate asOf() {
        return AS_OF;
    }
}
```

- [ ] **Step 5: The core tests that read the envelope or the importer**

- `ForecastRepositoryTest`: add `static long count(DataSource ds, String sql) { return JdbcClient.create(ds).sql(sql).query(Long.class).single(); }`. Then: line 36's `rows` becomes `count(SeededData.dataSource(), "SELECT COUNT(*) FROM users WHERE role IN ('MEMBER', 'TEAM_LEADER') AND active")`; line 53 `count(..., "SELECT COUNT(*) FROM tasks WHERE archived = FALSE")`; line 65 `count(..., "SELECT COUNT(*) FROM time_logs")`; lines 74-75 `count(..., "SELECT COUNT(*) FROM personal_leaves WHERE status = 'APPROVED'")` and `... = 'PENDING'`; line 79 `count(..., "SELECT COUNT(*) FROM projects WHERE archived = FALSE")`; line 82 `count(..., "SELECT COUNT(*) FROM users")`; the two `postgresWithSchema()` + importer pairs become `DataSource ds = SeededData.freshDataSource();`; line 116's member id becomes `JdbcClient.create(ds).sql("SELECT id FROM users ORDER BY id LIMIT 1").query(UUID.class).single()` bound with `.param(member)` where the test builds its `INSERT` (replace the string concatenation `'" + member + "'` by a `?` and `.param(member)`; the surrounding `Statement` becomes a `JdbcClient` call).
- `JdbcGitHubTokenStoreTest`: `postgresWithFixture()` becomes

```java
    static DataSource postgresWithOneUser() {
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        JdbcClient.create(ds).sql("INSERT INTO users (id, username, email, full_name, role, active, created_at, updated_at)"
                + " VALUES (?, 'eng', 'eng@example.test', 'Eng Two', 'MEMBER', TRUE, ?, ?)")
                .param(ENG).param(LocalDateTime.of(2026, 9, 1, 8, 0)).param(LocalDateTime.of(2026, 9, 1, 8, 0)).update();
        ForecastMigrations.run(ds);
        return ds;
    }
```

  (every `sqliteWithFixture()`/`postgresWithFixture()` call becomes `postgresWithOneUser()`; drop the `ExportImporter`, `ExportFiles` and `Path` imports; the stored-token query binds `.param(ENG)`.) The eight columns in that insert are exactly the `NOT NULL` columns of `users` in the schema dump (`active`, `created_at`, `updated_at`, `id`, `role`, `username`, `email`, `full_name`).
- `SampleHostApplication.dataSource()`: `return SeededData.freshDataSource();` (drop the importer, schema and `DatabaseTestSupport` imports).
- `ForecastAutoConfigurationTest.serviceWith`: `DataSource ds = SeededData.freshDataSource();` in place of the three lines; drop the `ExportImporter` import.
- `SeededFacts`: `new CapacityRule(AbsencePlanner.BASE_HOURS)` becomes `new CapacityRule(44.0)` with the comment `// 44 h, the seed's own week and the module's default`; drop the `AbsencePlanner` import.
- `ExperimentFlowTest`: add

```java
    @Test
    void fixtureWritesTheSchemaAndTheRows(@TempDir Path dir) throws Exception {
        assertOk(experiment("fixture", "--out", dir.toString()), "seeded-rows.sql");
        assertTrue(Files.readString(dir.resolve("workloadhub-schema.sql")).contains("CREATE SCHEMA task_service;"));
        assertTrue(Files.readString(dir.resolve("seeded-rows.sql")).startsWith("BEGIN;\nSET search_path TO task_service;\n"));
    }
```

`grep -rn 'SeededData.envelope\|ExportImporter' forecast-core/src/test --include=*.java | grep -v '^forecast-core/src/test/java/com/workloadhub/forecast/\(seed\|data\)/'` must print nothing.

- [ ] **Step 6: The freshness test**

`server/forecast-core/src/test/java/com/workloadhub/forecast/FixtureFreshnessTest.java`:

```java
package com.workloadhub.forecast;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.SqlExportWriter;
import com.workloadhub.forecast.seed.SeedGenerator;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** The committed fixture is what the generator produces today; a stale one fails the gate and says how to refresh it. */
class FixtureFreshnessTest {

    static final String HOW = "stale fixture: run `bash server/tools/experiment.sh fixture` and commit the result";

    @Test
    void theCommittedRowsMatchTheGenerator() throws Exception {
        StringWriter expected = new StringWriter();
        SqlExportWriter.write(SeedGenerator.generate(null, SeedGenerator.FIXTURE), expected);
        String actual = Files.readString(DatabaseTestSupport.FIXTURES.resolve("seeded-rows.sql"), StandardCharsets.UTF_8);
        assertTrue(expected.toString().equals(actual), HOW);
    }

    @Test
    void theCommittedSchemaMatchesTheResource() throws Exception {
        String expected = WorkloadHubSchema.readResource("/schema/workloadhub-postgresql.sql");
        String actual = Files.readString(DatabaseTestSupport.FIXTURES.resolve("workloadhub-schema.sql"), StandardCharsets.UTF_8);
        assertTrue(expected.equals(actual), HOW);
    }
}
```

- [ ] **Step 7: Gate**

`rm -rf forecast-core/target/surefire-reports && mvn -B -q verify` from `server/`; exit 0. Then change one byte of `seeded-rows.sql`, run `mvn -B -q -pl forecast-core test -Dtest=FixtureFreshnessTest -Dsurefire.failIfNoSpecifiedTests=false`, see it fail with the `stale fixture` message, and `git checkout -- server/forecast-core/src/test/resources/fixtures/seeded-rows.sql`.

- [ ] **Step 8: Commit**

```bash
git add -A server .gitattributes
git commit -m "Read the seeded test data from a committed fixture

The seed is leaving the library for a module that depends on it, and Maven
allows no cycle back into core's tests, so core reads the same 36-user seed from
two generated SQL files that the driver's new fixture command writes and a
freshness test keeps honest (spec 2026-09-17, section 5.3)."
```

---

### Task 8: The `forecast-tools` module

**Files:**
- Create: `server/forecast-tools/pom.xml`, `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/testing/DatabaseTestSupport.java`, `server/tools/tools-classpath.sh`
- Move (with `git mv`, then package rename): from `server/forecast-core/src/main/java/com/workloadhub/forecast/` the whole `seed/` to `server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/seed/`; `data/{ExportEnvelope,ExportFiles,ExportImporter,ExportExporter,SqlExportWriter}.java` and `store/WorkloadHubSchema.java` to `.../tools/export/`; `server/forecast-core/src/main/resources/schema/workloadhub-postgresql.sql` to `server/forecast-tools/src/main/resources/schema/`; `server/tools/Experiment.java` to `server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/Experiment.java`; `server/examples/HostExample.java` to `server/forecast-tools/src/main/java/com/workloadhub/forecast/examples/HostExample.java`
- Move (tests): from `server/forecast-core/src/test/java/com/workloadhub/forecast/` the whole `seed/` (12 files), `data/{ExportFilesTest,ExportImporterTest,RoundTripTest,SqlExportWriterTest}.java`, `store/WorkloadHubSchemaTest.java`, `ExperimentFlowTest.java`, `FixtureFreshnessTest.java` to the matching packages under `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/`; `server/forecast-core/src/test/resources/fixtures/mini-export.json` to `server/forecast-tools/src/test/resources/fixtures/`
- Modify: `server/pom.xml`, `server/forecast-core/pom.xml`, `server/tools/experiment.sh`, `server/examples/run-host-example.sh`, `server/tools/translate-schema.py` (the output path)
- Delete: `server/tools/core-classpath.sh`

**Interfaces:**
- Produces: packages `com.workloadhub.forecast.tools` (`Experiment` with `public static int run(String[] argv)`), `com.workloadhub.forecast.tools.seed`, `com.workloadhub.forecast.tools.export`, `com.workloadhub.forecast.examples` (`HostExample`); the tools module's tests reach a database through `com.workloadhub.forecast.tools.testing.DatabaseTestSupport.postgres()` and `postgresWithSchema()` (which runs `WorkloadHubSchema.createPostgresql`).
- Consumes: `Json.mapper()`, `Numbers`, `ForecastMigrations.run`, `ForecastService` and the rest of core's public API.

- [ ] **Step 1: The module's POM and the parent**

`server/forecast-tools/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.workloadhub</groupId>
    <artifactId>workloadhub-forecast-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>workloadhub-forecast-tools</artifactId>
  <name>WorkloadHub forecast tools</name>
  <description>What the host never runs: the seed, the import and export of a WorkloadHub database, the experiment driver and the sample host. Run by hand through server/tools/experiment.sh and server/examples/run-host-example.sh; never shipped.</description>
  <dependencies>
    <dependency><groupId>com.workloadhub</groupId><artifactId>workloadhub-forecast-core</artifactId><version>${project.version}</version></dependency>
    <!-- The driver builds a PGSimpleDataSource itself and the sample host's pool needs the driver on the classpath. -->
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency>

    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>net.jqwik</groupId><artifactId>jqwik</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
  </dependencies>
</project>
```

`server/pom.xml`: `<modules>` lists `forecast-core` then `forecast-tools`.

- [ ] **Step 2: Move the main sources and rename the packages**

From the repository root:

```bash
T=server/forecast-tools/src/main/java/com/workloadhub/forecast
C=server/forecast-core/src/main/java/com/workloadhub/forecast
mkdir -p $T/tools/export $T/examples server/forecast-tools/src/main/resources/schema
git mv $C/seed $T/tools/seed
for f in ExportEnvelope ExportFiles ExportImporter ExportExporter SqlExportWriter; do git mv $C/data/$f.java $T/tools/export/$f.java; done
git mv $C/store/WorkloadHubSchema.java $T/tools/export/WorkloadHubSchema.java
git mv server/forecast-core/src/main/resources/schema/workloadhub-postgresql.sql server/forecast-tools/src/main/resources/schema/workloadhub-postgresql.sql
git mv server/tools/Experiment.java $T/tools/Experiment.java
git mv server/examples/HostExample.java $T/examples/HostExample.java
rmdir server/forecast-core/src/main/resources/schema
# package declarations and imports
find $T -name '*.java' | xargs sed -i \
  -e 's/^package com\.workloadhub\.forecast\.seed;/package com.workloadhub.forecast.tools.seed;/' \
  -e 's/^package com\.workloadhub\.forecast\.data;/package com.workloadhub.forecast.tools.export;/' \
  -e 's/^package com\.workloadhub\.forecast\.store;/package com.workloadhub.forecast.tools.export;/' \
  -e 's/import com\.workloadhub\.forecast\.seed\./import com.workloadhub.forecast.tools.seed./' \
  -e 's/import com\.workloadhub\.forecast\.data\.\(ExportEnvelope\|ExportFiles\|ExportImporter\|ExportExporter\|SqlExportWriter\);/import com.workloadhub.forecast.tools.export.\1;/' \
  -e 's/import com\.workloadhub\.forecast\.store\.WorkloadHubSchema;/import com.workloadhub.forecast.tools.export.WorkloadHubSchema;/'
```

`Experiment.java` had no package line (it was a single-file program): add `package com.workloadhub.forecast.tools;` as its first line and make `run(String[] argv)` `public static`. `HostExample.java` declares `package com.workloadhub.forecast.examples;` already. Classes in the same new package (`tools.export`) that used to import each other across `data`/`store` no longer need those imports; `mvn -B -q -pl forecast-tools -am compile` reports any leftover, and each is fixed by hand.

`Experiment`'s javadoc paragraph beginning `This is not a Maven module and deliberately so.` becomes: `Until 2026-09-17 this was a single-file program under server/tools, compiled by the launcher against forecast-core's classes; it is now a class of forecast-tools, the module that holds everything the host never runs, so the gate compiles it. server/tools/experiment.sh is still the way in.`

- [ ] **Step 3: Move the tests**

```bash
TT=server/forecast-tools/src/test/java/com/workloadhub/forecast/tools
CT=server/forecast-core/src/test/java/com/workloadhub/forecast
mkdir -p $TT/export $TT/testing server/forecast-tools/src/test/resources/fixtures
git mv $CT/seed $TT/seed
for f in ExportFilesTest ExportImporterTest RoundTripTest SqlExportWriterTest; do git mv $CT/data/$f.java $TT/export/$f.java; done
git mv $CT/store/WorkloadHubSchemaTest.java $TT/export/WorkloadHubSchemaTest.java
git mv $CT/ExperimentFlowTest.java $TT/ExperimentFlowTest.java
git mv $CT/FixtureFreshnessTest.java $TT/FixtureFreshnessTest.java
git mv server/forecast-core/src/test/resources/fixtures/mini-export.json server/forecast-tools/src/test/resources/fixtures/mini-export.json
find $TT -name '*.java' | xargs sed -i \
  -e 's/^package com\.workloadhub\.forecast\.seed;/package com.workloadhub.forecast.tools.seed;/' \
  -e 's/^package com\.workloadhub\.forecast\.data;/package com.workloadhub.forecast.tools.export;/' \
  -e 's/^package com\.workloadhub\.forecast\.store;/package com.workloadhub.forecast.tools.export;/' \
  -e 's/^package com\.workloadhub\.forecast;/package com.workloadhub.forecast.tools;/' \
  -e 's/import com\.workloadhub\.forecast\.seed\./import com.workloadhub.forecast.tools.seed./' \
  -e 's/import com\.workloadhub\.forecast\.data\.\(ExportEnvelope\|ExportFiles\|ExportImporter\|ExportExporter\|SqlExportWriter\);/import com.workloadhub.forecast.tools.export.\1;/' \
  -e 's/import com\.workloadhub\.forecast\.store\.WorkloadHubSchema;/import com.workloadhub.forecast.tools.export.WorkloadHubSchema;/' \
  -e 's/import com\.workloadhub\.forecast\.store\.DatabaseTestSupport;/import com.workloadhub.forecast.tools.testing.DatabaseTestSupport;/'
```

`server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/testing/DatabaseTestSupport.java` is a copy of core's class with: package `com.workloadhub.forecast.tools.testing`; the `FIXTURES` constant and `runScript` removed; `postgresWithSchema()` calling `WorkloadHubSchema.createPostgresql(ds)`; no `postgresMigrated()`. Its javadoc says: `A copy of forecast-core's test support, fixed to one engine: thirty lines are cheaper than a test-jar of core published for one class. Keep the image tag equal to the other copy's.`

`FixtureFreshnessTest` in tools: replace `DatabaseTestSupport.FIXTURES.resolve(...)` with `Path.of("../forecast-core/src/test/resources/fixtures").resolve(...)` (surefire's working directory is the module) and drop the `DatabaseTestSupport` import.

`ExperimentFlowTest` in tools runs the driver in process:

```java
    /** What one run of the driver did: its exit code and everything it printed, both streams together. */
    private record Run(int exit, String output) {
    }

    private static synchronized Run experiment(String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            return new Run(Experiment.run(args), buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }
```

Delete `DRIVER`, `java()` and the `ProcessBuilder` version; `FIXTURE` stays `Path.of("src/test/resources/fixtures/mini-export.json")` (the module's own resource); the class javadoc says the driver is a class of this module now and this test drives it in process. The `--url` negative test that wrote `x.db` is already gone (task 5). The test's `connection(...)` helper reads the tools copy of `DatabaseTestSupport`.

Tests under `seed/` that read `src/test/resources/fixtures/mini-export.json` keep that path (the file moved with them).

- [ ] **Step 4: Core's POM and leftovers**

`server/forecast-core/pom.xml` is unchanged in dependencies (the PostgreSQL driver stays optional at runtime and present in tests). `grep -rn 'forecast\.seed\|ExportImporter\|ExportExporter\|SqlExportWriter\|ExportEnvelope\|WorkloadHubSchema' server/forecast-core/src` must print nothing.

`server/tools/translate-schema.py`: `OUT = Path(__file__).resolve().parents[1] / "forecast-tools/src/main/resources/schema"`.

- [ ] **Step 5: The launcher classpath**

`server/tools/tools-classpath.sh` (replacing `core-classpath.sh`, `git mv` then edit):

```bash
#!/usr/bin/env bash
# Prints the classpath the two programs of forecast-tools run against: both modules' compiled classes
# followed by the tools module's runtime dependencies, compiling and resolving first if either is
# missing or stale.
#
#   cp="$(bash server/tools/tools-classpath.sh)" && java --class-path "$cp" com.workloadhub.forecast.tools.Experiment --help
#
# server/examples/run-host-example.sh and server/tools/experiment.sh both use it. Progress goes to stderr so
# only the classpath lands on stdout, and nothing here changes the caller's working directory.
set -euo pipefail

server="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cp_file="$server/forecast-tools/target/tools-classpath.txt"
core_classes="$server/forecast-core/target/classes"
tools_classes="$server/forecast-tools/target/classes"

(
    cd "$server"
    if [ ! -d "$tools_classes" ] || [ ! -d "$core_classes" ] \
        || [ -n "$(find forecast-core/src/main forecast-tools/src/main -newer "$tools_classes" -name '*.java' -print -quit 2>/dev/null)" ]; then
        echo "==> compiling forecast-core and forecast-tools" >&2
        mvn -B -q -pl forecast-tools -am -DskipTests compile
    fi
    if [ ! -f "$cp_file" ] || [ forecast-tools/pom.xml -nt "$cp_file" ] || [ forecast-core/pom.xml -nt "$cp_file" ] || [ pom.xml -nt "$cp_file" ]; then
        echo "==> resolving the runtime classpath" >&2
        mvn -B -q -pl forecast-tools dependency:build-classpath \
            -Dmdep.outputFile=target/tools-classpath.txt -Dmdep.includeScope=runtime
    fi
)

printf '%s:%s:%s\n' "$tools_classes" "$core_classes" "$(cat "$cp_file")"
```

`server/tools/experiment.sh`: `classpath="$(bash "$here/tools-classpath.sh")"` and `exec java --class-path "$classpath" com.workloadhub.forecast.tools.Experiment "$@"`; its header's third paragraph becomes `The driver is a class of forecast-tools, compiled by the gate; this script only resolves the classpath and runs it.`
`server/examples/run-host-example.sh`: `classpath="$(bash "$(dirname "$here")/tools/tools-classpath.sh")"` and `exec java --class-path "$classpath" com.workloadhub.forecast.examples.HostExample "$@"`; its header drops the sentence about the single-file launcher and the `/data/workloadhub.db` instructions, and says instead: `The default database is the local PostgreSQL scripts/postgres.sh runs; see server/README.md, "Running experiments", for the three commands that fill it.`

- [ ] **Step 6: Gate**

`rm -rf forecast-core/target/surefire-reports forecast-tools/target/surefire-reports && mvn -B -q verify` from `server/`; exit 0. Read both `TEST-*.xml` sets; the totals add up to the count of task 7 plus the new fixture test.

Then, from the repository root, `bash server/tools/experiment.sh --help` prints the usage (this compiles nothing new after the gate) and `bash server/examples/run-host-example.sh --help` prints the sample host's usage.

Check the jar: `unzip -l server/forecast-core/target/workloadhub-forecast-core-0.1.0-SNAPSHOT.jar | grep -c 'seed/\|schema/\|sqlite'` prints `0`; note the jar's size for task 11.

- [ ] **Step 7: Commit**

```bash
git add -A server
git commit -m "Move the seed, the export code, the driver and the sample host to forecast-tools

Nothing on the host's path used them and they were a fifth of the shipped jar.
forecast-tools depends on forecast-core, holds them under tools.seed,
tools.export, tools.Experiment and examples.HostExample, and its tests move
with them; the launcher scripts resolve the tools classpath. Core keeps only
the seeded fixture (spec 2026-09-17, section 2.2)."
```

---

### Task 9: `scripts/postgres.sh`, the container and the devbox

**Files:**
- Create: `scripts/postgres.sh`
- Modify: `scripts/container/Containerfile`, `scripts/devbox.sh`

**Interfaces:**
- Produces: a PostgreSQL 18 container `whf-postgres` on port 5432 with database, user and password `workloadhub`, on the named volume `whf-pg`; `postgres.sh up|stop|rm [--volume]|status|psql|--help`.
- Corrected on 2026-09-18, in the snippet below and in spec section 4.1: the volume mounts at `/var/lib/postgresql`, not `/var/lib/postgresql/data`. `postgres:18` moved `PGDATA` into a version subdirectory, and its entrypoint reads a separate mount landing on `.../data` as leftover data from before an image upgrade and refuses to start, on an empty volume as well as a full one.

- [ ] **Step 1: The script**

```bash
#!/usr/bin/env bash
# The local PostgreSQL: one container under the same engine as the development box, holding the
# database the experiment driver and the sample host connect to by default. The host application's
# database is PostgreSQL, so this is the one engine the module ever runs on.
#
# Usage: bash scripts/postgres.sh up              create the volume and start the container, or start it again
#        bash scripts/postgres.sh stop            stop it; `up` brings it back with its data
#        bash scripts/postgres.sh rm [--volume]   remove the container; --volume also removes the data
#        bash scripts/postgres.sh status          is it running, which port, does the database answer
#        bash scripts/postgres.sh psql            open psql inside the container
#        bash scripts/postgres.sh --help          this header
#
# The database is `workloadhub`, user `workloadhub`, password `workloadhub`, port 5432, reachable as
# jdbc:postgresql://localhost:5432/workloadhub from Windows and from inside the development box
# alike, since the box runs on the host network. The schema task_service inside it is created by the
# driver (`bash server/tools/experiment.sh init-db`), not here: this script knows nothing about
# WorkloadHub.
#
# CONTAINER_ENGINE  podman or docker; the default is whichever is on PATH, podman first.
# WHF_PG_IMAGE      the image (default postgres:18-alpine; the schema dump came from an 18 server).
# WHF_PG_CONTAINER  the container's name (default whf-postgres).
# WHF_PG_VOLUME     the named volume for the data (default whf-pg).
# WHF_PG_PORT       the host port (default 5432).
set -euo pipefail

help() {
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
}

case "${1:-}" in
help | -h | --help)
    help
    exit 0
    ;;
"")
    help
    exit 2
    ;;
up | stop | rm | status | psql) ;;
*)
    help
    echo "unknown command: $1" >&2
    exit 2
    ;;
esac

engine="${CONTAINER_ENGINE:-}"
if [ -z "$engine" ]; then
    if command -v podman >/dev/null 2>&1; then
        engine=podman
    elif command -v docker >/dev/null 2>&1; then
        engine=docker
    else
        echo "no container engine on PATH: install podman or docker" >&2
        exit 1
    fi
fi
command -v "$engine" >/dev/null 2>&1 || {
    echo "CONTAINER_ENGINE=$engine is not on PATH" >&2
    exit 1
}

image="${WHF_PG_IMAGE:-postgres:18-alpine}"
name="${WHF_PG_CONTAINER:-whf-postgres}"
volume="${WHF_PG_VOLUME:-whf-pg}"
port="${WHF_PG_PORT:-5432}"

exists() { "$engine" inspect --type container "$name" >/dev/null 2>&1; }
running() { [ "$("$engine" inspect -f '{{.State.Running}}' "$name" 2>/dev/null || true)" = true ]; }

case "$1" in
up)
    if exists; then
        if running; then
            echo "$name is already running on port $port"
        else
            "$engine" start "$name" >/dev/null
            echo "$name started again on port $port"
        fi
        exit 0
    fi
    echo "==> creating $name from $image"
    "$engine" volume create "$volume" >/dev/null 2>&1 || true
    "$engine" run -d --name "$name" --restart unless-stopped \
        -p "$port:5432" \
        -e POSTGRES_DB=workloadhub -e POSTGRES_USER=workloadhub -e POSTGRES_PASSWORD=workloadhub \
        -v "$volume:/var/lib/postgresql" \
        "$image" >/dev/null
    echo "waiting for the database to answer"
    for _ in $(seq 1 30); do
        if "$engine" exec "$name" pg_isready -U workloadhub -d workloadhub >/dev/null 2>&1; then
            echo "$name is up: jdbc:postgresql://localhost:$port/workloadhub (user workloadhub, password workloadhub)"
            echo "next: bash server/tools/experiment.sh init-db"
            exit 0
        fi
        sleep 1
    done
    echo "$name did not answer within 30 s; inspect it with: $engine logs $name" >&2
    exit 1
    ;;
stop)
    if running; then
        "$engine" stop "$name" >/dev/null
        echo "$name stopped; \`up\` brings it back with its data"
    else
        echo "$name is not running"
    fi
    ;;
rm)
    if exists; then
        "$engine" rm -f "$name" >/dev/null
        echo "$name removed"
    else
        echo "$name does not exist"
    fi
    if [ "${2:-}" = "--volume" ]; then
        "$engine" volume rm "$volume" >/dev/null 2>&1 && echo "volume $volume removed" || echo "volume $volume did not exist"
    else
        echo "the data stays in volume $volume; pass --volume to remove it too"
    fi
    ;;
status)
    if ! exists; then
        echo "$name does not exist; run: bash scripts/postgres.sh up"
        exit 1
    fi
    if running; then
        echo "$name is running on port $port ($image), volume $volume"
        if "$engine" exec "$name" pg_isready -U workloadhub -d workloadhub >/dev/null 2>&1; then
            echo "the database answers: jdbc:postgresql://localhost:$port/workloadhub"
        else
            echo "the container runs but the database does not answer yet"
        fi
    else
        echo "$name exists but is stopped; run: bash scripts/postgres.sh up"
        exit 1
    fi
    ;;
psql)
    running || {
        echo "$name is not running; run: bash scripts/postgres.sh up" >&2
        exit 1
    }
    exec "$engine" exec -it "$name" psql -U workloadhub -d workloadhub
    ;;
esac
```

`chmod +x scripts/postgres.sh`.

- [ ] **Step 2: The container image and the devbox header**

`scripts/container/Containerfile`: in the `apt-get install` list replace `sqlite3 postgresql-client \` with `postgresql-client \`; in the comment above, `sqlite3 and psql look into a forecast database by hand` becomes `psql looks into the forecast database by hand`, and `python3 is what server/tools/parity.sh runs` becomes `python3 runs server/tools/translate-schema.py`.

`scripts/devbox.sh` header: the `WHF_DATA` line becomes `WHF_DATA          host directory mounted at /data, for seeds and exports, which must stay out of the repository (default ~/whf; created if missing). The database itself lives in the whf-pg volume of scripts/postgres.sh.`; the `CONTAINER_SOCK` line becomes `CONTAINER_SOCK    the engine socket the box mounts so Testcontainers can start PostgreSQL as a sibling container. The gate needs it: without the socket every database test fails. The default is what podman reports for itself, or the usual docker path.`; in `check_socket`, the two messages `the PostgreSQL tests will skip themselves` and `the PostgreSQL tests will skip` become `the gate will fail: every database test needs the engine`; in `create`, the message `CONTAINER_SOCK= to run the box without PostgreSQL.` becomes `CONTAINER_SOCK= to run the box without the gate.`. In the `status` command's output add one line: `if "$engine" inspect -f '{{.State.Running}}' "${WHF_PG_CONTAINER:-whf-postgres}" 2>/dev/null | grep -q true; then echo "local PostgreSQL: whf-postgres is running (scripts/postgres.sh status)"; else echo "local PostgreSQL: not running (bash scripts/postgres.sh up)"; fi` (find the `status)` case and append the line at its end).

- [ ] **Step 3: The hand check**

From the repository root, with an engine available (this session's container has `docker`; the owner's box has podman):

```bash
bash scripts/postgres.sh up
bash scripts/postgres.sh status
bash server/tools/experiment.sh init-db
bash server/tools/experiment.sh init-db            # expected: exit 2, "already exists"
bash server/tools/experiment.sh seed --synthetic --users 40 --weeks 26 --seed 7 --end 2026-09-06 --out /tmp/seed.json
bash server/tools/experiment.sh import /tmp/seed.json
bash server/tools/experiment.sh export /tmp/dump.json
bash server/examples/run-host-example.sh           # lists the teams
bash server/examples/run-host-example.sh --team <a uuid marked TEAM_LEADER>
bash scripts/postgres.sh stop && bash scripts/postgres.sh up && bash scripts/postgres.sh status   # the data survives
bash scripts/postgres.sh rm --volume
```

Expected: every command exits 0 except the second `init-db` (2); the sample host prints a run with progress labels, the windows and the overload, the current forecast, the run list, accuracy and `copilotStatus`; `status` after the restart still answers. Record the sample host's first lines in the closing notes.

- [ ] **Step 4: Gate and commit**

The gate is unaffected by scripts; run `bash scripts/check.sh` once from the repository root anyway (it now checks the engine first) and confirm it exits 0.

```bash
git add scripts/postgres.sh scripts/container/Containerfile scripts/devbox.sh
git commit -m "Add scripts/postgres.sh, the local PostgreSQL the driver connects to

One container under the engine the development box already uses, on a named
volume, published on 5432, so the driver and the sample host reach it as
localhost from Windows and from inside the box alike (spec 2026-09-17,
section 4.1). The image drops sqlite3."
```

---

### Task 10: Test pruning and speed-ups, the gate timed

**Files:**
- Modify: `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/seed/WorkQueueTest.java`, `.../seed/SeedGeneratorTest.java`, `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/FixtureFreshnessTest.java`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/ai/NarratorTest.java`, `ai/PromptsTest.java`, `features/FeatureBuilderTest.java`

- [ ] **Step 1: Time the gate before**

From `server/`: `rm -rf forecast-core/target/surefire-reports forecast-tools/target/surefire-reports && time mvn -B -q verify`. Record the wall time and, from the XML, the sum of the suites' `time` attributes: `grep -ho 'time="[0-9.]*"' forecast-*/target/surefire-reports/TEST-*.xml | sed 's/time="//; s/"//' | awk '{s+=$1} END {print s " s in tests"}'`.

- [ ] **Step 2: `SeedGeneratorTest` caches its generation**

Replace

```java
    static ExportEnvelope generated() {
        return SeedGenerator.generate(null, CFG);
    }
```

with

```java
    private static ExportEnvelope generated;

    /** One generation per JVM: the tests only read it, and `isDeterministic` generates its own second copy. */
    static synchronized ExportEnvelope generated() {
        if (generated == null) {
            generated = SeedGenerator.generate(null, CFG);
        }
        return generated;
    }
```

and delete the two lines in `everyForeignKeyResolvesAndKeysAreUnique` that `syntheticModeStillWritesEveryTable` subsumes: `assertEquals(WorkloadHubSchema.TABLE_ORDER.size(), env.data().size(), "every table present, empty ones included");` and `assertEquals(List.of("refresh_tokens"), env.excludedTables());`.

- [ ] **Step 3: `WorkQueueTest`**

In `defaultRatesCoverAllModesSubTasksAndDataIntegrity`: delete part (a), from the comment line `// (a) all three creation modes occur.` through `assertTrue(leaderSeen, "leader mode (reporter != assignee, assigned by the leader) occurs");` (parts (b) to (e) read `w`, `r` and `byId`, none of part (a)'s `lead`, `withAssigneeHistory`, `backlogSeen`, `selfSeen`, `leaderSeen`); and delete part (f), from `// (f) determinism:` through `assertEquals(r.timeLogRows(), r2.timeLogRows());`. If the `historyOf` helper then has no caller (`grep -n 'historyOf(' WorkQueueTest.java`), delete it too. Rename the method `defaultRatesCoverSubTasksAndDataIntegrity`.

Delete `realisticDataset()` and `someMemberLogsMoreThanADayAndMoreThanAWeek`. Its "any day over 8.8 h" half moves onto the fixture seed: in `FixtureFreshnessTest` add a static cache of the generated envelope,

```java
    private static ExportEnvelope generated;

    static synchronized ExportEnvelope generated() {
        if (generated == null) {
            generated = SeedGenerator.generate(null, SeedGenerator.FIXTURE);
        }
        return generated;
    }
```

use it in `theCommittedRowsMatchTheGenerator`, and add

```java
    /** Overload must be reachable on the fixture: at least one member logs more than a present day's 8.8 h somewhere. */
    @Test
    void someMemberLogsMoreThanADay() {
        Map<String, Double> byMemberDay = new HashMap<>();
        for (LinkedHashMap<String, Object> row : generated().rows("time_logs")) {
            byMemberDay.merge(row.get("user_id") + "|" + row.get("log_date"), (Double) row.get("hours"), Double::sum);
        }
        assertTrue(byMemberDay.values().stream().anyMatch(h -> h > AbsencePlanner.HOURS_PER_DAY),
                "no seeded member ever logs more than a day's worth in one day, so overload can never fire");
    }
```

(imports `java.util.HashMap`, `java.util.LinkedHashMap`, `java.util.Map`, `com.workloadhub.forecast.tools.seed.AbsencePlanner`, `com.workloadhub.forecast.tools.export.ExportEnvelope`).

- [ ] **Step 4: `NarratorTest` and `PromptsTest`**

`NarratorTest.theSessionMetricsSayWhatTheNarrationCost`: keep `assertEquals("metrics", u.path("source").asText());` and `assertEquals(List.of("usage", "close"), g.session.calls, ...)`; delete the five arithmetic assertions between them (`ai_credits`, `usd`, `input_tokens`, `premium_requests`, `api_seconds`), which `UsageTest` owns. `aFailedNarrationStillReportsWhatItCost`: delete the `ai_credits` assertion, keep `source`.

`PromptsTest.theContractSchemaIsJsonWithTheTopLevelFields`: keep the `additionalProperties` assertion only, rename the test `theContractSchemaForbidsUnknownTopLevelFields`, delete the loop and the two `$defs` assertions (`ContractSchemaTest` pins the exact key sets).

- [ ] **Step 5: `FeatureBuilderTest` builds the seeded matrix once**

`seededMatrixHasEveryFeatureColumnPopulated` and `targetHEqualsLoggedHoursHWeeksLaterWhereBothExist` open with the same four lines:

```java
        ForecastData data = SeededData.data();
        LocalDate origin = com.workloadhub.forecast.calendar.Weeks.lastCompleteWeek(SeededData.asOf());
        FeatureMatrix m = new FeatureBuilder(data, Lifecycle.derive(data), WorkingCalendar.fromHolidays(data.holidays()), RULE, WINDOWS)
                .build(data.members(), origin);
```

Add to the class

```java
    private static FeatureMatrix seeded;

    /** The seeded matrix once per JVM: the two tests that read it only read it. */
    static synchronized FeatureMatrix seededMatrix() {
        if (seeded == null) {
            ForecastData data = SeededData.data();
            LocalDate origin = com.workloadhub.forecast.calendar.Weeks.lastCompleteWeek(SeededData.asOf());
            seeded = new FeatureBuilder(data, Lifecycle.derive(data), WorkingCalendar.fromHolidays(data.holidays()), RULE, WINDOWS)
                    .build(data.members(), origin);
        }
        return seeded;
    }
```

and in both tests replace the four lines with `ForecastData data = SeededData.data();` and `FeatureMatrix m = seededMatrix();` (the first test still reads `data.members().size()`; the second still needs `origin` if it uses it below, so keep its `origin` line there).

- [ ] **Step 6: Gate, timed**

`rm -rf forecast-core/target/surefire-reports forecast-tools/target/surefire-reports && time mvn -B -q verify` from `server/`; exit 0. Record the wall time and the tests' summed time as in step 1, and the test count per module from the XML.

- [ ] **Step 7: Commit**

```bash
git add -A server
git commit -m "Prune the redundant tests the audits listed and cache the seeds the tests read

Two determinism tests repeated a third, one assertion repeated another test's,
the narrator re-checked the usage arithmetic UsageTest owns, and the 40-user seed
was generated eleven times per run; the assertions that pinned behaviour of
their own stay, one of them moved onto the committed fixture (spec 2026-09-17,
sections 5.2 and 5.4)."
```

---

### Task 11: Documentation and the measurements

**Files:**
- Modify: `CLAUDE.md`, `server/README.md`, `docs/backlog.md`, `docs/design/2026-09-17-workloadhub-schema-diagram.html`, `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`, `docs/superpowers/specs/2026-09-17-postgresql-only-and-tools-module-design.md`, this plan (closing notes)

- [ ] **Step 1: `CLAUDE.md`**

- Opening paragraph: `The host calls a Java interface or an optional REST surface; a single-file Java driver runs the same code on SQLite for experiments.` becomes `The host calls a Java interface or an optional REST surface; a second Maven module, forecast-tools, holds the seed and the experiment driver, run by hand against a local PostgreSQL.`
- "Read these first": add `docs/superpowers/specs/2026-09-17-postgresql-only-and-tools-module-design.md: **implemented, landed on dev on <date>.** PostgreSQL is the only database, one final V1 migration, the seed and the driver in forecast-tools, core tests on a committed seeded fixture, a local PostgreSQL in scripts/postgres.sh.`
- "Where the project stands": one paragraph after the 2026-09-17 personal-leaves one, in the same voice, stating what landed, the test counts and the gate time from task 10, and `Next: the derived-arithmetic backlog item's own design pass, then the real export through the seed into the local PostgreSQL, then the server's own integration code, against the sample host.`
- "Layout": the `server/` line becomes

```text
server/    Java 21 modules: `forecast-core`, the library the host adds and the only artifact; `forecast-tools`,
           never shipped: the seed, the import and export of a WorkloadHub database, `Experiment` (the
           driver: init-db, import, export, seed, fixture) and `HostExample` (what a host does through
           `ForecastService`), run through `server/tools/experiment.sh` and `server/examples/run-host-example.sh`
```

  and the `scripts/` line gains `postgres.sh` (the local PostgreSQL the driver connects to).
- "Toolchain": the gate sentence becomes `The gate is one step: cd server && mvn -B -q verify (both modules; the database tests run PostgreSQL through Testcontainers and need Docker or podman: without an engine the gate fails, it never skips).`; delete `PostgreSQL tests run through Testcontainers when Docker is present, else skip with a message`; the devbox paragraph's `with the engine's socket mounted so the PostgreSQL tests still run` becomes `with the engine's socket mounted, which the gate needs`; add `bash scripts/postgres.sh up starts the local PostgreSQL 18 (database, user and password workloadhub, port 5432); bash server/tools/experiment.sh init-db creates the schema in it.`
- "Hard rules": the seed sentence `The real export and any real-mode seed output stay outside the repository.` is unchanged; add to the CI bullet nothing.
- "Conventions": no change.

- [ ] **Step 2: `server/README.md`**

- First paragraph: `Two Maven modules: forecast-core, the library the WorkloadHub Spring Boot application adds as a dependency, and forecast-tools, never shipped, which holds the seed, the import and export of a WorkloadHub database, the experiment driver and the sample host.`
- "Prerequisites": replace the paragraph `A container engine is optional for the build itself...` with `A container engine (Docker or podman) is required: the database tests run PostgreSQL 18 through Testcontainers and the gate fails without one. The same engine runs the local database of scripts/postgres.sh.`
- "Build and test": `mvn -B verify` builds both modules; add `bash scripts/check.sh   # the gate: checks for the engine, then mvn verify`.
- "The development container": `/data is ~/whf on the host, for the seeds and exports that must stay out of the repository` (drop `databases`); add a sentence: `The database is not a file: scripts/postgres.sh runs it as a sibling container, reachable from the box as localhost:5432.`
- "Running experiments": rewrite the block as

```bash
X="bash server/tools/experiment.sh"

# 0. the local database, once
bash scripts/postgres.sh up

# 1. the schema task_service with the 24 WorkloadHub tables and the module's tables
$X init-db                                    # --force drops and recreates the schema

# 2. a year of history for the real directory (the export holds personal data: keep it and the output outside git)
$X seed --export ~/whf/workloadhub_export.json --weeks 52 --end 2026-09-06 --seed 42 --out ~/whf/seeded.json
$X seed --export ~/whf/workloadhub_export.json --weeks 52 --end 2026-09-06 --seed 42 --format sql --out ~/whf/seeded.sql

# 3. load it
$X import ~/whf/seeded.json

# 4. or a synthetic population with no personal data, for tests and demos
$X seed --synthetic --users 120 --weeks 52 --seed 7 --end 2026-09-06 --out /tmp/synthetic.json
$X import /tmp/synthetic.json

# 5. dump the database back to JSON
$X export /tmp/dump.json

# 6. regenerate forecast-core's committed test fixture after a change to the seed
$X fixture
```

  keep the team-size comment of step 4; the command table gains the connection row (`--url`, `--user`, `--password`, else `WHF_DB_URL`, `WHF_DB_USER`, `WHF_DB_PASSWORD`, else the local database) and the `fixture` row, and `init-db`'s row reads `[--force]  Creates schema task_service with the 24 WorkloadHub tables and the module's tables; refuses an existing schema without --force.`; the paragraph `tools/experiment.sh runs tools/Experiment.java the same way: Java 21's single-file source launcher ...` becomes `tools/experiment.sh runs com.workloadhub.forecast.tools.Experiment, a class of forecast-tools, against the classpath tools/tools-classpath.sh resolves (it compiles both modules first when the sources are newer). ExperimentFlowTest in forecast-tools drives every verb in process.`; the `psql -d avl_workloadhub -f ~/whf/seeded.sql` sentence stays (the SQL export is for the host's database).
- "Using the module from the WorkloadHub server", "Try the calls first" bullet: `... runs HostExample, a standalone Spring Boot application on the local PostgreSQL that makes every one of those calls ...`; delete `It is compiled by Java's single-file source launcher against forecast-core's classes, so it adds no module to the build and can be edited and re-run in a few seconds.` and write `It is a class of forecast-tools, so the gate compiles it; its DataSource is the one Spring Boot builds from spring.datasource.*, exactly as in the server.`
- "What the module adds to the host's dependency tree": add `Nothing of the seed, the schema scripts or the driver: they live in forecast-tools, which the host never adds.`
- A new short section before "Narrating with Copilot":

```markdown
## The tools module

`forecast-tools` depends on `forecast-core` and is never shipped. It holds the seed (`tools.seed`), the
import and export of a WorkloadHub database and the schema script (`tools.export`), the driver
(`tools.Experiment`) and the sample host (`examples.HostExample`). Its tests are the seed's, the
export code's, the driver's and `FixtureFreshnessTest`, which fails the gate when
`forecast-core/src/test/resources/fixtures/` no longer matches what the generator produces: run
`bash server/tools/experiment.sh fixture` and commit the two files.
```

- [ ] **Step 3: `docs/backlog.md`**

- Under "Landed", a new first entry `**PostgreSQL only, one final migration, and the tools module** (<date>): ...` in the voice of the entries above it, naming the spec and this plan, the two modules, the fixture, `scripts/postgres.sh`, the removed SQLite, the test count and gate time.
- In the "Over-engineering survey (2026-09-12)" item, item 1 becomes: `1. **`seed/` ships inside the library.** Closed on <date>: it is `forecast-tools` now (`docs/superpowers/specs/2026-09-17-postgresql-only-and-tools-module-design.md`).`
- A new open item under "Java migration": `**Duplication folds the 2026-09-17 audits found outside the store (2026-09-17).** NumberVerifier parses each token twice and walks the facts tree twice per verification; SdkCopilotGateway hand-parses two properties files and repeats one timeout-and-interrupt block four times; the Copilot sign-in check exists in both Narrator and DefaultForecastService with the same two messages; the seed builds rows with ten-line put ladders in six files and rebuilds the person map three times; two medians, four rounding helpers, three weekend tests and three working-day counts exist. Each is a pure refactor with a test already pinning it; about 300 lines. Spec section 10.`

- [ ] **Step 4: The schema diagram page and the two specs**

- `docs/design/2026-09-17-workloadhub-schema-diagram.html`: in the `<p class="hint">Source: ...` line, `the module's migrations <code>db/forecast/postgresql/V1</code> to <code>V5</code>` becomes `the module's one migration <code>db/forecast/postgresql/V1</code>`; in the JSON data (`var T = {...}`), every `"migration":"V1, V4"`, `"V3, V4, V5"`, `"V3, V4"`, `"V1, V2"` becomes `"migration":"V1"` (`sed -i -E 's/"migration":"V[0-9](, V[0-9])*"/"migration":"V1"/g'` on that file); the sentence at line 144 (`The module ships its own Flyway migrations and runs them ...`) becomes `The module ships one Flyway migration and runs it ...`.
- `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`: add under its status line `**Superseded in part (2026-09-17):** the SQLite experiment path and the paired migrations of sections 2, 3 and 12 are gone; PostgreSQL is the only database and the driver lives in forecast-tools (docs/superpowers/specs/2026-09-17-postgresql-only-and-tools-module-design.md).`
- The spec of this plan: status line `implemented, landed on dev on <date> (plan docs/superpowers/plans/2026-09-17-postgresql-only-and-tools-module.md, closing notes there)`; section 5.3's first bullet becomes two files (`workloadhub-schema.sql`, the schema script copied by the fixture command, and `seeded-rows.sql`, the seeded rows), with the sentence `Two files rather than one, so a test that needs only the empty schema does not load 2.8 MB of rows; both are written by the fixture command and both are checked by the freshness test.`

- [ ] **Step 5: Closing notes in this plan**

Append a `## Closing notes` section with: the gate time before (task 10 step 1) and after (task 10 step 6), the test counts per module before and after with the removed tests listed against spec section 5.2, the fixture's size on disk and in git (`git cat-file -s $(git rev-parse HEAD:server/forecast-core/src/test/resources/fixtures/seeded-rows.sql)` after commit shows the stored size), the core jar's size before (from task 8 step 6, or `git show c9bdc1c` built once if not recorded) and after, the sample host's output from task 9, and any deviation from the spec with its reason.

- [ ] **Step 6: Gate and commit**

`rm -rf forecast-core/target/surefire-reports forecast-tools/target/surefire-reports && mvn -B -q verify` from `server/`; exit 0 (documentation changes nothing, the run confirms the tree is still green).

```bash
git add CLAUDE.md server/README.md docs
git commit -m "Document PostgreSQL-only, the tools module and the local database

CLAUDE.md, the README, the backlog, the schema page and the two specs describe
the two modules, the one migration, the fixture, scripts/postgres.sh and the
gate's need for an engine; the measurements are in the plan's closing notes."
```

---

## After the tasks

The standing workflow: a review per task during execution, a whole-branch review at the end, one fix wave, the gate green by hand in the development container (`bash scripts/check.sh`, with the engine socket mounted), then `main` fast-forwarded to `dev` with `bash scripts/release.sh`. Push `dev` after each task's commit (`git push -u origin dev`).
---

## Closing notes

Landed on `dev` on 2026-09-18, eleven tasks plus two added during execution (2b and 2c), each with a
review, each ending on a green gate.

### The gate

| | before task 10 | after task 10 |
|---|---|---|
| wall time of `mvn -B -q verify` | 11m41s | 11m38s |
| tests, `forecast-core` | 312 | 312 |
| tests, `forecast-tools` | 125 | 125 |
| failures, errors, skipped | 0, 0, 1 | 0, 0, 1 |

Read from `TEST-*.xml` in both modules. The review's fix wave added one test to `forecast-tools` (the
driver's exit code on an incomplete export), for 438 in 11m44s on the final gate. `dev` before the plan ran
one module and 450 tests; the difference
is what sections 5.2 and 5.3 removed, not coverage silently lost — every deletion was checked against a
live caller first. The single skip is the opt-in `-Dseed.full=true` seed timing.

The suite-level time is 693 s of the 698 s wall (the sum of the `<testsuite time>` attributes; an earlier
grep summed the testcase times as well and produced 1343 s — that figure is wrong, do not quote it).

**Ruling E asked for a gate faster by measured cost; it did not get faster.** The caches of task 10 removed
repeated work, but the delta between 11m41s and 11m38s is noise. The reason is in the distribution: seven
full-pipeline classes hold 94% of the test time — `DefaultForecastServiceTest` 260 s,
`JavaHostIntegrationTest` 181 s, `ForecastRunnerTest` 78 s, `ForecastAutoConfigurationTest` 69 s,
`SampleHostIntegrationTest` 24 s, `FactsBuilderTest` 22 s, `SdkCopilotGatewayTest` 22 s — while the whole of
`forecast-tools` is 11 s and seed generation, which the fixture was expected to save, was only 4.8 s to
begin with. Any future speed-up has to come from those seven: fewer booster fits, or one prepared context
shared across the classes that each build their own.

### The tests that went (against spec section 5.2)

Engine and dead-subject removals, tasks 1 to 5: `DialectTest`; `SchemaFilesTest` (three of its tests were
not about SQLite and were restored in `forecast-tools` at task 8, as `SchemaDumpTest` and
`WorkloadHubSchemaTest`); the stepwise-upgrade half of `ForecastMigrationsTest`; `JdbcRunStoreTest`'s eight
engine wrappers, now the four scenarios they wrapped; `TruthTest` and the twin-equality test of
`WeeklySeriesTest`; two assertions in `AbsencePlannerTest`; the tautological assignment assertion in
`WorkQueueTest`; the codebooks test of `FeatureBuilderTest`; `MetricsTest`'s `mae` assertions, which now
read `Numbers.mae` since `Metrics.mae` was the duplicate.

Redundancy removals, task 10: `WorkQueueTest` parts (a) and (f) of `defaultRatesCoverAllModesSubTasksAndDataIntegrity`
and `someMemberLogsMoreThanADayAndMoreThanAWeek`; two assertions in `SeedGeneratorTest` that
`syntheticModeStillWritesEveryTable` subsumes; five usage assertions plus one in `NarratorTest` that
`UsageTest` owns; the key loop and two `$defs` assertions in `PromptsTest` that `ContractSchemaTest`
subsumes. The "any day over 8.8 h" half of `realisticDataset()` moved onto the fixture as
`FixtureFreshnessTest.someMemberLogsMoreThanADay`, so the count is unchanged in that module.

`realisticDataset()` itself stays: `aMembersWeekHasAShape` still calls it. Section 5.2 is half met there.

### What `forecast-core` stopped shipping

| | before (`c9bdc1c`, `clean package`) | after |
|---|---|---|
| `workloadhub-forecast-core` jar | 438,713 B | 317,615 B |
| entries | 243 | 196 |
| seed, schema or SQLite entries | 38 | 0 |

`workloadhub-forecast-tools` is 134,858 B and is never published.

### The fixture

`server/forecast-core/src/test/resources/fixtures/workloadhub-schema.sql` is 34,564 B and
`seeded-rows.sql` is 2,831,643 B, on disk and as git stores them (`git cat-file -s` on both blobs returns
the same figures: the `-diff` attribute keeps them out of textual diffs, it does not compress them).
`bash server/tools/experiment.sh fixture` regenerates both; `FixtureFreshnessTest` fails the gate when they
drift from the generator.

### The hand check of task 9

Eleven commands from the repository root, Docker as the engine: `postgres.sh up`, `status`,
`experiment.sh init-db`, `init-db` again (refused, exit 2), `seed --synthetic --users 40 --weeks 26`,
`import` (9,603 rows), `export` (9,603 rows), `run-host-example.sh` (29 teams listed, several marked
`TEAM_LEADER`), `run-host-example.sh --team <uuid>`, `postgres.sh stop && up && status` (the 40 seeded
users still there, so the volume survives a stop), `postgres.sh rm --volume` (container and data removed).
The sample host's first lines:

```
acting as Hamza Guessous 38 (this team's leader; a server passes the session's own user)
startRun -> d9a46115-e538-4095-8d6b-886f113ebcab  (run day 2026-09-06, the fixed clock's today)
  progress QUEUED         0%  queued / en attente
  progress BACKTEST      25%  scoring the models / évaluation des modèles
  progress FORECAST      60%  predicting the coming weeks / prévision des semaines à venir
  progress DONE         100%  forecast ready / prévision prête
```

It went on through `getRun` (mae, backtest scores, no overloaded member for that seed and team),
`currentForecast`, `listRuns`, `accuracy` (0 rows: only one run exists, so nothing was forecast before it)
and `copilotStatus` (no token stored, as expected without `--narrate`).

### Deviations from the spec

1. **The volume mounts at `/var/lib/postgresql`, not `/var/lib/postgresql/data`** (section 4.1).
   `postgres:18` moved `PGDATA` into a version subdirectory, and its entrypoint reads a separate mount
   landing on `.../data` as leftover data from before an image upgrade and refuses to start — on an empty
   volume as well. The image's own documentation recommends the single mount at `/var/lib/postgresql`.
   Section 4.1 and task 9 above are corrected.
2. **The fixture is two files, not one** (section 5.3): `workloadhub-schema.sql` and `seeded-rows.sql`, so
   that a test needing only the empty schema does not load 2.8 MB of rows. Both are written by the fixture
   command and both are checked by the freshness test. Section 5.3 is amended.
3. **Two tasks were added, 2b and 2c.** The first PostgreSQL run of the suite found two foreign-key
   failures that existed on unmodified `dev` and that SQLite had hidden, because it never enforced the
   keys: real mode invented a team for `projects.team_id`, and it substituted a task status and type from
   `ReferenceData` that a partial export never carries. Real mode now builds teams from the export's own
   teams and invents none (2b), and refuses an export missing a status or type, naming them (2c). Synthetic
   output stayed byte-identical through both.
4. **Migrations went PostgreSQL-only at task 4, one task before the driver left SQLite at task 5.** The gate
   at that boundary showed two `ExperimentFlowTest` failures, ruled a known ordering gap and closed by task
   5. Nothing user-visible reached `dev` between the two commits in a state anyone ran.
5. **`tools-classpath.sh` resolves with `-pl forecast-tools -am -DskipTests package dependency:build-classpath`**
   rather than a plain `dependency:build-classpath`, because core's snapshot is never installed to the local
   repository and the reactor has to build it first.
6. **The second speed-up of section 5.4 was not done.** `FactsBuilderTest` still fits a booster for the
   three tests that only need a `TeamOutcome` shape; the file is untouched across the branch and the
   whole-branch review found the omission. It stays open in the backlog with the other gate-time work,
   under the seven full-pipeline classes above, where it belongs by measured cost (22 s of 693).

### Rulings during execution

- The seeded fixture is two files, not one: a test needing the empty schema must not load 2.8 MB of rows.
- `forecast-tools` carries its own thirty-line copy of `DatabaseTestSupport`; a test-jar of core for one
  class is heavier. The two copies are kept in step by hand, and the image tag is the one thing that drifts.
- `SqlExportWriterTest`'s foreign-key failure is pre-existing on `dev`, not caused by task 1: reproduced
  three times on the base commit. Fixed as task 2b before task 3 made PostgreSQL mandatory.
- Task 1's gate was accepted as not literally green at that boundary, for the same pre-existing failure.
- The second foreign-key failure behind the same test is fixed as task 2c: real mode refuses an incomplete
  export rather than substituting reference rows the database will reject.
- The plan's `check.sh` engine snippet was wrong — it read `${DOCKER_HOST#unix://}` before testing that the
  variable is set, which aborts the gate under `set -u` wherever `DOCKER_HOST` is unset; fixed in task 3.
- The two `ExperimentFlowTest` failures at task 4 are a plan-ordering gap closed by task 5 (deviation 4).
  In the same test, the migration-history query reads `type = 'SQL'` because Flyway's baseline row carries
  version 0 rather than null.
- `SchemaFilesTest` carried three tests that were not about SQLite; they were restored in `forecast-tools`
  at task 8 rather than lost with the file at task 5.

### Left open

- `realisticDataset()` and the half-met section 5.2, above.
- The deferred minors each review recorded: `devbox.sh status` hardcodes `whf-postgres` in its messages
  while inspecting `$WHF_PG_CONTAINER`, and `WHF_PG_CONTAINER` is undocumented in its header;
  `postgres.sh psql` ignores extra arguments and forces `-it`; `up` on an existing container prints the
  port from the environment rather than the one it was created with; the two `DatabaseTestSupport` copies
  are held equal by a javadoc sentence only; `ExperimentFlowTest`'s stdout capture assumes sequential
  surefire.
- A new backlog item under "Java migration": the duplication folds the 2026-09-17 audits found outside the
  store, about 300 lines of pure refactor (spec section 10).
