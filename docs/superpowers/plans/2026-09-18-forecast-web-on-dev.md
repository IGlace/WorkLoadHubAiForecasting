# The showcase web application on dev — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the showcase web application from `origin/claude/forecast-web-showcase` onto `dev`'s
PostgreSQL-only, two-module world, closing the API hole and the seed defect the showcase exposed on the way.

**Architecture:** `forecast-web` becomes a third Maven module that depends on `forecast-core` alone and
connects to a database the experiment driver filled beforehand — the same shape the WorkloadHub server will
have. The module gains `findRun` and `latestRunOf` so no host needs a shadow run table, and the seed
generator stops draining its own last weeks so a run on the seed's end date has work to forecast.

**Tech Stack:** Java 21, Maven 3.9, Spring Boot 4.1, JUnit 6, jqwik, Flyway, PostgreSQL 18 through
Testcontainers, React + Vite (front end, ported unchanged).

**Spec:** `docs/superpowers/specs/2026-09-18-forecast-web-on-dev-design.md`

## Global Constraints

- **Java 21, Maven 3.9.** Source and target are set in `server/pom.xml`; do not change them.
- **PostgreSQL only.** There is no SQLite anywhere in this repository any more. Never add `sqlite-jdbc`,
  never add a `Dialect`, never write dialect-conditional SQL.
- **`forecast-web` depends on `forecast-core` and nothing else of ours** (spec ruling A). Never add a
  dependency on `forecast-tools` in any scope, including test scope. `forecast-core`'s **test-jar** is
  allowed and is the only extra.
- **`V1` is edited in place; there is no `V2`** (spec ruling E). No database has applied the migration, so
  there is no checksum to break.
- **The gate is `bash scripts/check.sh`, run by hand inside the development container**
  (`bash scripts/devbox.sh shell`). CI is paused; never say "CI will catch it". A container engine (Docker
  or podman) must be reachable or the gate fails rather than skipping.
- **Read test results from `forecast-core/target/surefire-reports/TEST-*.xml` and
  `forecast-tools/target/surefire-reports/TEST-*.xml`**, never by summing the `*.txt` files: a class mixing
  JUnit `@Test` with jqwik `@Property` has both engines overwrite the same `.txt`. Wipe both report
  directories before a run you intend to trust.
- **Test-driven development for every change**; jqwik property tests for arithmetic and population
  invariants.
- **Every text file stays LF** (`.gitattributes`).
- **Commit messages:** imperative subject, short body explaining why. End every commit with:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01SYgKUgQNrUtibf8jGUqAgM
  ```
- **Branch:** all work lands on `dev`. Never push to another branch.
- **The showcase branch is read-only.** Take files from it with
  `git checkout origin/claude/forecast-web-showcase -- <path>`. Never merge it, never rebase it, never
  delete it.

---

## Verification in the session that writes this code (2026-09-18)

This plan is being executed in a remote container, not in `scripts/devbox.sh`. **Java 21, Maven, Node and
npm are present and working; there is no reachable container engine.** Starting one was refused. The owner
runs the gate locally.

**A database test here fails with `no container engine is reachable: the gate needs Docker or podman`.
That message is the environment, NOT a defect in your code. Do not try to fix it, do not weaken the test,
do not reintroduce a skip, and do not report it as a failure.** `DatabaseTestSupport` fails rather than
skips by the owner's ruling of 2026-09-17 (ruling G) and that stays.

Run here, and a failure IS yours to fix:

| what | command |
|---|---|
| compile, all modules, main sources | `mvn -B -q -DskipTests compile` |
| compile, all modules, **test** sources | `mvn -B -q -DskipTests test-compile` |
| Task 1 | `mvn -B -pl forecast-core -Dtest='LeaveDaysTest,LeaveDaysPropertyTest' test` |
| Task 2 | `mvn -B -pl forecast-core -Dtest=DatabaseTestSupportTest test` |
| Task 4 | `mvn -B -pl forecast-tools -Dtest=SeedTailPropertyTest test` |
| Task 5 | `bash server/tools/experiment.sh fixture`, then `mvn -B -pl forecast-tools -Dtest=FixtureFreshnessTest test` |
| Task 7 | `mvn -B -q -pl forecast-web dependency:tree` |
| the front end | `cd server/forecast-web/ui && npm ci && npm run check` |

**`mvn test-compile` over all three modules is mandatory at the end of every task that touches Java**,
including the tasks whose tests cannot run: it is the one check that still catches a wrong signature, a
missing import or a stale name, and it is what stops the owner receiving code that does not build.

Hand over, unrun, clearly labelled in the task's commit body and in the closing notes:

- Task 3 — `JdbcRunStoreTest`, `DefaultForecastServiceTest`, `ForecastMigrationsTest` (all need PostgreSQL)
- Task 9 step 4 — starting the application against a real database
- Task 10 — every `forecast-web` test
- Task 12 — the gate itself

Where a step below says to run one of those, **compile it, state plainly that it was not run and why, and
move on.** Never write "tests pass" for something you did not execute.

---

### Task 1: The leave-days floating-point fix

The showcase branch carries a real fix to two `forecast-core` tests that has nothing to do with the web
module. `dev` has not touched either file since the fork, so it applies unchanged. It lands first and alone.

**Files:**
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/capacity/LeaveDaysPropertyTest.java`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/capacity/LeaveDaysTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. No production code changes.

- [ ] **Step 1: Fetch the showcase branch**

```bash
cd /home/user/WorkLoadHubAiForecasting
git fetch origin claude/forecast-web-showcase
git status --short --branch   # expect: "## dev...origin/dev" and a clean tree
```

- [ ] **Step 2: Read the commit before applying it**

```bash
git show a915ad4
```

Expected: two files changed, 17 insertions, 2 deletions. Read the diff and understand it — it replaces exact
floating-point equality in the leave-days arithmetic with a tolerance comparison. Do not apply a commit you
have not read.

- [ ] **Step 3: Cherry-pick it**

```bash
git cherry-pick a915ad4
```

Expected: applies cleanly, no conflict. If it conflicts, stop and report — something has changed since this
plan was written.

- [ ] **Step 4: Run just these two test classes**

Inside the development container (`bash scripts/devbox.sh shell`):

```bash
cd /work/server && mvn -B -q -pl forecast-core -Dtest='LeaveDaysTest,LeaveDaysPropertyTest' test
```

Expected: PASS, both classes.

- [ ] **Step 5: Amend the commit message to carry the attribution**

```bash
git commit --amend -F - <<'MSG'
fix(test): the leave-days property's own floating-point arithmetic

Cherry-picked from the showcase branch, where it was found. The property
compared accumulated leave hours for exact equality, which a sum of halves
does not satisfy.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SYgKUgQNrUtibf8jGUqAgM
MSG
```

---

### Task 2: `DatabaseTestSupport` reads its fixtures from the classpath

`DatabaseTestSupport.FIXTURES` is the relative path `src/test/resources/fixtures`, resolved against whichever
module's directory the JVM was started in. That works for `forecast-core`'s own tests and for nobody else:
`forecast-web`'s tests will run with `server/forecast-web/` as the working directory, where the path does not
exist. The two files are already test resources, so they are on the test classpath and travel inside the
test-jar. This must land before any `forecast-web` test can run.

**Files:**
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/store/DatabaseTestSupport.java`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/store/DatabaseTestSupportTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `DatabaseTestSupport.runScript(DataSource ds, String resource)` — `resource` is an absolute classpath
    path such as `/fixtures/seeded-rows.sql`. **Replaces** the old `runScript(DataSource, Path)`.
  - `DatabaseTestSupport.SCHEMA_SQL` = `"/fixtures/workloadhub-schema.sql"` (String constant).
  - `DatabaseTestSupport.SEEDED_ROWS_SQL` = `"/fixtures/seeded-rows.sql"` (String constant).
  - `DatabaseTestSupport.postgresWithSchema()` and `postgresMigrated()` keep their signatures.
  - The `FIXTURES` `Path` constant is **removed**. `Experiment`'s `fixture` command writes by path and is
    untouched; only reading moves to the classpath.

- [ ] **Step 1: Write the failing test**

Create `server/forecast-core/src/test/java/com/workloadhub/forecast/store/DatabaseTestSupportTest.java`:

```java
package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * The fixtures are read from the classpath, not from a path relative to the module's working directory:
 * forecast-web's tests reuse this class through forecast-core's test-jar and run with their own module as
 * the working directory, where `src/test/resources/fixtures` does not exist.
 */
class DatabaseTestSupportTest {

    @Test
    void bothFixturesResolveOnTheClasspath() {
        try (InputStream schema = DatabaseTestSupport.class.getResourceAsStream(DatabaseTestSupport.SCHEMA_SQL);
                InputStream rows = DatabaseTestSupport.class.getResourceAsStream(DatabaseTestSupport.SEEDED_ROWS_SQL)) {
            assertNotNull(schema, DatabaseTestSupport.SCHEMA_SQL + " is not on the test classpath");
            assertNotNull(rows, DatabaseTestSupport.SEEDED_ROWS_SQL + " is not on the test classpath");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aMissingResourceSaysWhichOne() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DatabaseTestSupport.runScript(null, "/fixtures/does-not-exist.sql"));
        assertTrue(e.getMessage().contains("/fixtures/does-not-exist.sql"), "message must name the resource: " + e.getMessage());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd /work/server && mvn -B -q -pl forecast-core -Dtest=DatabaseTestSupportTest test
```

Expected: FAIL to compile — `SCHEMA_SQL`, `SEEDED_ROWS_SQL` and `runScript(DataSource, String)` do not exist.

- [ ] **Step 3: Change `DatabaseTestSupport`**

Replace the `FIXTURES` constant and the `runScript` method. The new versions:

```java
    /** The WorkloadHub schema, written by `experiment.sh fixture` and read from the test classpath. */
    public static final String SCHEMA_SQL = "/fixtures/workloadhub-schema.sql";

    /** The seeded rows, written by `experiment.sh fixture` and read from the test classpath. */
    public static final String SEEDED_ROWS_SQL = "/fixtures/seeded-rows.sql";

    /**
     * Runs a whole SQL resource in one statement; the driver splits it on semicolons and honours BEGIN and
     * COMMIT inside it. The resource is read from the classpath, not from the working directory, so a module
     * that reuses this class through forecast-core's test-jar finds it too.
     */
    public static void runScript(DataSource ds, String resource) {
        String sql;
        try (InputStream in = DatabaseTestSupport.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("not on the classpath: " + resource);
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("script failed: " + resource + ": " + e.getMessage(), e);
        }
    }
```

Update `postgresWithSchema()` to call `runScript(ds, SCHEMA_SQL)`. Adjust the imports: add
`java.io.InputStream`, drop `java.nio.file.Files` and `java.nio.file.Path` if nothing else uses them.

- [ ] **Step 4: Update the two other callers**

```bash
grep -rn 'FIXTURES' server/forecast-core/src/test server/forecast-tools/src/test
```

Expected callers: `SeededData.freshDataSource()` uses
`DatabaseTestSupport.runScript(ds, DatabaseTestSupport.FIXTURES.resolve("seeded-rows.sql"))` — change it to
`DatabaseTestSupport.runScript(ds, DatabaseTestSupport.SEEDED_ROWS_SQL)`. Fix every other hit the grep
returns the same way. If `FixtureFreshnessTest` in `forecast-tools` reads by path, **leave it alone**: it
compares the committed file on disk against the generator, which is a path operation by nature.

- [ ] **Step 5: Run the test and the classes that depend on it**

```bash
cd /work/server && mvn -B -q -pl forecast-core -Dtest='DatabaseTestSupportTest,JdbcRunStoreTest,ForecastRepositoryTest' test
```

Expected: PASS, all three.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-core/src/test/java/com/workloadhub/forecast/store/DatabaseTestSupport.java \
        server/forecast-core/src/test/java/com/workloadhub/forecast/store/DatabaseTestSupportTest.java \
        server/forecast-core/src/test/java/com/workloadhub/forecast/testing/SeededData.java
git commit -F - <<'MSG'
test: read the committed fixtures from the classpath

FIXTURES was a path relative to the module's working directory, so only
forecast-core's own tests could resolve it. forecast-web's tests reuse this
class through the test-jar and run from their own module, where that path
does not exist.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SYgKUgQNrUtibf8jGUqAgM
MSG
```

---

### Task 3: `findRun`, `latestRunOf`, and the `requested_by` index

A host running forecasts asynchronously must answer "whose run is this, and for which team?" for a run that
is still computing, so it can decide whether a caller may poll it. `ForecastService` cannot: `getRun` throws
`RUN_NOT_DONE`, `listRuns` takes the team as input, and `RunProgress` carries neither. The showcase worked
around it with a shadow table duplicating four columns `forecast_runs` already holds. The lookup exists and
is public one layer down — `JdbcRunStore.find(UUID)` — it is simply not on the interface.

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/api/ForecastService.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/service/DefaultForecastService.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/store/JdbcRunStore.java`
- Modify: `server/forecast-core/src/main/resources/db/forecast/postgresql/V1__forecast_tables.sql:13`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/store/JdbcRunStoreTest.java`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/service/DefaultForecastServiceTest.java`

**Interfaces:**
- Consumes: `JdbcRunStore.find(UUID) -> Optional<RunSummary>`, `JdbcRunStore.create(RunRequest, LocalDate,
  LocalDateTime) -> UUID`, `JdbcRunStore.NIL` (= `new UUID(0L, 0L)`, the placeholder stored when
  `RunRequest.requestedBy()` is null), `RunSummary(UUID id, UUID teamId, UUID requestedBy, LocalDate asOf,
  RunStatus status, Double mae, String error, LocalDateTime createdAt, LocalDateTime finishedAt)`,
  `RunStatus{QUEUED, RUNNING, DONE, FAILED}`.
- Produces:
  - `ForecastService.findRun(UUID runId) -> Optional<RunSummary>` — the summary whatever the status; empty
    when no such run.
  - `ForecastService.latestRunOf(UUID userId) -> Optional<RunSummary>` — the most recent run that user
    requested, across every team, whatever its status. **Returns empty for `null` and for
    `JdbcRunStore.NIL`**: a run created with no requester is nobody's run, and every such run shares NIL, so
    matching them would collapse all anonymous runs onto one phantom user.
  - `JdbcRunStore.latestOf(UUID userId) -> Optional<RunSummary>`.
  - `forecast_runs_requested_by_idx` on `forecast_runs (requested_by, created_at DESC)`.

- [ ] **Step 1: Write the failing store test**

Append to `JdbcRunStoreTest` (it already has `TEAM`, `USER` and `T0`; add a second team and user constant at
the top of the class beside them):

```java
    static final UUID TEAM2 = UUID.fromString("40000000-0000-0000-0000-000000000002");
    static final UUID USER2 = UUID.fromString("30000000-0000-0000-0000-000000000002");
```

and the test method:

```java
    @Test
    void latestOfFindsTheUsersMostRecentRunAcrossTeamsAndIgnoresTheNilRequester(DataSource ds) {
        JdbcRunStore store = new JdbcRunStore(ds);
        store.create(new RunRequest(TEAM, USER), LocalDate.of(2026, 9, 6), T0);
        UUID newer = store.create(new RunRequest(TEAM2, USER), LocalDate.of(2026, 9, 6), T0.plusMinutes(5));
        store.create(new RunRequest(TEAM, USER2), LocalDate.of(2026, 9, 6), T0.plusMinutes(9));
        store.create(new RunRequest(TEAM, null), LocalDate.of(2026, 9, 6), T0.plusMinutes(20));

        assertEquals(newer, store.latestOf(USER).orElseThrow().id(), "the most recent run, whatever its team");
        assertEquals(RunStatus.QUEUED, store.latestOf(USER).orElseThrow().status(), "whatever its status");
        assertFalse(store.latestOf(UUID.randomUUID()).isPresent(), "a user with no run");
        assertFalse(store.latestOf(JdbcRunStore.NIL).isPresent(), "a run created without a requester is nobody's run");
    }
```

Note the `DataSource ds` parameter: this test class already receives one per method through its existing
extension — copy the annotation pattern from `lifecycle(DataSource ds)` in the same file exactly.

- [ ] **Step 2: Run it to verify it fails**

```bash
cd /work/server && mvn -B -q -pl forecast-core -Dtest=JdbcRunStoreTest test
```

Expected: FAIL to compile — `latestOf` does not exist.

- [ ] **Step 3: Add `latestOf` to `JdbcRunStore`**

Immediately after `list(UUID, int)` (line 119–122):

```java
    /**
     * The most recent run this user requested, across every team. NIL is the placeholder {@link #create}
     * stores for a request with no requester, so every such run would otherwise answer as one phantom user.
     */
    public Optional<RunSummary> latestOf(UUID userId) {
        if (userId == null || NIL.equals(userId)) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM forecast_runs WHERE requested_by = ? ORDER BY created_at DESC, id DESC LIMIT 1")
                .param(userId).query(SUMMARY).optional();
    }
```

- [ ] **Step 4: Add the index to `V1`**

In `server/forecast-core/src/main/resources/db/forecast/postgresql/V1__forecast_tables.sql`, after line 13:

```sql
CREATE INDEX forecast_runs_team_idx ON forecast_runs (team_id, created_at);
CREATE INDEX forecast_runs_requested_by_idx ON forecast_runs (requested_by, created_at DESC);
```

Do **not** create a `V2`. No database has applied `V1` yet (spec ruling E).

- [ ] **Step 5: Assert the index exists, in `ForecastMigrationsTest`**

That class asserts on tables today and on no index at all, so nothing would notice if the new line were
dropped from `V1`. Add:

```java
    @Test
    void forecastRunsCarriesBothItsIndexes(DataSource ds) {
        ForecastMigrations.run(ds);
        List<String> names = JdbcClient.create(ds)
                .sql("SELECT indexname FROM pg_indexes WHERE tablename = 'forecast_runs' ORDER BY indexname")
                .query(String.class).list();
        assertTrue(names.contains("forecast_runs_team_idx"), "listing by team: " + names);
        assertTrue(names.contains("forecast_runs_requested_by_idx"), "latestRunOf has no team to filter on: " + names);
    }
```

Copy the `DataSource ds` parameter style and the imports from the tests already in that file.

- [ ] **Step 6: Run the store and migration tests to verify they pass**

```bash
cd /work/server && mvn -B -q -pl forecast-core -Dtest='JdbcRunStoreTest,ForecastMigrationsTest' test
```

Expected: PASS, both.

- [ ] **Step 7: Write the failing service test**

Append to `DefaultForecastServiceTest`:

```java
    @Test
    void findRunAnswersForARunThatIsNotDoneWhileGetRunStillRefuses() {
        JdbcRunStore store = new JdbcRunStore(SeededData.dataSource());
        UUID id = store.create(new RunRequest(team, member), SeededData.asOf(), LocalDateTime.of(2026, 9, 6, 8, 0));
        store.markRunning(id);

        RunSummary summary = service.findRun(id).orElseThrow();
        assertEquals(team, summary.teamId());
        assertEquals(member, summary.requestedBy());
        assertEquals(RunStatus.RUNNING, summary.status());

        ForecastException e = assertThrows(ForecastException.class, () -> service.getRun(id));
        assertEquals("RUN_NOT_DONE", e.code());

        assertFalse(service.findRun(UUID.randomUUID()).isPresent());
        assertEquals(id, service.latestRunOf(member).orElseThrow().id());
        assertFalse(service.latestRunOf(UUID.randomUUID()).isPresent());
    }
```

Add whatever imports the file is missing (`RunSummary`, `RunStatus`, `ForecastException`, `JdbcRunStore`,
`RunRequest`, `LocalDateTime`, `assertThrows`, `assertFalse`).

- [ ] **Step 8: Run it to verify it fails**

```bash
cd /work/server && mvn -B -q -pl forecast-core -Dtest=DefaultForecastServiceTest test
```

Expected: FAIL to compile — `findRun` and `latestRunOf` are not on `ForecastService`.

- [ ] **Step 9: Add both methods to the interface**

In `ForecastService.java`, after `List<RunSummary> listRuns(UUID teamId, int limit);`:

```java
    /**
     * The run's summary whatever its status, for a host that must decide whether a caller may poll a run
     * that is still computing: {@link #getRun} refuses anything but DONE and {@link #progress} carries
     * neither the team nor the requester. Empty when there is no such run.
     */
    Optional<RunSummary> findRun(UUID runId);

    /**
     * The most recent run this user requested, across every team and whatever its status, for a host
     * enforcing one run at a time. Empty for a user with no run.
     */
    Optional<RunSummary> latestRunOf(UUID userId);
```

- [ ] **Step 10: Implement both in `DefaultForecastService`**

Beside `getRun` (which stays exactly as it is, `RUN_NOT_DONE` included):

```java
    @Override
    public Optional<RunSummary> findRun(UUID runId) {
        return store.find(runId);
    }

    @Override
    public Optional<RunSummary> latestRunOf(UUID userId) {
        return store.latestOf(userId);
    }
```

- [ ] **Step 11: Run both test classes to verify they pass**

```bash
cd /work/server && mvn -B -q -pl forecast-core -Dtest='DefaultForecastServiceTest,JdbcRunStoreTest' test
```

Expected: PASS, both.

- [ ] **Step 12: Check for other implementers of the interface**

```bash
grep -rln 'implements ForecastService' server/
```

Any class the grep finds must gain both methods or it will not compile. Fix each one; a test double can
return `Optional.empty()`.

- [ ] **Step 13: Commit**

```bash
git add server/forecast-core/src/main server/forecast-core/src/test
git commit -F - <<'MSG'
feat: expose findRun and latestRunOf on the module's interface

A host running forecasts asynchronously must know a run's team and
requester to authorise a poll of it, and could not: getRun refuses
anything but DONE, listRuns takes the team as input, and RunProgress
carries neither. The lookup already existed on JdbcRunStore; without it on
the interface every such host keeps a shadow copy of four columns
forecast_runs already holds.

latestRunOf answers empty for the NIL requester, the placeholder a request
without a requester is stored under, so anonymous runs do not collapse
onto one phantom user.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SYgKUgQNrUtibf8jGUqAgM
MSG
```

---

### Task 4: The seed's tail — every week of the window has work

A seeded population drains toward its end date: about 3,000 h a week from March to June, then 500–1,300 h
through August, and tasks created per month falling from 1,171 in June to 84 in September. A run on the
seed's last day therefore forecasts near zero for everybody. Two lines cause it together:
`ProjectPlanner` draws every ACTIVE project's start from the first 60% of the window, and `WorkQueue` gives
a member no work at all in a week where none of their projects is active.

**Files:**
- Modify: `server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/seed/ProjectPlanner.java:106-135`
- Test: `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/seed/SeedTailPropertyTest.java` (create)

**Interfaces:**
- Consumes: `SeedConfig(int weeks, LocalDate end, long seed, boolean synthetic, int users)` with
  `mondays() -> List<LocalDate>`, `lastDay() -> LocalDate`, `firstMonday() -> LocalDate`, and the static
  `SeedConfig.mondayOf(LocalDate) -> LocalDate`; `SeedRandom.between(int a, int b)` and
  `SeedRandom.chance(double p)`; `Project(UUID id, String key, String name, UUID teamId, UUID ownerId,
  String status, LocalDate windowStart, LocalDate windowEnd)` with
  `activeOn(LocalDate day)` = `status.equals("ACTIVE") && !day.isBefore(windowStart) && day.isBefore(windowEnd)`;
  `ExportEnvelope.rows(String table) -> List<LinkedHashMap<String, Object>>`.
- Produces: no new public signature. `ProjectPlanner.plan(...)` keeps its exact signature and returns
  projects whose ACTIVE windows cover every Monday of `cfg.mondays()` for every department.

- [ ] **Step 1: Write the failing property test**

Create `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/seed/SeedTailPropertyTest.java`:

```java
package com.workloadhub.forecast.tools.seed;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.tools.export.ExportEnvelope;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import net.jqwik.api.Property;

/**
 * A seed must be as busy in its last weeks as in its middle, because a run is made on its end date: the
 * forecast a showcase or an experiment looks at covers the weekdays after the seed's last day, and a
 * population that has wound down by then forecasts near zero for everybody, which is arithmetically right
 * and useless to look at.
 *
 * Measured per week, not as a mean over the whole window: WorkFamilyPropertyTest already averages over all
 * 52 weeks, which is exactly why it could not see the taper that a live run on 2026-09-14 found. Fixture
 * size: 120 users, 52 weeks, seed 7 — the showcase's own demo population.
 */
class SeedTailPropertyTest {

    private static final SeedConfig CFG = new SeedConfig(52, LocalDate.of(2026, 9, 6), 7, true, 120);

    /** Weeks at each end that a partial first week or the horizon's own edge makes uneven. */
    private static final int EDGE = 2;

    /** The tail this property is about. */
    private static final int TAIL = 8;

    @Property(tries = 1)
    void theLastWeeksCarryAsMuchWorkAsTheMiddle() {
        ExportEnvelope env = SeedGenerator.generate(null, CFG);
        Map<LocalDate, Double> hoursPerWeek = new TreeMap<>();
        for (LocalDate monday : CFG.mondays()) {
            hoursPerWeek.put(monday, 0.0);
        }
        for (var log : env.rows("time_logs")) {
            LocalDate week = SeedConfig.mondayOf(LocalDate.parse((String) log.get("log_date")));
            hoursPerWeek.computeIfPresent(week, (k, v) -> v + (Double) log.get("hours"));
        }

        var weeks = new java.util.ArrayList<>(hoursPerWeek.entrySet());
        int n = weeks.size();

        // 1. No week inside the window is empty.
        Map<LocalDate, Double> empty = new HashMap<>();
        for (int i = EDGE; i < n - EDGE; i++) {
            if (weeks.get(i).getValue() <= 0.0) {
                empty.put(weeks.get(i).getKey(), weeks.get(i).getValue());
            }
        }
        assertTrue(empty.isEmpty(), "weeks inside the window with no logged hours at all: " + empty);

        // 2. The last eight weeks carry at least 60% of the middle's weekly mean.
        double middle = mean(weeks, EDGE, n - TAIL);
        double tail = mean(weeks, n - TAIL, n - EDGE);
        assertTrue(tail >= 0.60 * middle,
                "the seed tapers: last " + TAIL + " weeks mean " + Math.round(tail) + " h/week against a middle mean of "
                        + Math.round(middle) + " h/week (ratio " + String.format("%.2f", tail / middle) + ")");
    }

    private static double mean(java.util.List<Map.Entry<LocalDate, Double>> weeks, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += weeks.get(i).getValue();
        }
        return sum / (to - from);
    }
}
```

- [ ] **Step 2: Run it to verify it fails, and record the number**

```bash
cd /work/server && mvn -B -q -pl forecast-tools -Dtest=SeedTailPropertyTest test
```

Expected: FAIL on assertion 2, with a ratio well below 0.60 (the backlog entry's figures imply roughly
0.2–0.4). **Write the reported ratio into the task's commit message** — it is the evidence the defect was
real and the measure of the fix.

- [ ] **Step 3: Replace the project-window logic in `ProjectPlanner`**

Inside `plan(...)`, replace the body of the `for (Team team : teams)` loop from `int count = rnd.between(2, 4);`
(line 106) down to the end of the inner `for` loop. The new version decides PLANNING first, deals the ACTIVE
starts across the whole window, and gives each ACTIVE project a duration that reaches past the next one's
start so a department's coverage has no gap:

```java
            int count = rnd.between(2, 4);

            // Which slots are PLANNING, keeping at least one ACTIVE: a department with no active project
            // produces no work at all, which is the defect this guarantee exists to prevent.
            boolean[] planning = new boolean[count];
            int activeCount = 0;
            for (int i = 0; i < count; i++) {
                planning[i] = rnd.chance(1.0 / 6);
                if (!planning[i]) {
                    activeCount++;
                }
            }
            if (activeCount == 0) {
                planning[0] = false;
                activeCount = 1;
            }

            // The active slots' starts, dealt evenly across the WHOLE window. Before 2026-09-18 every
            // ACTIVE project started in its first 60%, so the projects begun early expired through the tail
            // with nothing to replace them and WorkQueue stopped giving those members work at all.
            int lastMonday = mondays.size() - 1;
            int[] startIndex = new int[count];
            int[] nextActiveStart = new int[count];
            int dealt = 0;
            for (int i = 0; i < count; i++) {
                if (!planning[i]) {
                    startIndex[i] = (int) ((long) dealt * lastMonday / activeCount);
                    dealt++;
                }
            }
            int following = lastMonday + 1;
            for (int i = count - 1; i >= 0; i--) {
                if (!planning[i]) {
                    nextActiveStart[i] = following;
                    following = startIndex[i];
                }
            }
            int lastActiveSlot = -1;
            for (int i = 0; i < count; i++) {
                if (!planning[i]) {
                    lastActiveSlot = i;
                }
            }

            for (int i = 0; i < count; i++) {
                Template t = templates.get(i % templates.size());
                int n = 1;
                String key = code + "-" + t.suffix();
                while (used.contains(key)) {
                    n++;
                    key = code + "-" + t.suffix() + n;
                }
                used.add(key);
                LocalDate start;
                LocalDate end;
                String status;
                if (planning[i]) {
                    start = cfg.lastDay().plusWeeks(rnd.between(1, 8));
                    end = start.plusWeeks(rnd.between(12, 40));
                    status = "PLANNING";
                } else {
                    start = mondays.get(startIndex[i]);
                    if (i == lastActiveSlot) {
                        // The department's last project always runs past the as-of date, so the final week
                        // of the window is covered and the horizon's own features have work to measure.
                        end = cfg.lastDay().plusWeeks(1);
                    } else {
                        // At least four weeks past the next project's start: its window opens inside this
                        // one, so the department's coverage is continuous.
                        int minWeeks = Math.max(12, nextActiveStart[i] - startIndex[i] + 4);
                        end = start.plusWeeks(rnd.between(minWeeks, Math.max(40, minWeeks)));
                        if (end.isAfter(cfg.lastDay().plusWeeks(1))) {
                            end = cfg.lastDay().plusWeeks(1);
                        }
                    }
                    status = "ACTIVE";
                }
                UUID owner = team.managerId() != null ? team.managerId() : fallbackOwner;
                out.add(new Project(rnd.uuid(), key, String.format(t.name(), code, n), team.id(), owner,
                        status, start, end));
            }
```

- [ ] **Step 4: Run the property to verify it passes**

```bash
cd /work/server && mvn -B -q -pl forecast-tools -Dtest=SeedTailPropertyTest test
```

Expected: PASS. Record the new ratio for the commit message; it should be near 1.0.

- [ ] **Step 5: Run the rest of the seed's tests**

```bash
cd /work/server && mvn -B -q -pl forecast-tools test
```

Expected: `SeedTailPropertyTest` and `WorkFamilyPropertyTest` PASS. **`FixtureFreshnessTest` will FAIL** —
the generator now produces different bytes from the committed fixture. That is correct and expected; Task 5
fixes it. Any *other* failure is a real regression: investigate before continuing.

- [ ] **Step 6: Document the default in `USAGE`**

In `server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/Experiment.java`, the `USAGE` text
lists `[--end ISO_DATE]` without saying what happens when it is absent. `Experiment.java:190` already
defaults it to `LocalDate.now()`; say so:

```
              seed     --out FILE [--export FILE] [--synthetic] [--users N] [--weeks N]
                       [--end ISO_DATE] [--seed N] [--force]
                       --end defaults to today, so a seed ends on the day it is generated.
```

Match the surrounding indentation exactly.

- [ ] **Step 7: Commit**

```bash
git add server/forecast-tools/src/main server/forecast-tools/src/test
git commit -F - <<'MSG'
fix(seed): give every week of the window an active project

Every ACTIVE project started in the first 60% of the window while
WorkQueue gives a member no work in a week where none of their projects is
active, so the projects begun early expired through the tail with nothing
to replace them and the population wound down to nothing. A run on the
seed's last day forecast near zero for everybody.

Starts are now dealt across the whole window, each project runs at least
four weeks past the next one's start, and a department's last project runs
past the as-of date. A department can no longer be all-PLANNING.

SeedTailPropertyTest measures it per week: the last eight weeks went from
a ratio of RATIO_BEFORE to RATIO_AFTER of the middle's weekly mean.
WorkFamilyPropertyTest averages over all 52 weeks, which is why it could
not see this.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SYgKUgQNrUtibf8jGUqAgM
MSG
```

Replace `RATIO_BEFORE` and `RATIO_AFTER` with the two numbers recorded in steps 2 and 4.

---

### Task 5: Regenerate the committed fixture

Task 4 changed the generator, so the 2.8 MB `seeded-rows.sql` in `forecast-core`'s test resources no longer
matches what the generator produces and `FixtureFreshnessTest` fails. The fixture's parameters do **not**
change — `SeedGenerator.FIXTURE` stays `new SeedConfig(30, LocalDate.of(2026, 9, 6), 11, true, 36)` and
`SeededData.AS_OF` stays `2026-09-06`, because every database test in `forecast-core` needs the same bytes on
every machine on every day.

**Files:**
- Modify: `server/forecast-core/src/test/resources/fixtures/seeded-rows.sql` (generated, 2.8 MB)
- Modify: `server/forecast-core/src/test/resources/fixtures/workloadhub-schema.sql` (generated, 34 KB —
  expected to come out byte-identical; the schema does not depend on the generator)

**Interfaces:**
- Consumes: `SeedGenerator.FIXTURE`, `bash server/tools/experiment.sh fixture`.
- Produces: nothing in code. Later tasks rely on `SeededData` loading a fixture that matches the generator.

- [ ] **Step 1: Confirm the failure you are about to fix**

```bash
cd /work/server && mvn -B -q -pl forecast-tools -Dtest=FixtureFreshnessTest test
```

Expected: FAIL. Read the message — it should be a mismatch between the committed file and the generator's
output, not an error of any other kind. If it is any other kind of failure, stop and report.

- [ ] **Step 2: Regenerate**

```bash
cd /work && bash server/tools/experiment.sh fixture
```

Expected: `Wrote .../workloadhub-schema.sql and .../seeded-rows.sql (N tasks, M time logs)`. Record `N` and
`M` for the commit message.

- [ ] **Step 3: Sanity-check the diff without reading 2.8 MB of it**

```bash
cd /home/user/WorkLoadHubAiForecasting
git diff --stat server/forecast-core/src/test/resources/fixtures/
git diff --numstat server/forecast-core/src/test/resources/fixtures/workloadhub-schema.sql
```

Expected: `seeded-rows.sql` heavily changed; `workloadhub-schema.sql` **unchanged** (no numstat line, or
0/0). If the schema file changed, stop — Task 4 should not have touched the schema, and something else is
wrong.

- [ ] **Step 4: Verify the new fixture actually carries work in its last weeks**

The fixture is 30 weeks ending 2026-09-06, so its last weeks are what Task 4 fixed:

```bash
cd /work && bash scripts/postgres.sh status >/dev/null 2>&1 || bash scripts/postgres.sh up
grep -c "INSERT INTO task_service.time_logs" server/forecast-core/src/test/resources/fixtures/seeded-rows.sql
grep -o "2026-09-0[1-6]" server/forecast-core/src/test/resources/fixtures/seeded-rows.sql | sort | uniq -c
```

Expected: the final week's dates appear on a comparable number of rows to any mid-window week, not a
handful. This is a smoke check, not the guarantee — `SeedTailPropertyTest` is the guarantee.

- [ ] **Step 5: Run both modules' full suites**

```bash
cd /work/server && rm -rf forecast-core/target/surefire-reports forecast-tools/target/surefire-reports
cd /work/server && mvn -B -q verify
```

Expected: everything green, `FixtureFreshnessTest` included. Read the totals from the XML:

```bash
cd /work/server && python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
t=f=e=s=0
for p in glob.glob('*/target/surefire-reports/TEST-*.xml'):
    r=ET.parse(p).getroot()
    t+=int(r.get('tests',0)); f+=int(r.get('failures',0)); e+=int(r.get('errors',0)); s+=int(r.get('skipped',0))
print(f"tests={t} failures={f} errors={e} skipped={s}")
PY
```

Record the totals. If any core test now fails on data grounds (a team that no longer exists, a member with
different hours), that is a real consequence of the new fixture: fix the test to be robust to the data
rather than pinning the old numbers, and say so in the commit.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-core/src/test/resources/fixtures/
git commit -F - <<'MSG'
test: regenerate the committed fixture after the seed's tail fix

The generator changed, so the committed rows no longer matched it and
FixtureFreshnessTest failed. SeedGenerator.FIXTURE keeps its parameters
(30 weeks ending 2026-09-06, seed 11, 36 users) and SeededData.AS_OF stays
2026-09-06: every database test needs the same bytes on every machine on
every day.

N tasks, M time logs. The schema file is unchanged.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SYgKUgQNrUtibf8jGUqAgM
MSG
```

Replace `N` and `M` with the numbers from step 2.

---

### Task 6: Import `server/forecast-web/` unchanged

The bulk of the showcase — 48 front-end files, 26 web Java sources, 11 web tests, 2 resource files — comes
across untouched so that the adaptation reads as its own diff afterwards. **This commit does not compile.**
That is deliberate and is the reason it is a separate task: a reviewer can see exactly what arrived before
seeing what was changed about it.

**Files:**
- Create: `server/forecast-web/**` (everything, from the showcase branch)
- Modify: `server/pom.xml`
- Modify: `scripts/check.sh`
- Modify: `scripts/check.ps1`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: the showcase branch at `origin/claude/forecast-web-showcase`.
- Produces: the `forecast-web` module tree, in the state Tasks 7–10 adapt.

- [ ] **Step 1: Copy the module wholesale**

```bash
cd /home/user/WorkLoadHubAiForecasting
git checkout origin/claude/forecast-web-showcase -- server/forecast-web/
git status --short | head -20
find server/forecast-web -type f | wc -l
```

Expected: about 87 files under `server/forecast-web/` (48 ui, 26 java main, 11 java test, 2 resources, plus
`pom.xml`, `README.md`, `run.sh`). No file outside `server/forecast-web/` touched yet.

- [ ] **Step 2: Add the module to the parent pom**

In `server/pom.xml`, the `<modules>` block becomes:

```xml
  <modules>
    <module>forecast-core</module>
    <module>forecast-tools</module>
    <module>forecast-web</module>
  </modules>
```

Do **not** add a `sqlite.version` property. `dev` removed it and it is not coming back.

- [ ] **Step 3: Add the npm step to `scripts/check.sh`**

Take the showcase's version of the step but keep `dev`'s gate semantics. See the showcase's file for the
exact shape:

```bash
git show origin/claude/forecast-web-showcase:scripts/check.sh
```

Merge by hand: `dev`'s Maven step and its container-engine requirement stay exactly as they are; the npm
step is added after it. Where the showcase's version says the gate skips without npm, keep that — npm is a
front-end tool, not the database engine, and a missing npm must not fail the Java gate. Update the header
comment to describe both steps.

- [ ] **Step 4: Mirror the same change in `scripts/check.ps1`**

```bash
git show origin/claude/forecast-web-showcase:scripts/check.ps1
```

The two scripts must stay in step; the project treats a divergence between them as a defect.

- [ ] **Step 5: Merge the `.gitignore` additions**

```bash
git diff c9bdc1c origin/claude/forecast-web-showcase -- .gitignore
```

Add the showcase's entries (`node_modules`, `ui/dist`, the token key file) to `dev`'s file. Do not remove
any of `dev`'s entries.

- [ ] **Step 6: Confirm it does NOT compile, for the stated reason**

```bash
cd /work/server && mvn -B -q -pl forecast-web -am -DskipTests compile 2>&1 | head -40
```

Expected: FAIL, with errors naming `com.workloadhub.forecast.seed`, `com.workloadhub.forecast.data`,
`com.workloadhub.forecast.store.Dialect` and `WorkloadHubSchema.createSqlite`. Confirm the errors are
*these* and not something else. Write the list down — Tasks 7 and 8 must clear exactly it.

- [ ] **Step 7: Commit the broken state deliberately**

```bash
git add server/forecast-web server/pom.xml scripts/check.sh scripts/check.ps1 .gitignore
git commit -F - <<'MSG'
feat(web): import the showcase module as it stands on its own branch

Copied unchanged from claude/forecast-web-showcase so that the adaptation
to dev's two-module, PostgreSQL-only world reads as its own diff. This
commit does not compile: the module still imports the seed, the export
code and Dialect from where they were before 8a67e79 and 3472c17 moved or
deleted them. The next two commits clear exactly that list.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SYgKUgQNrUtibf8jGUqAgM
MSG
```

---

### Task 7: Point the build at `forecast-core` alone

**Files:**
- Modify: `server/forecast-web/pom.xml`
- Modify: `server/forecast-core/pom.xml`

**Interfaces:**
- Consumes: the imported module from Task 6.
- Produces: a `forecast-web` whose only dependency of ours is `workloadhub-forecast-core` (compile) and its
  `test-jar` (test).

- [ ] **Step 1: Strip `sqlite-jdbc` from the web pom**

In `server/forecast-web/pom.xml`, delete this line entirely:

```xml
    <dependency><groupId>org.xerial</groupId><artifactId>sqlite-jdbc</artifactId></dependency>
```

Leave everything else: `workloadhub-forecast-core`, the Spring Boot starters, `postgresql` at runtime scope,
the test dependencies and the `spring-boot-maven-plugin`. Do **not** add `forecast-tools` in any scope.

- [ ] **Step 2: Add the test-jar execution to the core pom**

At the end of `server/forecast-core/pom.xml`, before `</project>`:

```xml
  <build>
    <plugins>
      <!-- forecast-web's tests reuse SeededData, DatabaseTestSupport and FakeGateway from this module's tests. -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
        <executions>
          <execution>
            <goals><goal>test-jar</goal></goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
```

If `forecast-core/pom.xml` already has a `<build>` block, add the plugin inside the existing one rather than
opening a second.

- [ ] **Step 3: Verify the dependency graph has no `forecast-tools` and no sqlite**

```bash
cd /work/server && mvn -B -q -pl forecast-web dependency:tree 2>&1 | grep -iE 'sqlite|forecast-tools' || echo "clean: neither appears"
```

Expected: `clean: neither appears`. If either shows up, the build is violating spec ruling A — fix it before
going on.

- [ ] **Step 4: Commit**

```bash
git add server/forecast-web/pom.xml server/forecast-core/pom.xml
git commit -F - <<'MSG'
build(web): depend on forecast-core alone

forecast-tools exists so the seed and the driver stay out of what a host
consumes, and the showcase is a host. sqlite-jdbc goes with it: there is
one engine. Core publishes a test-jar so the web module's tests can reuse
SeededData, DatabaseTestSupport and FakeGateway, which is test scope and
ships nothing.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SYgKUgQNrUtibf8jGUqAgM
MSG
```

---

### Task 8: Delete the seeding and the dialect, and use `findRun`

This is the adaptation proper. Three classes go, one shrinks, and the `Dialect` parameter that existed only
to paper over two engines' placeholder syntax disappears from every signature that carried it.

**Files:**
- Delete: `server/forecast-web/src/main/java/com/workloadhub/forecastweb/demo/DemoDatabase.java`
- Delete: `server/forecast-web/src/main/java/com/workloadhub/forecastweb/host/RunRegistry.java`
- Delete: `server/forecast-web/src/test/java/com/workloadhub/forecastweb/demo/DemoDatabaseTest.java`
- Delete: `server/forecast-web/src/test/java/com/workloadhub/forecastweb/host/RunRegistryTest.java`
- Modify: `server/forecast-web/src/main/java/com/workloadhub/forecastweb/demo/DemoConfiguration.java` → rename to `ShowcaseConfiguration.java`
- Modify: `server/forecast-web/src/main/java/com/workloadhub/forecastweb/host/ForecastAccess.java`
- Modify: `server/forecast-web/src/main/java/com/workloadhub/forecastweb/host/HostForecastFacade.java`
- Modify: `server/forecast-web/src/main/java/com/workloadhub/forecastweb/host/HostConfiguration.java`
- Modify: `server/forecast-web/src/main/java/com/workloadhub/forecastweb/ForecastWebProperties.java`
- Modify: `server/forecast-web/src/main/java/com/workloadhub/forecastweb/ForecastWebApplication.java`
- Modify: `server/forecast-web/src/main/java/com/workloadhub/forecastweb/api/SystemController.java`
- Modify: `server/forecast-web/src/main/java/com/workloadhub/forecastweb/api/Json.java`
- Modify: `server/forecast-web/src/main/java/com/workloadhub/forecastweb/host/Directory.java`

**Interfaces:**
- Consumes: `ForecastService.findRun(UUID) -> Optional<RunSummary>` and
  `ForecastService.latestRunOf(UUID) -> Optional<RunSummary>` from Task 3; `RunSummary.teamId()`,
  `.requestedBy()`, `.status()`, `.id()`.
- Produces:
  - `ShowcaseConfiguration` with exactly two beans: `DemoClock forecastClock(ForecastWebProperties)` and
    `UiResources uiResources(ForecastWebProperties)`. No `DataSource` bean — Spring Boot's own
    autoconfiguration supplies it from `spring.datasource.*`.
  - `ForecastAccess(JdbcClient jdbc)` — one argument. The `Dialect` parameter is gone.
  - `ForecastWebProperties` without `database` and without the `Seed` nested class.

- [ ] **Step 1: Delete the four files**

```bash
cd /home/user/WorkLoadHubAiForecasting
git rm server/forecast-web/src/main/java/com/workloadhub/forecastweb/demo/DemoDatabase.java \
       server/forecast-web/src/main/java/com/workloadhub/forecastweb/host/RunRegistry.java \
       server/forecast-web/src/test/java/com/workloadhub/forecastweb/demo/DemoDatabaseTest.java \
       server/forecast-web/src/test/java/com/workloadhub/forecastweb/host/RunRegistryTest.java
```

- [ ] **Step 2: Rewrite `DemoConfiguration` as `ShowcaseConfiguration`**

```bash
git mv server/forecast-web/src/main/java/com/workloadhub/forecastweb/demo/DemoConfiguration.java \
       server/forecast-web/src/main/java/com/workloadhub/forecastweb/demo/ShowcaseConfiguration.java
```

The whole file becomes:

```java
package com.workloadhub.forecastweb.demo;

import com.workloadhub.forecastweb.ForecastWebProperties;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Showcase only: the two beans that let this host serve a front end and move its own clock. The DataSource
 * is Spring Boot's own, from spring.datasource.*, exactly as it will be on the WorkloadHub server: this
 * application never seeds and never creates a database. The work tables are filled beforehand by
 * `server/tools/experiment.sh` (server/forecast-web/README.md).
 */
@Configuration(proxyBeanMethods = false)
public class ShowcaseConfiguration {

    /** Replaces the module's default clock; the date is pinned to {@code forecast-web.clock.today} when set. */
    @Bean
    DemoClock forecastClock(ForecastWebProperties properties) {
        String today = properties.getClock().getToday();
        return new DemoClock(ZoneId.systemDefault(), today == null || today.isBlank() ? null : LocalDate.parse(today.trim()));
    }

    @Bean
    UiResources uiResources(ForecastWebProperties properties) {
        return new UiResources(Path.of(properties.getUiDir()));
    }
}
```

- [ ] **Step 3: Drop `Dialect` from `ForecastAccess`**

Remove the `import com.workloadhub.forecast.store.Dialect;`, the `private final Dialect dialect;` field, the
constructor parameter and its assignment. The query becomes a plain placeholder with a real `UUID`
parameter:

```java
    public String roleOf(UUID userId) {
        return jdbc.sql("SELECT role FROM users WHERE id = ?").param(userId).query().listOfRows().stream()
```

Keep the `Decision` record and the reason strings — those are the web copy's own feature, not a dialect
workaround. Apply the same treatment to any other `dialect.placeholder(...)` call in the module:

```bash
grep -rn 'dialect\|Dialect' server/forecast-web/src
```

Every hit must be gone by the end of this step.

- [ ] **Step 4: Replace `RunRegistry` with `findRun` in `HostForecastFacade`**

Wherever the facade called `registry.teamOf(runId)`, call `service.findRun(runId).map(RunSummary::teamId)`.
Wherever it called `registry.latestRunOf(userId)`, call
`service.latestRunOf(userId).map(RunSummary::id)`. Delete the `registry.register(...)` call in `startRun`
outright — the module writes the `forecast_runs` row inside `startRun` itself, so there is nothing left to
register, and the ordering bug the showcase recorded in its backlog stops existing. Delete the `RunRegistry`
field and constructor parameter.

- [ ] **Step 5: Drop the `RunRegistry` bean from `HostConfiguration`**

Remove the `@Bean RunRegistry ...` method and any `RunRegistry` argument from the `HostForecastFacade` bean
method.

- [ ] **Step 6: Strip the dead properties**

In `ForecastWebProperties`, delete the `database` field with its getter and setter, and delete the whole
`Seed` nested class with its `seed` field, getter and setter. Keep `clock`, `uiDir` and everything else.
Then:

```bash
grep -rn 'getDatabase\|getSeed()\|ForecastWebProperties.Seed' server/forecast-web/src
```

Fix every remaining hit — `ForecastWebApplication` and `SystemController` are the expected ones.

- [ ] **Step 7: Repoint the web module's JSON mapper at `forecast-core`**

`server/forecast-web/src/main/java/com/workloadhub/forecastweb/api/Json.java` imports
`com.workloadhub.forecast.data.ExportFiles` and calls `ExportFiles.mapper()`. `ExportFiles` is in
**`forecast-tools`** now, so this one line would drag the forbidden dependency back in through the front
door. `35bbcf3` moved the module's Jackson mapper to `com.workloadhub.forecast.Json`, in `forecast-core`,
with the same `mapper()` accessor.

The web class is itself called `Json`, so its own simple name shadows the one it needs: import it and the
file will not compile. Use the fully-qualified name and no import:

```java
package com.workloadhub.forecastweb.api;

import tools.jackson.databind.JsonNode;

/** Parses the JSON strings the module stores (facts, narrative, verification, usage) so the response carries objects. */
final class Json {

    private Json() {
    }

    static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        // Fully qualified: this class's own name shadows the module's, which cannot therefore be imported.
        return com.workloadhub.forecast.Json.mapper().readTree(json);
    }
}
```

- [ ] **Step 8: Compile**

```bash
cd /work/server && mvn -B -q -pl forecast-web -am -DskipTests compile
```

Expected: SUCCESS. Every error from Task 6 step 6 must be gone. Tests are still broken; Task 10 fixes them.

- [ ] **Step 9: Commit**

```bash
git add -A server/forecast-web
git commit -F - <<'MSG'
refactor(web): drop the seeding, the dialect and the shadow run table

DemoDatabase seeded a SQLite file through the seed generator, which is now
in forecast-tools and which a host must not depend on; the application
reads a database the driver filled. Dialect existed only to choose between
two engines' placeholder syntax and there is one engine. RunRegistry
duplicated four columns of forecast_runs because ForecastService could not
answer them for a run that was not DONE; findRun now can, so the table and
its register-after-start ordering bug both go.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SYgKUgQNrUtibf8jGUqAgM
MSG
```

---

### Task 9: Configuration, the system clock, and `run.sh`'s pre-flight

**Files:**
- Modify: `server/forecast-web/src/main/resources/application.yml`
- Modify: `server/forecast-web/run.sh`

**Interfaces:**
- Consumes: `ShowcaseConfiguration.forecastClock` from Task 8, which treats a blank
  `forecast-web.clock.today` as "follow the system date".
- Produces: an application whose default database is the local PostgreSQL and whose default clock is today.

- [ ] **Step 1: Point the datasource at the local PostgreSQL and unpin the clock**

In `application.yml`, remove the `forecast-web.database` and `forecast-web.seed.*` keys entirely, and set:

```yaml
spring:
  datasource:
    url: ${FORECAST_WEB_DB_URL:jdbc:postgresql://localhost:5432/workloadhub}
    username: ${FORECAST_WEB_DB_USER:workloadhub}
    password: ${FORECAST_WEB_DB_PASSWORD:workloadhub}

forecast-web:
  clock:
    today: ${FORECAST_WEB_TODAY:}   # blank: follow the system date. The seed ends on the day it was generated.
    adjustable: true
```

Keep every other key the file already had. The `2026-06-28` pin and the comment about the seed's last weeks
tapering are both deleted — Task 4 removed the reason for them.

- [ ] **Step 2: Rewrite `run.sh`'s header and add the pre-flight**

Delete every mention of a database file, of `FORECAST_WEB_DB` as a path, and of deleting the file to seed
again. Add, immediately after the `set -euo pipefail` line and before the npm block:

```bash
db_url="${FORECAST_WEB_DB_URL:-jdbc:postgresql://localhost:5432/workloadhub}"
if ! bash "$server/../scripts/postgres.sh" status >/dev/null 2>&1; then
    cat >&2 <<'EOT'
The local PostgreSQL is not running, and this application does not create or seed a database.

    bash scripts/postgres.sh up
    bash server/tools/experiment.sh init-db
    bash server/tools/experiment.sh seed --synthetic --users 40 --out /tmp/seed.json
    bash server/tools/experiment.sh import /tmp/seed.json

Then run this script again. Point it elsewhere with FORECAST_WEB_DB_URL.
EOT
    exit 1
fi
```

Keep the npm staleness check, the Maven staleness check and the final `java -jar` exactly as they are.

- [ ] **Step 3: Check the script parses and its guidance is right**

```bash
bash -n server/forecast-web/run.sh && echo "syntax ok"
grep -n 'sqlite\|\.db\|delete the database file' server/forecast-web/run.sh || echo "no stale database-file wording"
```

Expected: both lines print their success message.

- [ ] **Step 4: Start it against a real database and see a page**

```bash
cd /work && bash scripts/postgres.sh up
bash server/tools/experiment.sh init-db
bash server/tools/experiment.sh seed --synthetic --users 40 --out /tmp/seed.json
bash server/tools/experiment.sh import /tmp/seed.json
bash server/forecast-web/run.sh --server.port=8080 &
sleep 40
curl -sS http://localhost:8080/api/system | head -c 400
```

Expected: JSON naming the seeded admin, and a today that is the real system date. Note that the seed was
generated without `--end`, so its last day is today and the forecast has work to cover. Stop the server
afterwards.

- [ ] **Step 5: Commit**

```bash
git add server/forecast-web/src/main/resources/application.yml server/forecast-web/run.sh
git commit -F - <<'MSG'
feat(web): read the local PostgreSQL and the system clock

The application connects to a database the driver filled and creates
nothing. The clock is no longer pinned to 2026-06-28: that date existed
because the seed's last weeks tapered, and they no longer do, so a seed
ends today and the showcase reads the system date.

run.sh prints the four commands that prepare the database instead of
letting a connection stack trace be the first thing a reader sees.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SYgKUgQNrUtibf8jGUqAgM
MSG
```

---

### Task 10: The web module's tests onto the committed fixture

**Files:**
- Modify: `server/forecast-web/src/test/java/com/workloadhub/forecastweb/TestBeans.java`
- Modify: `server/forecast-web/src/test/java/com/workloadhub/forecastweb/ForecastWebIntegrationTest.java`
- Modify: `server/forecast-web/src/test/java/com/workloadhub/forecastweb/SeededUsers.java`
- Modify: `server/forecast-web/src/test/java/com/workloadhub/forecastweb/host/ForecastAccessTest.java`
- Modify: `server/forecast-web/src/test/java/com/workloadhub/forecastweb/host/HostForecastFacadeTest.java`

**Interfaces:**
- Consumes: `SeededData.freshDataSource() -> DataSource` (a Testcontainers PostgreSQL with the WorkloadHub
  tables, the committed seeded rows and the module's own tables), `SeededData.dataSource()` (the JVM's
  shared copy — read it, or write only what the test clears again), `SeededData.asOf() -> LocalDate` (=
  `2026-09-06`), `DatabaseTestSupport.postgresMigrated()`, `FakeGateway`, all from `forecast-core`'s
  test-jar.
- Produces: a green `mvn -pl forecast-web test`.

- [ ] **Step 1: Replace the SQLite fixture in `TestBeans`**

Wherever a test built a SQLite file and seeded it, the `DataSource` becomes
`SeededData.freshDataSource()` for a test that writes, or `SeededData.dataSource()` for one that only reads.
Delete every `SQLiteDataSource`, every `WorkloadHubSchema.createSqlite` and every `SeedGenerator` call:

```bash
grep -rn 'SQLite\|sqlite\|SeedGenerator\|SeedConfig\|ExportImporter\|WorkloadHubSchema' server/forecast-web/src/test
```

Every hit must be gone by the end of this task.

- [ ] **Step 2: Pin the tests' clock to the fixture's last day**

The fixture ends `2026-09-06`, so any test asserting on forecast values must run with that as "today",
whatever the production default now is:

```java
Clock.fixed(SeededData.asOf().atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
```

This is the same construction `DefaultForecastServiceTest.build` uses — copy it. Production following the
system clock (Task 9) and tests pinning the fixture's date are not in conflict: one reads a database that
ends today, the other reads a fixture that ends 2026-09-06.

- [ ] **Step 3: Run the web tests**

```bash
cd /work/server && mvn -B -q -pl forecast-web -am test
```

Expected: PASS. A failure naming a user or team id that no longer exists means the test pinned a value from
the old SQLite seed: replace the pin with a lookup through `SeededUsers`, do not re-pin a new constant.

- [ ] **Step 4: Prove the module does not reach into the tools module even in tests**

```bash
cd /work/server && mvn -B -q -pl forecast-web dependency:tree 2>&1 | grep -iE 'forecast-tools|sqlite' || echo "clean"
grep -rn 'com.workloadhub.forecast.tools' server/forecast-web/src || echo "no tools imports"
```

Expected: `clean` and `no tools imports`.

- [ ] **Step 5: Commit**

```bash
git add server/forecast-web/src/test
git commit -F - <<'MSG'
test(web): read the committed fixture instead of seeding SQLite

The tests built a SQLite file through the seed generator, which lives in
forecast-tools and which this module must not depend on in any scope. They
now take a Testcontainers PostgreSQL loaded from forecast-core's committed
fixture, through its test-jar, and pin their clock to the fixture's last
day.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SYgKUgQNrUtibf8jGUqAgM
MSG
```

---

### Task 11: Documentation and the backlog

The showcase branch's three documentation commits are **not** replayed: they describe a module with no
`forecast-tools` and a SQLite-capable core, and one of them records that the work sits on its own branch,
which stops being true here.

**Files:**
- Create: `server/forecast-web/README.md` (rewrite in place)
- Modify: `server/README.md`
- Modify: `CLAUDE.md`
- Modify: `docs/backlog.md`

**Interfaces:**
- Consumes: everything Tasks 1–10 produced.
- Produces: documentation that matches the code.

- [ ] **Step 1: Rewrite the web README**

Read the showcase's version for the material that is still true — the role rules, the page inventory, the
REST surface, the no-authentication warning:

```bash
git show origin/claude/forecast-web-showcase:server/forecast-web/README.md
```

Rewrite the setup half: the four commands that prepare the database, the fact that the application creates
nothing, the property table with `forecast-web.database` and `forecast-web.seed.*` gone and
`forecast-web.clock.today` documented as blank-means-today, and reseeding as
`bash scripts/postgres.sh psql -c 'DROP SCHEMA task_service CASCADE'` followed by `init-db`, `seed` and
`import` again. Delete every mention of a file to delete.

- [ ] **Step 2: Add the showcase to `server/README.md`**

A section beside "Running experiments", pointing at the same local PostgreSQL that
`run-host-example.sh` already documents. Say plainly that `forecast-web` depends on `forecast-core` only and
why.

- [ ] **Step 3: Update `CLAUDE.md`**

Three places: the layout block gains `forecast-web`; the "Where the project stands" narrative gains a
paragraph for this work; and the opening description mentions the showcase. Include in that paragraph the
seed's tail fix and that `--end` defaults to today, because both change what a reader should expect from a
fresh seed.

- [ ] **Step 4: Rewrite the backlog's "Java migration" section**

Two entries from the showcase branch are **closed by this work and must not be copied in**: the run registry
outside Flyway, and registering a run after starting it. Both died with `RunRegistry` in Task 8.

One is **carried across, rewritten for one engine**:

```markdown
- **`forecast-web` narration keys clear only when the call returns (2026-09-18).** The in-flight set that
  refuses a second narration of the same run and language is cleared in the submitted task's `finally`, so a
  Copilot call that never returns would keep that run and language refused for the life of the process
  (`close()` clears the set, which only helps at shutdown). The module's own `whf.copilot.timeout-seconds`
  bounds the call, so this needs the SDK to hang past its own timeout; a start timestamp per key, treating an
  entry older than the timeout as free, would close it.
```

One is **closed and recorded as closed**: the seed's taper. State that it was not an intended wind-down and
name the two lines that caused it.

One is **opened**:

```markdown
- **The showcase's host layer duplicates the sample host in `forecast-core`'s tests (2026-09-18).**
  `samplehost/ForecastAccess` (58 lines) and `samplehost/HostForecastFacade` (152) in core's test tree
  against `forecastweb/host/ForecastAccess` and `forecastweb/host/HostForecastFacade`, which add reason
  strings a page can show. Until 2026-09-18 they also differed by a `Dialect` parameter; with one engine
  that difference is gone and keeping both is now a choice rather than an accident.
```

- [ ] **Step 5: Check every factual claim you just wrote**

```bash
grep -rn 'sqlite\|SQLite\|DemoDatabase\|RunRegistry\|2026-06-28' server/forecast-web/README.md server/README.md CLAUDE.md docs/backlog.md
```

Expected: no hits except where a sentence is deliberately describing history. This project treats a false
documentation statement as a defect worth its own fix commit; do not leave one behind.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-web/README.md server/README.md CLAUDE.md docs/backlog.md
git commit -F - <<'MSG'
docs: describe the showcase as it lands on dev

Written against dev's state rather than replayed from the showcase branch,
whose documentation describes a module with no forecast-tools and a
SQLite-capable core. The two backlog entries findRun closed are not copied
in, the seed's taper is recorded as closed with its cause, and the
duplication between the web host layer and core's test sample host is
opened.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SYgKUgQNrUtibf8jGUqAgM
MSG
```

---

### Task 12: Whole-branch review, one fix wave, and the gate

The project's standing workflow: a review over everything the branch did, one fix wave, then the gate green
by hand before anything is pushed.

**Files:** whatever the review finds.

**Interfaces:**
- Consumes: Tasks 1–11.
- Produces: a pushable `dev`.

- [ ] **Step 1: Read the whole diff**

```bash
cd /home/user/WorkLoadHubAiForecasting
git log --oneline cd9a4d4..HEAD
git diff cd9a4d4..HEAD --stat
```

- [ ] **Step 2: Run the review**

Use the `requesting-code-review` skill over the whole range. Give the reviewer the spec
(`docs/superpowers/specs/2026-09-18-forecast-web-on-dev-design.md`) and this plan as context, and ask
specifically about: whether `forecast-web` reaches `forecast-tools` anywhere; whether any SQLite or
`Dialect` remnant survives; whether `latestRunOf`'s NIL handling is right; and whether the new
`ProjectPlanner` arithmetic can produce a gap in coverage for any `count` from 2 to 4.

- [ ] **Step 3: Apply one fix wave**

One commit, not one per finding, unless a finding is large enough to deserve its own. Findings that are
genuinely out of scope go to `docs/backlog.md` instead of being fixed.

- [ ] **Step 4: Run the gate, clean**

Inside the development container:

```bash
cd /work/server && rm -rf forecast-core/target/surefire-reports forecast-tools/target/surefire-reports forecast-web/target/surefire-reports
cd /work && bash scripts/check.sh
```

Expected: exit 0. Read the totals from the XML across all three modules, never by summing the `.txt` files:

```bash
cd /work/server && python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
t=f=e=s=0
for p in glob.glob('*/target/surefire-reports/TEST-*.xml'):
    r=ET.parse(p).getroot()
    t+=int(r.get('tests',0)); f+=int(r.get('failures',0)); e+=int(r.get('errors',0)); s+=int(r.get('skipped',0))
print(f"tests={t} failures={f} errors={e} skipped={s}")
PY
```

Record the figure. The baseline before this work was 437 tests (core 312, tools 125), 0 failures, 0 errors,
1 skipped.

- [ ] **Step 5: Record the gate figure in the plan's closing notes**

Append a `## Closing notes` section to this file with: the gate figure, what the review found and what was
fixed, anything left open and where it was recorded, and the two seed ratios from Task 4.

- [ ] **Step 6: Push**

```bash
git push -u origin dev
```

Retry up to four times with exponential backoff (2s, 4s, 8s, 16s) on network failure only.

- [ ] **Step 7: Ask before releasing**

Do **not** fast-forward `main`. `bash scripts/release.sh` is the owner's call; report the gate figure and
ask.
