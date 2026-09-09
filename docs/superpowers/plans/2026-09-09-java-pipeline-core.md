# Java Forecast Pipeline Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the deterministic forecasting core to Java on the WorkloadHub schema: read the tables into typed rows, derive each task's lifecycle dates and actual hours, compute calendars and capacity, build the 46-column feature matrix per horizon, run the seasonal-naive floor and the XGBoost arrival model, score them in the rolling backtest, place open and new hours with the effort model, and allocate the unassigned backlog with the planned-work rule.

**Architecture:** Pure functions over immutable Java records, no Spring beans, no database writes. `ForecastRepository` is the only class that talks to JDBC; everything after it takes `ForecastData` and returns values, so every unit is testable on hand-built rows and on the seed generator's output. The next plan wires these into the run, persistence, facts, CLI and the parity check.

**Tech Stack:** Java 21, Maven, Spring JDBC (`JdbcClient`), XGBoost4J 3.4.0, JUnit 6, jqwik. Existing code from the foundation plan: `store.Dialect`, `store.WorkloadHubSchema`, `data.ExportImporter`, the `seed` package (`SeedGenerator.generate(null, cfg)` gives a full synthetic dataset in memory), `DatabaseTestSupport`.

**Spec:** `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md` sections 5 (data rules), 6 (feature matrix), 7 (models), 8 (backtest), 9 steps 3 to 5 (placement, planned work); `docs/design/2026-09-08-workloadhub-schema-and-feature-matrix.md` sections 3 to 6 (column meanings); `docs/superpowers/specs/2026-09-07-planned-work-and-likely-work-design.md` section 5 (allocation); `docs/design/2026-09-07-forecasting-internals.md` (the Python algorithms, the parity oracle).

## Global Constraints

- The language model never produces a forecast number; everything here is deterministic code. Demand is never capped by capacity.
- Weeks start on Monday; working days are Monday to Friday minus holidays with `status = 'CONFIRMED'` and `active = true`; dates ISO 8601.
- Counted members: active users with role `MEMBER` or `TEAM_LEADER` linked through `team_members`; `SKILL_TEAM_LEADER`, `CENTER_MANAGER`, `ADMIN`, `VIEWER` never counted.
- Assignment date: `changed_at` of the latest `task_history` row with `field_name = 'assignee'` whose `new_value` resolves to the current assignee (UUID, else email, else `full_name`, team members first, then all users; `None`, empty, `null` mean unassigned); else `created_date`. Ambiguous or unresolvable values fall back to `created_date` and are counted.
- Actual hours: sum of `time_logs.hours` of the task by its assignee; a finished task with no logs uses `original_estimate_hrs − remaining_estimate_hrs` and is flagged unlogged.
- Type families: delivery (Story, New Feature, Task, Improvement, Change Request), defect (Bug, Incident), container (Epic), support (Spike, Test, Risk); Sub-task takes its parent's family. Assignment mode: self-picked when reporter = assignee, project when `parent_task_id` is set, else manual.
- Capacity: `user_capacity.available_hrs` for the week when the row exists; else the member's latest `base_capacity_hrs`, else `whf.default-weekly-hours` (40), times working days over 5, minus the member's `absences.hours` in the week.
- The arrival series and every target is `fresh_hours`: estimated hours of arrivals with assignment lag below `BACKLOG_LAG_DAYS = 2`; `est_hours` (all arrivals) is kept for facts.
- Feature matrix: the 46 columns of Task 5 and 6 per horizon, horizons 1, 2, 3; every column computed from data dated on or before the row's week except the `_h{h}` columns, which describe the target week from facts known at the row's week. No leakage.
- Models: `seasonal_naive` floor and `xgboost`; XGBoost parameters exactly `objective=count:poisson, tree_method=hist, max_bin=255, eta=0.05, num_round=300, max_leaves=31, grow_policy=lossguide, max_depth=0, min_child_weight=1, lambda=1, max_delta_step=0.7, seed=0, nthread=1`, missing = NaN, predictions clipped at 0; categorical feature types when the build supports them.
- Backtest: origins `as_of_origin − 2k` weeks, `k = 1..6`, kept when at least 13 weeks precede them; train rows `week ≤ origin − max(h)` weeks; MAE and MASE against the floor; residuals pooled per model and horizon; a model unavailable at any origin loses everything; champion = lowest mean MASE, the floor when that is at or above 1.0.
- Effort model: shrink `k = 5` toward team then global; ratio clipped to [0.5, 2.5]; cycle at least 1 day; open hours = `remaining_estimate_hrs`; new hours = prediction × ratio spread over the member's cycle days; hours land on the member's present working days.
- Planned work: candidates = unassigned tasks of the team's projects older than `BACKLOG_LAG_DAYS`; share weights over a 26-week window with shrink `k = 3` through levels (project, family), (project), (team, family), equal split; expected assignment = `created + lag` with lag = median lag of the project (≥5 tasks), else team, else all, else 2 days, floored at the first forecast week; hours after the second forecast week are reported, not counted.
- Determinism: no unordered iteration feeds an output; members and weeks sorted; XGBoost single-threaded with seed 0.
- Base package `com.workloadhub.forecast`; tests JUnit 6 + jqwik; `mvn -B -q verify` in `server/` under three minutes without Docker; no model identifiers in any file; commit messages with an imperative subject and a short body; stage files by path; never `--no-verify`.

---

## File structure

```text
server/forecast-core/src/main/java/com/workloadhub/forecast/
  data/rows/MemberRow.java, TeamRow.java, ProjectRow.java, TaskRow.java, TransitionRow.java,
            TimeLogRow.java, CapacityRow.java, AbsenceRow.java, HolidayRow.java, UserRef.java   (Task 1)
  data/ForecastData.java                    the loaded snapshot + lookups                         (Task 1)
  data/ForecastRepository.java              JDBC reads (JdbcClient + Dialect)                    (Task 1)
  calendar/Weeks.java                       Monday arithmetic                                     (Task 2)
  calendar/WorkingCalendar.java             holidays, working days, off days                       (Task 2)
  calendar/HourPlacement.java               placeHours over working days into weeks               (Task 2)
  capacity/CapacityRule.java                available hours per member and week                   (Task 2)
  lifecycle/Family.java, Mode.java          enums                                                  (Task 3)
  lifecycle/TaskFacts.java                  derived dates, actual hours, family, mode, lag         (Task 3)
  lifecycle/Lifecycle.java                  derive(ForecastData) -> facts, data-quality lists      (Task 3)
  lifecycle/Truncation.java                 the database as of a past day (replay)                 (Task 6)
  features/MemberWeek.java                  row key                                                (Task 4)
  features/WeeklySeries.java                arrivals per member and week (est, fresh, count)       (Task 4)
  features/FeatureMatrix.java               columns, rows, codebooks, subsets, DMatrix export      (Task 4)
  features/Features.java                    column names per horizon, constants                    (Task 4)
  features/FeatureBuilder.java              the 46 columns                                          (Task 5, 6)
  model/ArrivalModel.java, ModelUnavailable.java                                                    (Task 7)
  model/SeasonalNaive.java                                                                          (Task 7)
  model/XgboostArrival.java                                                                         (Task 7)
  backtest/Backtest.java                    rolling backtest, MASE, champion, interval bounds       (Task 8)
  model/EffortModel.java                    ratios, cycles, lateness, placement                     (Task 9)
  planned/PlannedWork.java                  share weights, lag, allocation                           (Task 10)
server/forecast-core/src/test/java/com/workloadhub/forecast/...                                    per task
server/forecast-core/src/test/java/com/workloadhub/forecast/testing/SeededData.java                 shared fixture (Task 1)
server/forecast-core/src/test/java/com/workloadhub/forecast/testing/TestData.java                   hand-built rows (Task 3)
server/forecast-core/src/test/java/com/workloadhub/forecast/testing/SyntheticMatrix.java            planted-signal matrix (Task 7)
```

`ForecastRepository` needs the module's SQLite database with imported rows; `SeededData` (test scope) builds one from `SeedGenerator.generate(null, new SeedConfig(30, 2026-09-06, 11, true, 36))` once per JVM and loads it, so every later task tests on realistic data without files.

---

### Task 1: Typed rows, `ForecastData` and the repository

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/rows/*.java` (ten records)
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/ForecastData.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/ForecastRepository.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/testing/SeededData.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/data/ForecastRepositoryTest.java`

**Interfaces:**
- Produces, in `data.rows`:
  - `record MemberRow(UUID id, String fullName, String email, String role, String jobTitle, List<UUID> teamIds, UUID primaryTeamId, LocalDate joined, LocalDate left)`; `boolean employedOn(LocalDate d)`.
  - `record TeamRow(UUID id, String name, UUID managerId, UUID parentId)`.
  - `record ProjectRow(UUID id, String key, String name, String status, UUID teamId)`.
  - `record TaskRow(UUID id, String key, String title, UUID projectId, UUID assigneeId, UUID reporterId, UUID parentId, String typeName, String statusCategory, String priority, Double estimate, Double remaining, LocalDateTime createdDate, LocalDateTime startedDate, LocalDateTime finishedDate, LocalDate dueDate, boolean reopened, boolean archived)`.
  - `record TransitionRow(UUID taskId, UUID userId, String field, String oldValue, String newValue, LocalDateTime changedAt)`.
  - `record TimeLogRow(UUID taskId, UUID userId, LocalDate day, double hours)`.
  - `record CapacityRow(UUID userId, LocalDate weekStart, double base, double absence, double available)`.
  - `record AbsenceRow(UUID userId, LocalDate day, double hours)`.
  - `record HolidayRow(LocalDate start, LocalDate end, boolean confirmed, boolean active, String title)`.
  - `record UserRef(UUID id, String fullName, String email, String username)` for every user, counted or not.
- `ForecastData(List<MemberRow> members, List<TeamRow> teams, List<ProjectRow> projects, List<TaskRow> tasks, List<TransitionRow> transitions, List<TimeLogRow> timeLogs, List<CapacityRow> capacity, List<AbsenceRow> absences, List<HolidayRow> holidays, List<UserRef> users, Map<String, String> statusCategoryByName)` with lookups: `Map<UUID, MemberRow> memberById()`, `Map<UUID, TaskRow> taskById()`, `Map<UUID, ProjectRow> projectById()`, `Map<UUID, TeamRow> teamById()`, `List<MemberRow> membersOfTeam(UUID teamId)` (sorted by id), `Map<UUID, List<TransitionRow>> transitionsByTask()`, `Map<UUID, List<TimeLogRow>> logsByTask()`, `Set<UUID> projectIdsOfTeamAndParent(UUID teamId)`; lists are sorted by id (tasks), by `changedAt` then id (transitions), by day (logs).
- `new ForecastRepository(JdbcClient jdbc, Dialect dialect).loadAll()` returning `ForecastData`; members are counted users in `team_members`; `primaryTeamId` = the member's team with a non-null `parent_team_id` with the smallest id, else the smallest team id; `joined` = the earliest `team_members.joined_at` date; `left` = `deactivated_at` date or null. Archived tasks are excluded.
- Test helper `SeededData.data()` (static, cached): builds an in-memory SQLite with the WorkloadHub schema, imports `SeedGenerator.generate(null, new SeedConfig(30, LocalDate.of(2026, 9, 6), 11, true, 36))`, and returns `ForecastRepository.loadAll()`; `SeededData.envelope()` returns the generated envelope; `SeededData.asOf()` returns `LocalDate.of(2026, 9, 6)`.

- [ ] **Step 1: Write the failing test**

```java
package com.workloadhub.forecast.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.testing.SeededData;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ForecastRepositoryTest {

    @Test
    void loadsCountedMembersWithTheirTeams() {
        ForecastData data = SeededData.data();
        assertFalse(data.members().isEmpty());
        for (MemberRow m : data.members()) {
            assertTrue(Set.of("MEMBER", "TEAM_LEADER").contains(m.role()), m.role());
            assertFalse(m.teamIds().isEmpty());
            assertTrue(m.teamIds().contains(m.primaryTeamId()));
            assertNotNull(m.joined());
        }
        long rows = SeededData.envelope().rows("users").stream()
                .filter(u -> List.of("MEMBER", "TEAM_LEADER").contains(u.get("role")) && Boolean.TRUE.equals(u.get("active")))
                .count();
        assertTrue(data.members().size() <= rows && data.members().size() >= rows - 5, "counted members " + data.members().size() + " of " + rows);
    }

    @Test
    void primaryTeamPrefersTheManagerTeam() {
        ForecastData data = SeededData.data();
        long withParent = data.members().stream()
                .filter(m -> data.teamById().get(m.primaryTeamId()).parentId() != null).count();
        assertTrue(withParent > data.members().size() / 2, "most members sit in a manager team");
    }

    @Test
    void tasksTransitionsAndLogsAreTypedAndOrdered() {
        ForecastData data = SeededData.data();
        assertEquals(SeededData.envelope().rows("tasks").size(), data.tasks().size());
        TaskRow first = data.tasks().get(0);
        assertNotNull(first.createdDate());
        assertTrue(Set.of("TO_DO", "IN_PROGRESS", "DONE").contains(first.statusCategory()));
        for (int i = 1; i < data.tasks().size(); i++) {
            assertTrue(data.tasks().get(i - 1).id().toString().compareTo(data.tasks().get(i).id().toString()) < 0);
        }
        UUID anyTask = data.transitions().get(0).taskId();
        var mine = data.transitionsByTask().get(anyTask);
        for (int i = 1; i < mine.size(); i++) {
            assertFalse(mine.get(i).changedAt().isBefore(mine.get(i - 1).changedAt()));
        }
        assertEquals(SeededData.envelope().rows("time_logs").size(), data.timeLogs().size());
        assertEquals(9, data.statusCategoryByName().size());
        assertEquals("DONE", data.statusCategoryByName().get("Done"));
    }

    @Test
    void holidaysCapacityAbsencesAndProjectsArePresent() {
        ForecastData data = SeededData.data();
        assertTrue(data.holidays().stream().anyMatch(h -> h.confirmed() && h.active()));
        assertEquals(SeededData.envelope().rows("user_capacity").size(), data.capacity().size());
        assertEquals(SeededData.envelope().rows("absences").size(), data.absences().size());
        assertEquals(SeededData.envelope().rows("projects").size(), data.projects().size());
        MemberRow m = data.members().get(0);
        assertFalse(data.projectIdsOfTeamAndParent(m.primaryTeamId()).isEmpty());
        assertEquals(SeededData.envelope().rows("users").size(), data.users().size());
    }
}
```

`SeededData.java` (test scope):

```java
package com.workloadhub.forecast.testing;

import com.workloadhub.forecast.data.ExportEnvelope;
import com.workloadhub.forecast.data.ExportImporter;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.ForecastRepository;
import com.workloadhub.forecast.seed.SeedConfig;
import com.workloadhub.forecast.seed.SeedGenerator;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** One synthetic dataset per JVM: 36 users, 30 weeks, seed 11, loaded through the real repository. */
public final class SeededData {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 6);
    private static ExportEnvelope envelope;
    private static ForecastData data;
    private static DataSource dataSource;

    private SeededData() {
    }

    public static synchronized ExportEnvelope envelope() {
        if (envelope == null) {
            envelope = SeedGenerator.generate(null, new SeedConfig(30, AS_OF, 11, true, 36));
        }
        return envelope;
    }

    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            dataSource = DatabaseTestSupport.sqliteInMemory();
            WorkloadHubSchema.createSqlite(dataSource);
            new ExportImporter(dataSource).importAll(envelope(), true);
        }
        return dataSource;
    }

    public static synchronized ForecastData data() {
        if (data == null) {
            DataSource ds = dataSource();
            data = new ForecastRepository(JdbcClient.create(ds), Dialect.of(ds)).loadAll();
        }
        return data;
    }

    public static LocalDate asOf() {
        return AS_OF;
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=ForecastRepositoryTest`
Expected: compilation errors, the `data.rows` records, `ForecastData` and `ForecastRepository` do not exist.

- [ ] **Step 3: Write the row records**

One file per record in `data/rows/`, each a plain `public record` in package `com.workloadhub.forecast.data.rows` with the components listed in Interfaces. `MemberRow` adds:

```java
    public boolean employedOn(LocalDate d) {
        return !d.isBefore(joined) && (left == null || d.isBefore(left));
    }
```

- [ ] **Step 4: Write `ForecastData`**

```java
package com.workloadhub.forecast.data;

import com.workloadhub.forecast.data.rows.AbsenceRow;
import com.workloadhub.forecast.data.rows.CapacityRow;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.data.rows.TransitionRow;
import com.workloadhub.forecast.data.rows.UserRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/** Everything a run reads, loaded once, immutable, with the lookups the rules need. */
public record ForecastData(
        List<MemberRow> members,
        List<TeamRow> teams,
        List<ProjectRow> projects,
        List<TaskRow> tasks,
        List<TransitionRow> transitions,
        List<TimeLogRow> timeLogs,
        List<CapacityRow> capacity,
        List<AbsenceRow> absences,
        List<HolidayRow> holidays,
        List<UserRef> users,
        Map<String, String> statusCategoryByName) {

    private static final Comparator<UUID> BY_ID = Comparator.comparing(UUID::toString);

    public ForecastData {
        members = sortedBy(members, MemberRow::id);
        teams = sortedBy(teams, TeamRow::id);
        projects = sortedBy(projects, ProjectRow::id);
        tasks = sortedBy(tasks, TaskRow::id);
        transitions = List.copyOf(transitions.stream()
                .sorted(Comparator.comparing(TransitionRow::changedAt).thenComparing(t -> t.taskId().toString())).toList());
        timeLogs = List.copyOf(timeLogs.stream()
                .sorted(Comparator.comparing(TimeLogRow::day).thenComparing(l -> l.taskId().toString())).toList());
        capacity = List.copyOf(capacity);
        absences = List.copyOf(absences);
        holidays = List.copyOf(holidays);
        users = sortedBy(users, UserRef::id);
        statusCategoryByName = Map.copyOf(statusCategoryByName);
    }

    private static <T> List<T> sortedBy(List<T> items, Function<T, UUID> id) {
        return List.copyOf(items.stream().sorted(Comparator.comparing(id, BY_ID)).toList());
    }

    public Map<UUID, MemberRow> memberById() {
        return index(members, MemberRow::id);
    }

    public Map<UUID, TaskRow> taskById() {
        return index(tasks, TaskRow::id);
    }

    public Map<UUID, ProjectRow> projectById() {
        return index(projects, ProjectRow::id);
    }

    public Map<UUID, TeamRow> teamById() {
        return index(teams, TeamRow::id);
    }

    public Map<UUID, UserRef> userById() {
        return index(users, UserRef::id);
    }

    private static <T> Map<UUID, T> index(List<T> items, Function<T, UUID> id) {
        Map<UUID, T> out = new LinkedHashMap<>();
        for (T item : items) {
            out.put(id.apply(item), item);
        }
        return out;
    }

    public List<MemberRow> membersOfTeam(UUID teamId) {
        return members.stream().filter(m -> m.teamIds().contains(teamId)).toList();
    }

    public Map<UUID, List<TransitionRow>> transitionsByTask() {
        Map<UUID, List<TransitionRow>> out = new TreeMap<>(BY_ID);
        for (TransitionRow t : transitions) {
            out.computeIfAbsent(t.taskId(), k -> new ArrayList<>()).add(t);
        }
        return out;
    }

    public Map<UUID, List<TimeLogRow>> logsByTask() {
        Map<UUID, List<TimeLogRow>> out = new TreeMap<>(BY_ID);
        for (TimeLogRow l : timeLogs) {
            out.computeIfAbsent(l.taskId(), k -> new ArrayList<>()).add(l);
        }
        return out;
    }

    /** Projects owned by the team or by its parent team (a department's projects feed its manager teams). */
    public Set<UUID> projectIdsOfTeamAndParent(UUID teamId) {
        TeamRow team = teamById().get(teamId);
        Set<UUID> owners = new TreeSet<>(BY_ID);
        owners.add(teamId);
        if (team != null && team.parentId() != null) {
            owners.add(team.parentId());
        }
        Set<UUID> out = new TreeSet<>(BY_ID);
        for (ProjectRow p : projects) {
            if (p.teamId() != null && owners.contains(p.teamId())) {
                out.add(p.id());
            }
        }
        return out;
    }
}
```

- [ ] **Step 5: Write `ForecastRepository`**

```java
package com.workloadhub.forecast.data;

import com.workloadhub.forecast.data.rows.AbsenceRow;
import com.workloadhub.forecast.data.rows.CapacityRow;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.data.rows.TransitionRow;
import com.workloadhub.forecast.data.rows.UserRef;
import com.workloadhub.forecast.store.Dialect;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads the WorkloadHub tables into typed rows. Read only; portable SQL only. */
public final class ForecastRepository {

    private static final Set<String> COUNTED_ROLES = Set.of("MEMBER", "TEAM_LEADER");

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public ForecastRepository(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    public ForecastData loadAll() {
        List<Map<String, Object>> statusRows = rows("SELECT id, name, category FROM task_statuses");
        Map<String, String> categoryByName = new TreeMap<>();
        Map<String, String> categoryById = new HashMap<>();
        for (Map<String, Object> r : statusRows) {
            categoryByName.put(str(r, "name"), str(r, "category"));
            categoryById.put(str(r, "id"), str(r, "category"));
        }
        Map<String, String> typeNameById = new HashMap<>();
        for (Map<String, Object> r : rows("SELECT id, name FROM task_types")) {
            typeNameById.put(str(r, "id"), str(r, "name"));
        }
        List<TeamRow> teams = new ArrayList<>();
        Map<UUID, TeamRow> teamById = new HashMap<>();
        for (Map<String, Object> r : rows("SELECT id, name, manager_id, parent_team_id FROM teams")) {
            TeamRow t = new TeamRow(uuid(r, "id"), str(r, "name"), uuid(r, "manager_id"), uuid(r, "parent_team_id"));
            teams.add(t);
            teamById.put(t.id(), t);
        }
        Map<UUID, List<UUID>> teamsOfUser = new HashMap<>();
        Map<UUID, LocalDate> joinedOfUser = new HashMap<>();
        for (Map<String, Object> r : rows("SELECT team_id, user_id, joined_at FROM team_members")) {
            UUID user = uuid(r, "user_id");
            teamsOfUser.computeIfAbsent(user, k -> new ArrayList<>()).add(uuid(r, "team_id"));
            LocalDate joined = dateTime(r, "joined_at").toLocalDate();
            joinedOfUser.merge(user, joined, (a, b) -> a.isBefore(b) ? a : b);
        }
        List<UserRef> users = new ArrayList<>();
        List<MemberRow> members = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT id, full_name, email, username, role, job_title, active, deactivated_at FROM users")) {
            UUID id = uuid(r, "id");
            users.add(new UserRef(id, str(r, "full_name"), str(r, "email"), str(r, "username")));
            boolean active = dialect.asBoolean(r.get("active"));
            List<UUID> teamIds = teamsOfUser.getOrDefault(id, List.of());
            if (!active || !COUNTED_ROLES.contains(str(r, "role")) || teamIds.isEmpty()) {
                continue;
            }
            List<UUID> sorted = teamIds.stream().sorted(Comparator.comparing(UUID::toString)).toList();
            UUID primary = sorted.stream()
                    .filter(t -> teamById.containsKey(t) && teamById.get(t).parentId() != null)
                    .findFirst().orElse(sorted.get(0));
            LocalDateTime left = dateTime(r, "deactivated_at");
            members.add(new MemberRow(id, str(r, "full_name"), str(r, "email"), str(r, "role"), str(r, "job_title"),
                    sorted, primary, joinedOfUser.get(id), left == null ? null : left.toLocalDate()));
        }
        List<ProjectRow> projects = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT id, key, name, status, team_id FROM projects WHERE archived = " + dialect.boolLiteral(false))) {
            projects.add(new ProjectRow(uuid(r, "id"), str(r, "key"), str(r, "name"), str(r, "status"), uuid(r, "team_id")));
        }
        List<TaskRow> tasks = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT id, key, title, project_id, assignee_id, reporter_id, parent_task_id, task_type_id,"
                + " task_status_id, priority, original_estimate_hrs, remaining_estimate_hrs, created_date, started_date, finished_date,"
                + " due_date, reopened_from_done, archived FROM tasks")) {
            if (dialect.asBoolean(r.get("archived"))) {
                continue;
            }
            tasks.add(new TaskRow(uuid(r, "id"), str(r, "key"), str(r, "title"), uuid(r, "project_id"), uuid(r, "assignee_id"),
                    uuid(r, "reporter_id"), uuid(r, "parent_task_id"), typeNameById.get(str(r, "task_type_id")),
                    categoryById.get(str(r, "task_status_id")), str(r, "priority"), dbl(r, "original_estimate_hrs"),
                    dbl(r, "remaining_estimate_hrs"), dateTime(r, "created_date"), dateTime(r, "started_date"),
                    dateTime(r, "finished_date"), date(r, "due_date"), dialect.asBoolean(r.get("reopened_from_done")), false));
        }
        List<TransitionRow> transitions = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT task_id, user_id, field_name, old_value, new_value, changed_at FROM task_history")) {
            transitions.add(new TransitionRow(uuid(r, "task_id"), uuid(r, "user_id"), str(r, "field_name"), str(r, "old_value"),
                    str(r, "new_value"), dateTime(r, "changed_at")));
        }
        List<TimeLogRow> logs = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT task_id, user_id, log_date, hours FROM time_logs")) {
            logs.add(new TimeLogRow(uuid(r, "task_id"), uuid(r, "user_id"), date(r, "log_date"), dbl(r, "hours")));
        }
        List<CapacityRow> capacity = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT user_id, week_start, base_capacity_hrs, absence_hrs, available_hrs FROM user_capacity")) {
            capacity.add(new CapacityRow(uuid(r, "user_id"), date(r, "week_start"), dbl(r, "base_capacity_hrs"),
                    dbl(r, "absence_hrs"), dbl(r, "available_hrs")));
        }
        List<AbsenceRow> absences = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT user_id, date, hours FROM absences")) {
            absences.add(new AbsenceRow(uuid(r, "user_id"), date(r, "date"), dbl(r, "hours")));
        }
        List<HolidayRow> holidays = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT start_date, end_date, status, active, title FROM holidays")) {
            holidays.add(new HolidayRow(date(r, "start_date"), date(r, "end_date"), "CONFIRMED".equals(str(r, "status")),
                    dialect.asBoolean(r.get("active")), str(r, "title")));
        }
        return new ForecastData(members, teams, projects, tasks, transitions, logs, capacity, absences, holidays, users, categoryByName);
    }

    private List<Map<String, Object>> rows(String sql) {
        return jdbc.sql(sql).query().listOfRows();
    }

    static String str(Map<String, Object> r, String col) {
        Object v = r.get(col);
        return v == null ? null : v.toString();
    }

    static UUID uuid(Map<String, Object> r, String col) {
        String s = str(r, col);
        return s == null || s.isBlank() ? null : UUID.fromString(s);
    }

    static Double dbl(Map<String, Object> r, String col) {
        Object v = r.get(col);
        return v == null ? null : ((Number) v).doubleValue();
    }

    static LocalDate date(Map<String, Object> r, String col) {
        Object v = r.get(col);
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        String s = v.toString();
        return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }

    static LocalDateTime dateTime(Map<String, Object> r, String col) {
        Object v = r.get(col);
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime();
        }
        String s = v.toString().replace(' ', 'T');
        return s.length() == 10 ? LocalDate.parse(s).atStartOfDay() : LocalDateTime.parse(s);
    }
}
```

`Dialect` gains one method (add to `store/Dialect.java`):

```java
    /** A boolean literal in SQL text, for the rare WHERE that filters a boolean column. */
    public String boolLiteral(boolean value) {
        return this == SQLITE ? (value ? "1" : "0") : (value ? "TRUE" : "FALSE");
    }
```

`dbl` returns `Double` for `Number` values: SQLite returns `Integer`/`Double`, PostgreSQL `Double`/`Long`; the cast covers both. A null `hours` never occurs (`NOT NULL`), so `dbl(r, "hours")` unboxes safely.

- [ ] **Step 6: Run the test**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=ForecastRepositoryTest`
Expected: 4 passed. The first call to `SeededData` generates and imports the dataset (a few seconds); later tests reuse it.

- [ ] **Step 7: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/data server/forecast-core/src/main/java/com/workloadhub/forecast/store/Dialect.java server/forecast-core/src/test/java/com/workloadhub/forecast/data/ForecastRepositoryTest.java server/forecast-core/src/test/java/com/workloadhub/forecast/testing
git commit -m "feat(server): typed rows and the repository that reads the WorkloadHub tables

Every rule after this point works on immutable records loaded once, so
the pipeline is testable without a database; the test fixture builds a
synthetic dataset through the seed generator and the real repository."
```

---

### Task 2: Calendar, hour placement and the capacity rule

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/calendar/Weeks.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/calendar/WorkingCalendar.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/calendar/HourPlacement.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/capacity/CapacityRule.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/calendar/WeeksTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/calendar/WorkingCalendarTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/calendar/HourPlacementTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/capacity/CapacityRuleTest.java`

**Interfaces:**
- `Weeks`: `static LocalDate mondayOf(LocalDate)`, `static LocalDate lastCompleteWeek(LocalDate asOf)` (= `mondayOf(asOf) − 1 week`), `static LocalDate[] forecastWeeks(LocalDate asOf)` (first = `mondayOf(asOf)` when as-of is a Monday, else the next Monday; second = first + 1 week), `static List<LocalDate> between(LocalDate first, LocalDate last)` (Mondays from `mondayOf(first)` to `mondayOf(last)` inclusive), `static long weeksBetween(LocalDate a, LocalDate b)`, `static int isoWeek(LocalDate)`.
- `WorkingCalendar.fromHolidays(List<HolidayRow>)` keeps confirmed and active rows; `boolean isWorkingDay(LocalDate)`, `List<LocalDate> workingDays(LocalDate start, LocalDate end, Set<LocalDate> off)`, `int workingDaysInWeek(LocalDate monday)`, `Set<LocalDate> holidays()`.
- `HourPlacement.placeHours(double hours, LocalDate start, LocalDate end, WorkingCalendar cal, Set<LocalDate> off)` returning `SortedMap<LocalDate, Double>` by Monday: evenly over the working days not in `off`; when `end < start`, `end = start`; when no working day exists, everything lands on `mondayOf(start)`.
- `CapacityRule(double defaultWeeklyHours)`: `double capacity(MemberRow m, LocalDate monday, ForecastData data, WorkingCalendar cal)` per the constraint (capacity row, else latest base, else default, × working days / 5, minus absence hours); `double absenceHours(UUID member, LocalDate monday, ForecastData data, WorkingCalendar cal)` (capacity row's `absence_hrs` when it exists, else the sum of `absences.hours` on the week's working days); `static Set<LocalDate> offDays(UUID member, ForecastData data)` (days with absence hours ≥ 8); results rounded to 2 decimals.

- [ ] **Step 1: Write the failing tests**

`WeeksTest.java`:

```java
package com.workloadhub.forecast.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.time.api.constraints.DateRange;
import org.junit.jupiter.api.Test;

class WeeksTest {

    @Property
    boolean mondayOfIsAMondayNotAfterTheDay(@ForAll @DateRange(min = "2020-01-01", max = "2030-12-31") LocalDate d) {
        LocalDate m = Weeks.mondayOf(d);
        return m.getDayOfWeek().getValue() == 1 && !m.isAfter(d) && d.toEpochDay() - m.toEpochDay() < 7;
    }

    @Test
    void forecastWeeksStartThisWeekOnAMondayElseNextWeek() {
        LocalDate[] onMonday = Weeks.forecastWeeks(LocalDate.of(2026, 9, 7));
        assertEquals(LocalDate.of(2026, 9, 7), onMonday[0]);
        assertEquals(LocalDate.of(2026, 9, 14), onMonday[1]);
        LocalDate[] onSunday = Weeks.forecastWeeks(LocalDate.of(2026, 9, 6));
        assertEquals(LocalDate.of(2026, 9, 7), onSunday[0]);
        assertEquals(LocalDate.of(2026, 8, 24), Weeks.lastCompleteWeek(LocalDate.of(2026, 9, 6)));
    }

    @Test
    void betweenListsEveryMondayInclusive() {
        List<LocalDate> weeks = Weeks.between(LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 24));
        assertEquals(List.of(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 24)), weeks);
        assertEquals(3, Weeks.weeksBetween(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 24)));
        assertEquals(32, Weeks.isoWeek(LocalDate.of(2026, 8, 3)));
    }
}
```

`WorkingCalendarTest.java`:

```java
package com.workloadhub.forecast.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.rows.HolidayRow;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkingCalendarTest {

    static WorkingCalendar cal() {
        return WorkingCalendar.fromHolidays(List.of(
                new HolidayRow(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1), true, true, "Labour Day"),
                new HolidayRow(LocalDate.of(2026, 3, 20), LocalDate.of(2026, 3, 21), false, true, "Pending"),
                new HolidayRow(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1), true, false, "Inactive")));
    }

    @Test
    void onlyConfirmedActiveHolidaysCount() {
        WorkingCalendar cal = cal();
        assertEquals(Set.of(LocalDate.of(2026, 5, 1)), cal.holidays());
        assertFalse(cal.isWorkingDay(LocalDate.of(2026, 5, 1)));
        assertTrue(cal.isWorkingDay(LocalDate.of(2026, 3, 20)));
        assertTrue(cal.isWorkingDay(LocalDate.of(2026, 6, 1)));
        assertFalse(cal.isWorkingDay(LocalDate.of(2026, 5, 2)));
        assertEquals(4, cal.workingDaysInWeek(LocalDate.of(2026, 4, 27)));
    }

    @Test
    void workingDaysSkipOffDays() {
        List<LocalDate> days = cal().workingDays(LocalDate.of(2026, 4, 27), LocalDate.of(2026, 5, 3), Set.of(LocalDate.of(2026, 4, 28)));
        assertEquals(List.of(LocalDate.of(2026, 4, 27), LocalDate.of(2026, 4, 29), LocalDate.of(2026, 4, 30)), days);
    }
}
```

`HourPlacementTest.java`:

```java
package com.workloadhub.forecast.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class HourPlacementTest {

    static final WorkingCalendar CAL = WorkingCalendar.fromHolidays(List.of());

    @Property
    boolean placedHoursSumToTheInputAndLandOnMondays(@ForAll @DoubleRange(min = 0, max = 200) double hours,
            @ForAll @IntRange(min = 0, max = 40) int span) {
        LocalDate start = LocalDate.of(2026, 3, 4);
        SortedMap<LocalDate, Double> out = HourPlacement.placeHours(hours, start, start.plusDays(span), CAL, Set.of());
        double sum = out.values().stream().mapToDouble(Double::doubleValue).sum();
        boolean mondays = out.keySet().stream().allMatch(d -> d.getDayOfWeek().getValue() == 1);
        return Math.abs(sum - hours) < 1e-6 && mondays;
    }

    @Test
    void spreadsEvenlyOverWorkingDaysAcrossWeeks() {
        SortedMap<LocalDate, Double> out = HourPlacement.placeHours(10, LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 10), CAL, Set.of());
        // Thu 5, Fri 6 in week of 2 March; Mon 9, Tue 10 in week of 9 March: 4 days, 2.5 h each
        assertEquals(5.0, out.get(LocalDate.of(2026, 3, 2)), 1e-9);
        assertEquals(5.0, out.get(LocalDate.of(2026, 3, 9)), 1e-9);
    }

    @Test
    void endBeforeStartAndNoWorkingDayFallBackToTheStartWeek() {
        assertEquals(8.0, HourPlacement.placeHours(8, LocalDate.of(2026, 3, 6), LocalDate.of(2026, 3, 2), CAL, Set.of()).get(LocalDate.of(2026, 3, 2)), 1e-9);
        SortedMap<LocalDate, Double> weekend = HourPlacement.placeHours(3, LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 8), CAL, Set.of());
        assertEquals(3.0, weekend.get(LocalDate.of(2026, 3, 2)), 1e-9);
    }
}
```

`CapacityRuleTest.java`:

```java
package com.workloadhub.forecast.capacity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.AbsenceRow;
import com.workloadhub.forecast.data.rows.CapacityRow;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.data.rows.UserRef;
import com.workloadhub.forecast.testing.SeededData;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapacityRuleTest {

    static final UUID M = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final UUID T = UUID.fromString("40000000-0000-0000-0000-000000000001");

    static ForecastData data(List<CapacityRow> capacity, List<AbsenceRow> absences) {
        MemberRow m = new MemberRow(M, "Eng Two", "e@example.test", "MEMBER", "Calibration Engineer", List.of(T), T, LocalDate.of(2026, 1, 5), null);
        return new ForecastData(List.of(m), List.of(new TeamRow(T, "CT2 · X", null, null)), List.of(), List.of(), List.of(), List.of(),
                capacity, absences, List.of(new HolidayRow(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1), true, true, "Labour Day")),
                List.of(new UserRef(M, "Eng Two", "e@example.test", "eng")), Map.of());
    }

    @Test
    void capacityRowWinsWhenPresent() {
        ForecastData d = data(List.of(new CapacityRow(M, LocalDate.of(2026, 4, 27), 40, 8, 24)), List.of());
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        assertEquals(24.0, new CapacityRule(40).capacity(d.members().get(0), LocalDate.of(2026, 4, 27), d, cal), 1e-9);
        assertEquals(8.0, new CapacityRule(40).absenceHours(M, LocalDate.of(2026, 4, 27), d, cal), 1e-9);
    }

    @Test
    void latestBaseThenDefaultTimesWorkingDaysMinusAbsences() {
        ForecastData d = data(List.of(new CapacityRow(M, LocalDate.of(2026, 3, 2), 36, 0, 36)),
                List.of(new AbsenceRow(M, LocalDate.of(2026, 4, 28), 8), new AbsenceRow(M, LocalDate.of(2026, 5, 2), 8)));
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        // week of 27 April: 4 working days (Labour Day), one absence day on Tue 28; Sat 2 May is not a working day
        assertEquals(36 * 4 / 5.0 - 8, new CapacityRule(40).capacity(d.members().get(0), LocalDate.of(2026, 4, 27), d, cal), 1e-9);
        ForecastData none = data(List.of(), List.of());
        assertEquals(40.0, new CapacityRule(40).capacity(none.members().get(0), LocalDate.of(2026, 3, 16), none, cal), 1e-9);
        assertEquals(java.util.Set.of(LocalDate.of(2026, 4, 28), LocalDate.of(2026, 5, 2)), CapacityRule.offDays(M, d));
    }

    @Test
    void seededCapacityMatchesTheCapacityRows() {
        ForecastData d = SeededData.data();
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        CapacityRule rule = new CapacityRule(40);
        int checked = 0;
        for (CapacityRow c : d.capacity()) {
            MemberRow m = d.memberById().get(c.userId());
            if (m == null) {
                continue;
            }
            assertEquals(c.available(), rule.capacity(m, c.weekStart(), d, cal), 1e-6);
            checked++;
        }
        assertTrue(checked > 100);
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='WeeksTest,WorkingCalendarTest,HourPlacementTest,CapacityRuleTest'`
Expected: compilation errors.

- [ ] **Step 3: Write `Weeks`, `WorkingCalendar`, `HourPlacement`**

```java
package com.workloadhub.forecast.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;

/** Monday-based week arithmetic. */
public final class Weeks {

    private Weeks() {
    }

    public static LocalDate mondayOf(LocalDate d) {
        return d.minusDays(d.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
    }

    public static LocalDate lastCompleteWeek(LocalDate asOf) {
        return mondayOf(asOf).minusWeeks(1);
    }

    /** The two Mondays to forecast: this week when as-of is a Monday, otherwise next week; plus one. */
    public static LocalDate[] forecastWeeks(LocalDate asOf) {
        LocalDate first = asOf.getDayOfWeek() == DayOfWeek.MONDAY ? asOf : mondayOf(asOf).plusWeeks(1);
        return new LocalDate[] {first, first.plusWeeks(1)};
    }

    public static List<LocalDate> between(LocalDate first, LocalDate last) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate w = mondayOf(first); !w.isAfter(mondayOf(last)); w = w.plusWeeks(1)) {
            out.add(w);
        }
        return out;
    }

    public static long weeksBetween(LocalDate a, LocalDate b) {
        return ChronoUnit.WEEKS.between(mondayOf(a), mondayOf(b));
    }

    public static int isoWeek(LocalDate d) {
        return d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }
}
```

```java
package com.workloadhub.forecast.calendar;

import com.workloadhub.forecast.data.rows.HolidayRow;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Working days: Monday to Friday minus confirmed, active holidays. */
public final class WorkingCalendar {

    private final Set<LocalDate> holidays;

    private WorkingCalendar(Set<LocalDate> holidays) {
        this.holidays = holidays;
    }

    public static WorkingCalendar fromHolidays(List<HolidayRow> rows) {
        Set<LocalDate> days = new TreeSet<>();
        for (HolidayRow h : rows) {
            if (!h.confirmed() || !h.active()) {
                continue;
            }
            for (LocalDate d = h.start(); !d.isAfter(h.end()); d = d.plusDays(1)) {
                days.add(d);
            }
        }
        return new WorkingCalendar(days);
    }

    public Set<LocalDate> holidays() {
        return holidays;
    }

    public boolean isWorkingDay(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(d);
    }

    public List<LocalDate> workingDays(LocalDate start, LocalDate end, Set<LocalDate> off) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (isWorkingDay(d) && !off.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    public int workingDaysInWeek(LocalDate monday) {
        return workingDays(monday, monday.plusDays(6), Set.of()).size();
    }
}
```

```java
package com.workloadhub.forecast.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/** Spreads hours evenly over working days and sums them per Monday week. */
public final class HourPlacement {

    private HourPlacement() {
    }

    public static SortedMap<LocalDate, Double> placeHours(double hours, LocalDate start, LocalDate end, WorkingCalendar cal, Set<LocalDate> off) {
        LocalDate last = end.isBefore(start) ? start : end;
        List<LocalDate> days = cal.workingDays(start, last, off);
        SortedMap<LocalDate, Double> out = new TreeMap<>();
        if (days.isEmpty()) {
            out.put(Weeks.mondayOf(start), hours);
            return out;
        }
        double perDay = hours / days.size();
        for (LocalDate d : days) {
            out.merge(Weeks.mondayOf(d), perDay, Double::sum);
        }
        return out;
    }
}
```

- [ ] **Step 4: Write `CapacityRule`**

```java
package com.workloadhub.forecast.capacity;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.AbsenceRow;
import com.workloadhub.forecast.data.rows.CapacityRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Hours a member can work in a week (design section 5): the application's own row first, then the rule. */
public final class CapacityRule {

    public static final int WORKING_DAYS_PER_WEEK = 5;
    public static final double FULL_DAY_HOURS = 8.0;

    private final double defaultWeeklyHours;

    public CapacityRule(double defaultWeeklyHours) {
        this.defaultWeeklyHours = defaultWeeklyHours;
    }

    static Optional<CapacityRow> rowFor(UUID member, LocalDate monday, ForecastData data) {
        return data.capacity().stream().filter(c -> c.userId().equals(member) && c.weekStart().equals(monday)).findFirst();
    }

    static Optional<CapacityRow> latestRowBefore(UUID member, LocalDate monday, ForecastData data) {
        return data.capacity().stream()
                .filter(c -> c.userId().equals(member) && !c.weekStart().isAfter(monday))
                .max((a, b) -> a.weekStart().compareTo(b.weekStart()));
    }

    public double absenceHours(UUID member, LocalDate monday, ForecastData data, WorkingCalendar cal) {
        Optional<CapacityRow> row = rowFor(member, monday, data);
        if (row.isPresent()) {
            return round2(row.get().absence());
        }
        LocalDate end = monday.plusDays(6);
        double sum = 0;
        for (AbsenceRow a : data.absences()) {
            if (a.userId().equals(member) && !a.day().isBefore(monday) && !a.day().isAfter(end) && cal.isWorkingDay(a.day())) {
                sum += a.hours();
            }
        }
        return round2(sum);
    }

    public double capacity(MemberRow member, LocalDate monday, ForecastData data, WorkingCalendar cal) {
        Optional<CapacityRow> row = rowFor(member.id(), monday, data);
        if (row.isPresent()) {
            return round2(row.get().available());
        }
        double base = latestRowBefore(member.id(), monday, data).map(CapacityRow::base).orElse(defaultWeeklyHours);
        double hours = base * cal.workingDaysInWeek(monday) / WORKING_DAYS_PER_WEEK - absenceHours(member.id(), monday, data, cal);
        return round2(Math.max(0.0, hours));
    }

    /** Days a member is away for the whole day: absences of at least a full day's hours. */
    public static Set<LocalDate> offDays(UUID member, ForecastData data) {
        Set<LocalDate> out = new TreeSet<>();
        for (AbsenceRow a : data.absences()) {
            if (a.userId().equals(member) && a.hours() >= FULL_DAY_HOURS) {
                out.add(a.day());
            }
        }
        return out;
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
```

The two linear scans over `data.capacity()` and `data.absences()` are fine at the seed's scale (about 14 000 capacity rows); Task 5 builds per-member indexes once where it loops over weeks.

- [ ] **Step 5: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='WeeksTest,WorkingCalendarTest,HourPlacementTest,CapacityRuleTest'`
Expected: all pass. `seededCapacityMatchesTheCapacityRows` holds because the seed writes `available_hrs` with the same formula and the rule returns the row itself when present.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/calendar server/forecast-core/src/main/java/com/workloadhub/forecast/capacity server/forecast-core/src/test/java/com/workloadhub/forecast/calendar server/forecast-core/src/test/java/com/workloadhub/forecast/capacity
git commit -m "feat(server): calendar arithmetic, hour placement and the capacity rule

Weeks start on Monday, working days exclude confirmed holidays, hours
spread evenly over a member's present working days, and capacity takes
the application's own weekly row before falling back to the rule."
```

---

### Task 3: Task lifecycle rules

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/lifecycle/Family.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/lifecycle/Mode.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/lifecycle/TaskFacts.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/lifecycle/Lifecycle.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/testing/TestData.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/lifecycle/LifecycleTest.java`

**Interfaces:**
- `enum Family { DELIVERY, DEFECT, CONTAINER, SUPPORT }` with `static Family ofType(String typeName)` (the table of the Global Constraints; unknown names are `DELIVERY`; `Sub-task` returns null so the caller takes the parent's family) and `String label()` (`"delivery"`, …).
- `enum Mode { SELF_PICKED, PROJECT, MANUAL }` with `label()` (`"self_picked"`, `"project"`, `"manual"`).
- `record TaskFacts(TaskRow task, LocalDateTime assigned, LocalDateTime started, LocalDateTime finished, Family family, Mode mode, double actualHours, boolean unlogged, boolean assignmentFallback)` with `UUID id()`, `UUID assignee()`, `double estimate()` (0 when null), `Double remaining()`, `boolean done()` (`statusCategory = DONE`), `boolean inProgress()`, `boolean isAssigned()` (the record accessor `assigned()` returns the date), `LocalDate assignedDay()`, `LocalDate assignedWeek()` (Monday), `long lagDays()` (whole days from `createdDate` to `assigned`, 0 when unassigned), `boolean fresh()` (`lagDays() < Lifecycle.BACKLOG_LAG_DAYS`), `Integer cycleDays()` (`finished − assigned` in days + 1, null when either is missing), `Integer latenessDays()` (`finished.date − dueDate`, null without both), `boolean openAtEndOf(LocalDate day)` (assigned on or before `day` and not finished by it), `boolean inProgressAtEndOf(LocalDate day)` (open and started on or before `day`).
- `record Lifecycle(Map<UUID, TaskFacts> facts, List<String> unresolvedAssignments, List<String> unloggedTasks)` with `static Lifecycle derive(ForecastData data)`, `TaskFacts of(UUID taskId)`, `List<TaskFacts> assignedTo(UUID member)` (sorted by assigned then id), `List<TaskFacts> all()` (sorted by id), `static final int BACKLOG_LAG_DAYS = 2`. Task keys go into the two lists, never UUIDs.
- Test helper `TestData` (test scope) with static factories that fill the boring columns: `MemberRow member(String suffix, UUID team)`, `TaskRow task(String suffix, UUID assignee, LocalDateTime created, double estimate)` and `TaskRow task(...).withX(...)`-style copies via a small `Tasks` builder, `TransitionRow assignee(UUID task, String newValue, LocalDateTime at)`, `TransitionRow status(UUID task, String newStatus, LocalDateTime at)`, `TimeLogRow log(UUID task, UUID user, LocalDate day, double hours)`, `UserRef user(MemberRow m)`, and `ForecastData data(List<MemberRow> members, List<TaskRow> tasks, List<TransitionRow> transitions, List<TimeLogRow> logs)` which fills teams (one team `T1` with parent `T0`), an empty project list, no capacity, no absences, no holidays, the users of the members, and the nine status names of `ReferenceData` mapped to their categories.

- [ ] **Step 1: Write the failing tests**

```java
package com.workloadhub.forecast.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LifecycleTest {

    static final UUID TEAM = TestData.TEAM;
    static final MemberRow ANA = TestData.member("ana", TEAM);
    static final MemberRow BEN = TestData.member("ben", TEAM);
    static final LocalDateTime CREATED = LocalDateTime.of(2026, 8, 3, 9, 0);

    @Test
    void assignedDateIsTheLatestTransitionResolvingToTheAssigneeByName() {
        TaskRow t = TestData.task("1", ANA.id(), CREATED, 8);
        ForecastData data = TestData.data(List.of(ANA, BEN), List.of(t), List.of(
                TestData.assignee(t.id(), BEN.fullName(), CREATED.plusDays(1)),
                TestData.assignee(t.id(), ANA.fullName(), CREATED.plusDays(5))), List.of());
        TaskFacts f = Lifecycle.derive(data).of(t.id());
        assertEquals(CREATED.plusDays(5), f.assigned());
        assertEquals(5, f.lagDays());
        assertFalse(f.fresh());
        assertFalse(f.assignmentFallback());
    }

    @Test
    void assignedDateResolvesUuidAndEmailToo() {
        TaskRow t = TestData.task("1", ANA.id(), CREATED, 8);
        ForecastData byUuid = TestData.data(List.of(ANA), List.of(t),
                List.of(TestData.assignee(t.id(), ANA.id().toString(), CREATED.plusHours(3))), List.of());
        assertEquals(CREATED.plusHours(3), Lifecycle.derive(byUuid).of(t.id()).assigned());
        ForecastData byEmail = TestData.data(List.of(ANA), List.of(t),
                List.of(TestData.assignee(t.id(), ANA.email().toUpperCase(), CREATED.plusHours(4))), List.of());
        TaskFacts f = Lifecycle.derive(byEmail).of(t.id());
        assertEquals(CREATED.plusHours(4), f.assigned());
        assertEquals(0, f.lagDays());
        assertTrue(f.fresh());
    }

    @Test
    void noTransitionMeansAssignedAtCreationAndUnresolvableFallsBackAndIsCounted() {
        TaskRow plain = TestData.task("1", ANA.id(), CREATED, 8);
        TaskRow odd = TestData.task("2", ANA.id(), CREATED, 8);
        MemberRow twin = TestData.member("ana2", TEAM).withFullName(ANA.fullName());
        ForecastData data = TestData.data(List.of(ANA, twin), List.of(plain, odd),
                List.of(TestData.assignee(odd.id(), ANA.fullName(), CREATED.plusDays(2))), List.of());
        Lifecycle lc = Lifecycle.derive(data);
        assertEquals(CREATED, lc.of(plain.id()).assigned());
        assertFalse(lc.of(plain.id()).assignmentFallback());
        assertEquals(CREATED, lc.of(odd.id()).assigned(), "an ambiguous name falls back to created_date");
        assertTrue(lc.of(odd.id()).assignmentFallback());
        assertEquals(List.of(odd.key()), lc.unresolvedAssignments());
    }

    @Test
    void unassignedTaskHasNoAssignmentAndNoneValueMeansUnassigned() {
        TaskRow t = TestData.task("1", null, CREATED, 8);
        ForecastData data = TestData.data(List.of(ANA), List.of(t),
                List.of(TestData.assignee(t.id(), "None", CREATED.plusDays(1))), List.of());
        TaskFacts f = Lifecycle.derive(data).of(t.id());
        assertNull(f.assigned());
        assertFalse(f.isAssigned());
        assertEquals(0, f.lagDays());
        assertTrue(Lifecycle.derive(data).unresolvedAssignments().isEmpty());
    }

    @Test
    void startedAndFinishedComeFromColumnsElseTransitions() {
        TaskRow fromColumns = TestData.task("1", ANA.id(), CREATED, 8)
                .withStatus("DONE").withStarted(CREATED.plusDays(1)).withFinished(CREATED.plusDays(3));
        TaskRow fromHistory = TestData.task("2", ANA.id(), CREATED, 8).withStatus("DONE");
        ForecastData data = TestData.data(List.of(ANA), List.of(fromColumns, fromHistory), List.of(
                TestData.status(fromHistory.id(), "In Progress", CREATED.plusDays(2)),
                TestData.status(fromHistory.id(), "In Review", CREATED.plusDays(4)),
                TestData.status(fromHistory.id(), "Done", CREATED.plusDays(6)),
                TestData.status(fromHistory.id(), "Closed", CREATED.plusDays(7))), List.of());
        Lifecycle lc = Lifecycle.derive(data);
        assertEquals(CREATED.plusDays(1), lc.of(fromColumns.id()).started());
        assertEquals(CREATED.plusDays(3), lc.of(fromColumns.id()).finished());
        assertEquals(CREATED.plusDays(2), lc.of(fromHistory.id()).started(), "first entry into IN_PROGRESS");
        assertEquals(CREATED.plusDays(6), lc.of(fromHistory.id()).finished(), "first entry into DONE");
        assertEquals(7, lc.of(fromHistory.id()).cycleDays());
    }

    @Test
    void anOpenTaskIsNeverFinishedEvenWithAStaleColumn() {
        TaskRow reopened = TestData.task("1", ANA.id(), CREATED, 8)
                .withStatus("IN_PROGRESS").withFinished(CREATED.plusDays(3)).withReopened(true);
        Lifecycle lc = Lifecycle.derive(TestData.data(List.of(ANA), List.of(reopened), List.of(), List.of()));
        assertNull(lc.of(reopened.id()).finished());
        assertTrue(lc.of(reopened.id()).openAtEndOf(CREATED.toLocalDate().plusDays(10)));
        assertFalse(lc.of(reopened.id()).openAtEndOf(CREATED.toLocalDate().minusDays(1)));
    }

    @Test
    void actualHoursSumTheAssigneeLogsElseEstimateMinusRemainingWhenDone() {
        TaskRow logged = TestData.task("1", ANA.id(), CREATED, 8).withStatus("DONE").withFinished(CREATED.plusDays(3));
        TaskRow silent = TestData.task("2", ANA.id(), CREATED, 10).withStatus("DONE").withFinished(CREATED.plusDays(3)).withRemaining(2.0);
        TaskRow open = TestData.task("3", ANA.id(), CREATED, 10);
        ForecastData data = TestData.data(List.of(ANA, BEN), List.of(logged, silent, open), List.of(), List.of(
                TestData.log(logged.id(), ANA.id(), CREATED.toLocalDate(), 3),
                TestData.log(logged.id(), ANA.id(), CREATED.toLocalDate().plusDays(1), 4.5),
                TestData.log(logged.id(), BEN.id(), CREATED.toLocalDate().plusDays(1), 2)));
        Lifecycle lc = Lifecycle.derive(data);
        assertEquals(7.5, lc.of(logged.id()).actualHours(), 1e-9, "only the assignee's logs");
        assertFalse(lc.of(logged.id()).unlogged());
        assertEquals(8.0, lc.of(silent.id()).actualHours(), 1e-9);
        assertTrue(lc.of(silent.id()).unlogged());
        assertEquals(0.0, lc.of(open.id()).actualHours(), 1e-9);
        assertFalse(lc.of(open.id()).unlogged(), "an open task without logs is not a data-quality issue");
        assertEquals(List.of(silent.key()), lc.unloggedTasks());
    }

    @Test
    void familyAndModeFollowTheTables() {
        TaskRow epic = TestData.task("1", ANA.id(), CREATED, 40).withType("Epic");
        TaskRow sub = TestData.task("2", ANA.id(), CREATED, 4).withType("Sub-task").withParent(epic.id());
        TaskRow bug = TestData.task("3", ANA.id(), CREATED, 4).withType("Bug").withReporter(ANA.id());
        TaskRow spike = TestData.task("4", ANA.id(), CREATED, 4).withType("Spike").withReporter(BEN.id());
        TaskRow orphanSub = TestData.task("5", ANA.id(), CREATED, 4).withType("Sub-task");
        Lifecycle lc = Lifecycle.derive(TestData.data(List.of(ANA, BEN), List.of(epic, sub, bug, spike, orphanSub), List.of(), List.of()));
        assertEquals(Family.CONTAINER, lc.of(epic.id()).family());
        assertEquals(Family.CONTAINER, lc.of(sub.id()).family(), "a sub-task takes its parent's family");
        assertEquals(Mode.PROJECT, lc.of(sub.id()).mode());
        assertEquals(Family.DEFECT, lc.of(bug.id()).family());
        assertEquals(Mode.SELF_PICKED, lc.of(bug.id()).mode());
        assertEquals(Family.SUPPORT, lc.of(spike.id()).family());
        assertEquals(Mode.MANUAL, lc.of(spike.id()).mode());
        assertEquals(Family.DELIVERY, lc.of(orphanSub.id()).family());
    }

    @Test
    void seededDataResolvesEveryAssignmentAndSplitsFreshFromBacklog() {
        Lifecycle lc = Lifecycle.derive(SeededData.data());
        assertTrue(lc.unresolvedAssignments().isEmpty(), "seed writes assignee names the rule resolves: " + lc.unresolvedAssignments());
        long assigned = lc.all().stream().filter(TaskFacts::isAssigned).count();
        long fresh = lc.all().stream().filter(f -> f.isAssigned() && f.fresh()).count();
        assertTrue(assigned > 0);
        assertTrue(fresh > assigned * 0.3 && fresh < assigned, "fresh " + fresh + " of " + assigned);
        long doneWithHours = lc.all().stream().filter(f -> f.done() && f.actualHours() > 0).count();
        long done = lc.all().stream().filter(TaskFacts::done).count();
        assertTrue(doneWithHours > done * 0.9, "done tasks carry hours: " + doneWithHours + " of " + done);
        LocalDate asOf = SeededData.asOf();
        for (TaskFacts f : lc.all()) {
            if (f.isAssigned()) {
                assertFalse(f.assigned().toLocalDate().isAfter(asOf));
                assertFalse(f.assigned().isBefore(f.task().createdDate()), "assigned before created: " + f.task().key());
            }
        }
    }
}
```

`TestData` (test scope), the builders the tests above use; `withX` copies live on a small nested `Tasks` helper so `TaskRow` stays a plain record:

```java
package com.workloadhub.forecast.testing;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.data.rows.TransitionRow;
import com.workloadhub.forecast.data.rows.UserRef;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Hand-built rows with sensible defaults, for rule tests that do not need the seed. */
public final class TestData {

    public static final UUID TEAM = UUID.fromString("40000000-0000-0000-0000-000000000001");
    public static final UUID PARENT_TEAM = UUID.fromString("40000000-0000-0000-0000-000000000000");
    public static final LocalDate JOINED = LocalDate.of(2026, 1, 5);
    public static final Map<String, String> STATUS_CATEGORIES = Map.of(
            "Open", "TO_DO", "To Do", "TO_DO", "On Hold", "TO_DO",
            "In Progress", "IN_PROGRESS", "In Review", "IN_PROGRESS", "Testing", "IN_PROGRESS", "Blocked", "IN_PROGRESS",
            "Done", "DONE", "Closed", "DONE");

    private TestData() {
    }

    public static UUID id(String suffix) {
        return UUID.nameUUIDFromBytes(suffix.getBytes(StandardCharsets.UTF_8));
    }

    public static MemberRow member(String suffix, UUID team) {
        return new MemberRow(id("member-" + suffix), "Member " + suffix, suffix + "@example.test", "MEMBER", "Engineer",
                List.of(team), team, JOINED, null);
    }

    public static UserRef user(MemberRow m) {
        return new UserRef(m.id(), m.fullName(), m.email(), m.email().substring(0, m.email().indexOf('@')));
    }

    public static TaskRow task(String suffix, UUID assignee, LocalDateTime created, double estimate) {
        return new TaskRow(id("task-" + suffix), "T-" + suffix, "Task " + suffix, null, assignee, id("reporter"), null,
                "Task", "TO_DO", "MEDIUM", estimate, estimate, created, null, null, null, false, false);
    }

    public static TransitionRow assignee(UUID task, String newValue, LocalDateTime at) {
        return new TransitionRow(task, id("reporter"), "assignee", null, newValue, at);
    }

    public static TransitionRow status(UUID task, String newStatus, LocalDateTime at) {
        return new TransitionRow(task, id("reporter"), "status", null, newStatus, at);
    }

    public static TimeLogRow log(UUID task, UUID user, LocalDate day, double hours) {
        return new TimeLogRow(task, user, day, hours);
    }

    public static ForecastData data(List<MemberRow> members, List<TaskRow> tasks, List<TransitionRow> transitions, List<TimeLogRow> logs) {
        List<TeamRow> teams = List.of(new TeamRow(PARENT_TEAM, "Dept", null, null), new TeamRow(TEAM, "Team", null, PARENT_TEAM));
        List<UserRef> users = members.stream().map(TestData::user).toList();
        return new ForecastData(members, teams, List.of(), tasks, transitions, logs, List.of(), List.of(), List.of(), users, STATUS_CATEGORIES);
    }
}
```

Add the copy helpers as default-free static methods in the same file, one per column the tests touch, each returning a new `TaskRow` with that component replaced (`withStatus`, `withStarted`, `withFinished`, `withRemaining`, `withType`, `withParent`, `withReporter`, `withReopened`, `withDue`, `withProject`), and `MemberRow withFullName(MemberRow, String)`. To keep the test bodies readable as written above, implement them as extension-style statics and import them statically, or, simpler, give the test-scope class `TestData` a tiny wrapper: the implementer may instead add the `withX` methods directly to the records in `data.rows` (records may declare methods); that is the cleaner choice and is allowed, since they carry no logic.

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=LifecycleTest`
Expected: compilation errors.

- [ ] **Step 3: Write the enums and `TaskFacts`**

```java
package com.workloadhub.forecast.lifecycle;

import java.util.Locale;
import java.util.Map;

/** The four type families of the schema mapping, section 3. */
public enum Family {
    DELIVERY, DEFECT, CONTAINER, SUPPORT;

    private static final Map<String, Family> BY_TYPE = Map.ofEntries(
            Map.entry("story", DELIVERY), Map.entry("new feature", DELIVERY), Map.entry("task", DELIVERY),
            Map.entry("improvement", DELIVERY), Map.entry("change request", DELIVERY),
            Map.entry("bug", DEFECT), Map.entry("incident", DEFECT),
            Map.entry("epic", CONTAINER),
            Map.entry("spike", SUPPORT), Map.entry("test", SUPPORT), Map.entry("risk", SUPPORT));

    /** Null for Sub-task (the caller takes the parent's family); DELIVERY for unknown names. */
    public static Family ofType(String typeName) {
        if (typeName == null) {
            return DELIVERY;
        }
        String key = typeName.trim().toLowerCase(Locale.ROOT);
        if (key.equals("sub-task") || key.equals("subtask")) {
            return null;
        }
        return BY_TYPE.getOrDefault(key, DELIVERY);
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
```

```java
package com.workloadhub.forecast.lifecycle;

import java.util.Locale;

/** How a task reached its assignee. */
public enum Mode {
    SELF_PICKED, PROJECT, MANUAL;

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
```

```java
package com.workloadhub.forecast.lifecycle;

import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.data.rows.TaskRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** A task with the dates and hours the forecast derives from it. */
public record TaskFacts(
        TaskRow task,
        LocalDateTime assigned,
        LocalDateTime started,
        LocalDateTime finished,
        Family family,
        Mode mode,
        double actualHours,
        boolean unlogged,
        boolean assignmentFallback) {

    public UUID id() {
        return task.id();
    }

    public UUID assignee() {
        return task.assigneeId();
    }

    public double estimate() {
        return task.estimate() == null ? 0.0 : task.estimate();
    }

    public Double remaining() {
        return task.remaining();
    }

    public boolean done() {
        return "DONE".equals(task.statusCategory());
    }

    public boolean inProgress() {
        return "IN_PROGRESS".equals(task.statusCategory());
    }

    public boolean isAssigned() {
        return assigned != null;
    }

    public LocalDate assignedDay() {
        return assigned == null ? null : assigned.toLocalDate();
    }

    public LocalDate assignedWeek() {
        return assigned == null ? null : Weeks.mondayOf(assigned.toLocalDate());
    }

    public long lagDays() {
        return assigned == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(task.createdDate().toLocalDate(), assigned.toLocalDate()));
    }

    public boolean fresh() {
        return lagDays() < Lifecycle.BACKLOG_LAG_DAYS;
    }

    public Integer cycleDays() {
        if (assigned == null || finished == null) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(assigned.toLocalDate(), finished.toLocalDate()) + 1;
    }

    public Integer latenessDays() {
        if (finished == null || task.dueDate() == null) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(task.dueDate(), finished.toLocalDate());
    }

    /** Assigned on or before the day and not finished by its end. */
    public boolean openAtEndOf(LocalDate day) {
        if (assigned == null || assigned.toLocalDate().isAfter(day)) {
            return false;
        }
        return finished == null || finished.toLocalDate().isAfter(day);
    }

    public boolean inProgressAtEndOf(LocalDate day) {
        return openAtEndOf(day) && started != null && !started.toLocalDate().isAfter(day);
    }
}
```

- [ ] **Step 4: Write `Lifecycle`**

```java
package com.workloadhub.forecast.lifecycle;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.data.rows.TransitionRow;
import com.workloadhub.forecast.data.rows.UserRef;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** The lifecycle rules of the spec, section 5, applied to every task once. */
public record Lifecycle(Map<UUID, TaskFacts> facts, List<String> unresolvedAssignments, List<String> unloggedTasks) {

    public static final int BACKLOG_LAG_DAYS = 2;
    private static final Set<String> UNASSIGNED_VALUES = Set.of("", "none", "null");
    private static final Comparator<UUID> BY_ID = Comparator.comparing(UUID::toString);

    public Lifecycle {
        facts = Map.copyOf(facts);
        unresolvedAssignments = List.copyOf(unresolvedAssignments);
        unloggedTasks = List.copyOf(unloggedTasks);
    }

    public static Lifecycle derive(ForecastData data) {
        Resolver resolver = new Resolver(data);
        Map<UUID, List<TransitionRow>> transitions = data.transitionsByTask();
        Map<UUID, List<TimeLogRow>> logs = data.logsByTask();
        Map<UUID, TaskRow> byId = data.taskById();
        Map<UUID, TaskFacts> out = new TreeMap<>(BY_ID);
        List<String> unresolved = new ArrayList<>();
        List<String> unlogged = new ArrayList<>();
        for (TaskRow t : data.tasks()) {
            List<TransitionRow> history = transitions.getOrDefault(t.id(), List.of());
            Assignment a = assignment(t, history, resolver);
            LocalDateTime started = t.startedDate() != null ? t.startedDate() : firstEntry(history, data, "IN_PROGRESS");
            LocalDateTime finished = null;
            if ("DONE".equals(t.statusCategory())) {
                finished = t.finishedDate() != null ? t.finishedDate() : firstEntry(history, data, "DONE");
            }
            double actual = 0.0;
            for (TimeLogRow l : logs.getOrDefault(t.id(), List.of())) {
                if (t.assigneeId() != null && t.assigneeId().equals(l.userId())) {
                    actual += l.hours();
                }
            }
            boolean isUnlogged = false;
            if (actual == 0.0 && finished != null && t.assigneeId() != null && t.estimate() != null) {
                actual = Math.max(0.0, t.estimate() - (t.remaining() == null ? 0.0 : t.remaining()));
                isUnlogged = true;
                unlogged.add(t.key());
            }
            if (a.fallback()) {
                unresolved.add(t.key());
            }
            out.put(t.id(), new TaskFacts(t, a.at(), started, finished, family(t, byId), mode(t), actual, isUnlogged, a.fallback()));
        }
        return new Lifecycle(out, unresolved, unlogged);
    }

    private record Assignment(LocalDateTime at, boolean fallback) {
    }

    private static Assignment assignment(TaskRow t, List<TransitionRow> history, Resolver resolver) {
        if (t.assigneeId() == null) {
            return new Assignment(null, false);
        }
        boolean sawAssigneeRow = false;
        for (int i = history.size() - 1; i >= 0; i--) {
            TransitionRow row = history.get(i);
            if (!"assignee".equals(row.field())) {
                continue;
            }
            sawAssigneeRow = true;
            UUID resolved = resolver.resolve(row.newValue(), t.assigneeId());
            if (t.assigneeId().equals(resolved)) {
                return new Assignment(row.changedAt(), false);
            }
        }
        return new Assignment(t.createdDate(), sawAssigneeRow);
    }

    private static LocalDateTime firstEntry(List<TransitionRow> history, ForecastData data, String category) {
        for (TransitionRow row : history) {
            if ("status".equals(row.field()) && category.equals(data.statusCategoryByName().get(row.newValue()))) {
                return row.changedAt();
            }
        }
        return null;
    }

    private static Family family(TaskRow t, Map<UUID, TaskRow> byId) {
        Family own = Family.ofType(t.typeName());
        if (own != null) {
            return own;
        }
        TaskRow parent = t.parentId() == null ? null : byId.get(t.parentId());
        Family parentFamily = parent == null ? null : Family.ofType(parent.typeName());
        return parentFamily == null ? Family.DELIVERY : parentFamily;
    }

    private static Mode mode(TaskRow t) {
        if (t.reporterId() != null && t.reporterId().equals(t.assigneeId())) {
            return Mode.SELF_PICKED;
        }
        return t.parentId() != null ? Mode.PROJECT : Mode.MANUAL;
    }

    public TaskFacts of(UUID taskId) {
        return facts.get(taskId);
    }

    public List<TaskFacts> all() {
        return facts.values().stream().sorted(Comparator.comparing(f -> f.id().toString())).toList();
    }

    public List<TaskFacts> assignedTo(UUID member) {
        return facts.values().stream()
                .filter(f -> member.equals(f.assignee()) && f.isAssigned())
                .sorted(Comparator.comparing(TaskFacts::assigned).thenComparing(f -> f.id().toString()))
                .toList();
    }

    /** UUID, else email, else full name; counted members first, then every user; ambiguity resolves to null. */
    static final class Resolver {
        private final Set<UUID> userIds = new java.util.HashSet<>();
        private final Map<String, List<UUID>> membersByEmail = new HashMap<>();
        private final Map<String, List<UUID>> membersByName = new HashMap<>();
        private final Map<String, List<UUID>> usersByEmail = new HashMap<>();
        private final Map<String, List<UUID>> usersByName = new HashMap<>();

        Resolver(ForecastData data) {
            for (MemberRow m : data.members()) {
                add(membersByEmail, m.email(), m.id());
                add(membersByName, m.fullName(), m.id());
            }
            for (UserRef u : data.users()) {
                userIds.add(u.id());
                add(usersByEmail, u.email(), u.id());
                add(usersByName, u.fullName(), u.id());
            }
        }

        private static void add(Map<String, List<UUID>> index, String key, UUID id) {
            if (key != null && !key.isBlank()) {
                index.computeIfAbsent(key.trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(id);
            }
        }

        UUID resolve(String value, UUID expected) {
            if (value == null || UNASSIGNED_VALUES.contains(value.trim().toLowerCase(Locale.ROOT))) {
                return null;
            }
            String key = value.trim().toLowerCase(Locale.ROOT);
            try {
                UUID id = UUID.fromString(key);
                return userIds.contains(id) ? id : null;
            } catch (IllegalArgumentException notAUuid) {
                // fall through to names
            }
            for (Map<String, List<UUID>> index : List.of(membersByEmail, membersByName, usersByEmail, usersByName)) {
                List<UUID> hits = index.get(key);
                if (hits != null && !hits.isEmpty()) {
                    return hits.size() == 1 ? hits.get(0) : null;
                }
            }
            return null;
        }
    }
}
```

The `expected` parameter of `resolve` is unused by the rule as written and exists only to make the intent readable at the call site; drop it if the reviewer prefers. Ambiguity means an exact-count match: two counted members with the same full name make the name unresolvable even when one of them is the task's assignee, which is what the spec says ("ambiguous … falls back to `created_date` and is counted").

- [ ] **Step 5: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=LifecycleTest`
Expected: 9 passed. If `seededDataResolvesEveryAssignmentAndSplitsFreshFromBacklog` fails on `fresh`, check the seed's `WorkQueue` lag rule (about 40 % of tasks start with a lag in the default rates); the bounds are deliberately wide.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/lifecycle server/forecast-core/src/test/java/com/workloadhub/forecast/lifecycle server/forecast-core/src/test/java/com/workloadhub/forecast/testing/TestData.java server/forecast-core/src/main/java/com/workloadhub/forecast/data/rows
git commit -m "feat(server): derive each task's lifecycle dates, hours, family and mode

Assignment dates come from the assignee history resolved by UUID, email
or name; started and finished fall back to the status history; a
finished task without logs takes estimate minus remaining and is flagged."
```

---

### Task 4: Weekly series, column names and the `FeatureMatrix` value

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/features/MemberWeek.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/features/WeeklySeries.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/features/Features.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/features/FeatureMatrix.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/features/WeeklySeriesTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/features/FeaturesTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/features/FeatureMatrixTest.java`

**Interfaces:**
- `record MemberWeek(UUID member, LocalDate week) implements Comparable<MemberWeek>` ordered by member id string then week; the row key of the matrix and of every placement map.
- `WeeklySeries`: `record Cell(double estHours, double freshHours, int nTasks, int freshTasks)` with `Cell.ZERO`; `static WeeklySeries build(Lifecycle lc, List<MemberRow> members, List<LocalDate> weeks)` (arrivals with an assignment week inside `weeks`, keyed by assignee; a task counts for its assignee only when the assignee is one of `members`); `Cell cell(UUID member, LocalDate week)` (`ZERO` when absent); `List<LocalDate> weeks()`; `List<MemberRow> members()`; `double[] fresh(UUID member)` and `double[] est(UUID member)` aligned on `weeks()`.
- `Features`: `int[] HORIZONS = {1, 2, 3}`, `int[] LAGS = {1, 2, 3, 4, 8, 13}`, `int[] ROLL_WINDOWS = {4, 8, 13}`, `int WINDOW_13 = 13`, `int HISTORY_WEEKS = 65`, `List<String> CATEGORICAL = member_id, team_id, role, job_title`, `String FRESH = "fresh_hours"`, `String EST = "est_hours"`, `static String target(int h)` (`target_h{h}`), `static List<String> featureColumns(int h)` (46 names in the order of the spec's table), `static List<String> allColumns()` (the shared columns once, the `_h{h}` columns and the targets for every horizon, plus `fresh_hours` and `est_hours`, which are stored for the floor and the facts and are not features), `static boolean isCategorical(String column)`.
- `FeatureMatrix`: `static FeatureMatrix of(List<String> columns, List<MemberWeek> keys, double[][] values, Map<String, List<String>> codebooks)`; `List<String> columns()`, `int rowCount()`, `MemberWeek key(int i)`, `List<MemberWeek> keys()`, `int columnIndex(String)` (throws on unknown), `double get(int row, String column)`, `double[] column(String)`, `double[] target(int h)`, `FeatureMatrix filter(Predicate<MemberWeek>)`, `FeatureMatrix rowsWithKnown(String column)` (drops rows where the column is NaN), `List<String> nonEmptyColumns(List<String> candidates)` (those with at least one non-NaN value), `float[] flatten(List<String> columns)` (row-major, NaN for missing, for `DMatrix`), `Map<String, List<String>> codebooks()`, `String decode(String column, double code)`; immutable, `values` copied on construction.

- [ ] **Step 1: Write the failing tests**

```java
package com.workloadhub.forecast.features;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeeklySeriesTest {

    static final MemberRow ANA = TestData.member("ana", TestData.TEAM);
    static final MemberRow BEN = TestData.member("ben", TestData.TEAM);
    static final LocalDate W1 = LocalDate.of(2026, 8, 3);

    @Test
    void sumsEstimatesPerAssigneeAndWeekAndSplitsFreshFromBacklog() {
        LocalDateTime created = W1.atTime(9, 0);
        TaskRow fresh = TestData.task("1", ANA.id(), created, 8);
        TaskRow lagged = TestData.task("2", ANA.id(), created, 5);
        TaskRow nextWeek = TestData.task("3", ANA.id(), created.plusWeeks(1), 3);
        TaskRow bens = TestData.task("4", BEN.id(), created, 2);
        ForecastData data = TestData.data(List.of(ANA, BEN), List.of(fresh, lagged, nextWeek, bens),
                List.of(TestData.assignee(lagged.id(), ANA.fullName(), created.plusDays(3))), List.of());
        WeeklySeries s = WeeklySeries.build(Lifecycle.derive(data), List.of(ANA, BEN), List.of(W1, W1.plusWeeks(1), W1.plusWeeks(2)));
        assertEquals(new WeeklySeries.Cell(13.0, 8.0, 2, 1), s.cell(ANA.id(), W1));
        assertEquals(new WeeklySeries.Cell(3.0, 3.0, 1, 1), s.cell(ANA.id(), W1.plusWeeks(1)));
        assertEquals(WeeklySeries.Cell.ZERO, s.cell(ANA.id(), W1.plusWeeks(2)));
        assertEquals(new WeeklySeries.Cell(2.0, 2.0, 1, 1), s.cell(BEN.id(), W1));
        assertEquals(List.of(8.0, 3.0, 0.0), java.util.Arrays.stream(s.fresh(ANA.id())).boxed().toList());
        assertEquals(List.of(13.0, 3.0, 0.0), java.util.Arrays.stream(s.est(ANA.id())).boxed().toList());
    }

    @Test
    void arrivalsOutsideTheWeeksOrToOtherPeopleAreIgnored() {
        LocalDateTime created = W1.minusWeeks(1).atTime(9, 0);
        TaskRow early = TestData.task("1", ANA.id(), created, 8);
        TaskRow unassigned = TestData.task("2", null, created, 8);
        ForecastData data = TestData.data(List.of(ANA, BEN), List.of(early, unassigned), List.of(), List.of());
        WeeklySeries s = WeeklySeries.build(Lifecycle.derive(data), List.of(BEN), List.of(W1));
        assertEquals(WeeklySeries.Cell.ZERO, s.cell(ANA.id(), W1));
        assertEquals(WeeklySeries.Cell.ZERO, s.cell(BEN.id(), W1));
        assertEquals(1, s.members().size());
    }
}
```

```java
package com.workloadhub.forecast.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeaturesTest {

    @Test
    void fortySixColumnsPerHorizonInTheSpecOrderWithoutDuplicates() {
        for (int h : Features.HORIZONS) {
            List<String> cols = Features.featureColumns(h);
            assertEquals(46, cols.size(), cols.toString());
            assertEquals(cols.size(), new HashSet<>(cols).size());
            assertEquals("lag1", cols.get(0));
            assertTrue(cols.contains("due_hrs_h" + h));
            assertTrue(cols.contains("available_hrs_h" + h));
            assertFalse(cols.contains(Features.target(h)));
            assertFalse(cols.contains(Features.FRESH));
            for (int other : Features.HORIZONS) {
                if (other != h) {
                    assertFalse(cols.contains("due_hrs_h" + other));
                }
            }
        }
    }

    @Test
    void allColumnsHoldEveryHorizonOnceAndTheSeriesValues() {
        List<String> all = Features.allColumns();
        assertEquals(all.size(), new HashSet<>(all).size());
        for (int h : Features.HORIZONS) {
            assertTrue(all.containsAll(Features.featureColumns(h)));
            assertTrue(all.contains(Features.target(h)));
        }
        assertTrue(all.contains(Features.FRESH));
        assertTrue(all.contains(Features.EST));
        assertEquals(List.of("member_id", "team_id", "role", "job_title"), Features.CATEGORICAL);
        assertTrue(Features.isCategorical("role"));
        assertFalse(Features.isCategorical("lag1"));
    }
}
```

```java
package com.workloadhub.forecast.features;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeatureMatrixTest {

    static final UUID A = UUID.fromString("30000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("30000000-0000-0000-0000-00000000000b");
    static final LocalDate W = LocalDate.of(2026, 8, 3);

    static FeatureMatrix sample() {
        List<String> cols = List.of("lag1", "member_id", "target_h1");
        List<MemberWeek> keys = List.of(new MemberWeek(A, W), new MemberWeek(A, W.plusWeeks(1)), new MemberWeek(B, W));
        double[][] values = {{1.0, 0, 2.0}, {2.0, 0, Double.NaN}, {Double.NaN, 1, 4.0}};
        return FeatureMatrix.of(cols, keys, values, Map.of("member_id", List.of(A.toString(), B.toString())));
    }

    @Test
    void columnsRowsAndTargetsAreAddressable() {
        FeatureMatrix m = sample();
        assertEquals(3, m.rowCount());
        assertEquals(1, m.columnIndex("member_id"));
        assertArrayEquals(new double[] {2.0, Double.NaN, 4.0}, m.target(1));
        assertEquals(2.0, m.get(1, "lag1"));
        assertEquals(B.toString(), m.decode("member_id", 1));
        assertThrows(IllegalArgumentException.class, () -> m.columnIndex("nope"));
    }

    @Test
    void filterAndKnownRowsKeepKeysAlignedWithValues() {
        FeatureMatrix m = sample();
        FeatureMatrix onlyA = m.filter(k -> k.member().equals(A));
        assertEquals(2, onlyA.rowCount());
        assertEquals(new MemberWeek(A, W.plusWeeks(1)), onlyA.key(1));
        FeatureMatrix known = m.rowsWithKnown("target_h1");
        assertEquals(List.of(new MemberWeek(A, W), new MemberWeek(B, W)), known.keys());
        assertEquals(4.0, known.get(1, "target_h1"));
    }

    @Test
    void flattenIsRowMajorWithNaNForMissingAndNonEmptyColumnsDropAllNaN() {
        FeatureMatrix m = sample();
        float[] flat = m.flatten(List.of("lag1", "member_id"));
        assertEquals(6, flat.length);
        assertEquals(1.0f, flat[0]);
        assertTrue(Float.isNaN(flat[4]));
        FeatureMatrix all = FeatureMatrix.of(List.of("x", "y"), List.of(new MemberWeek(A, W)), new double[][] {{Double.NaN, 1}}, Map.of());
        assertEquals(List.of("y"), all.nonEmptyColumns(List.of("x", "y")));
    }

    @Test
    void valuesAreCopiedSoTheCallerCannotMutateTheMatrix() {
        double[][] values = {{1.0, 0, 2.0}};
        FeatureMatrix m = FeatureMatrix.of(List.of("lag1", "member_id", "target_h1"), List.of(new MemberWeek(A, W)), values, Map.of());
        values[0][0] = 99;
        assertEquals(1.0, m.get(0, "lag1"));
        assertTrue(new MemberWeek(A, W).compareTo(new MemberWeek(B, W)) < 0);
        assertTrue(new MemberWeek(A, W).compareTo(new MemberWeek(A, W.plusWeeks(1))) < 0);
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='WeeklySeriesTest,FeaturesTest,FeatureMatrixTest'`
Expected: compilation errors.

- [ ] **Step 3: Write `MemberWeek`, `WeeklySeries` and `Features`**

```java
package com.workloadhub.forecast.features;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;

/** The row key: one counted member in one Monday week. */
public record MemberWeek(UUID member, LocalDate week) implements Comparable<MemberWeek> {

    private static final Comparator<MemberWeek> ORDER =
            Comparator.comparing((MemberWeek k) -> k.member().toString()).thenComparing(MemberWeek::week);

    @Override
    public int compareTo(MemberWeek o) {
        return ORDER.compare(this, o);
    }
}
```

```java
package com.workloadhub.forecast.features;

import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Arrivals per member and week: all estimated hours, the fresh part, and the counts. */
public final class WeeklySeries {

    public record Cell(double estHours, double freshHours, int nTasks, int freshTasks) {
        public static final Cell ZERO = new Cell(0.0, 0.0, 0, 0);

        Cell plus(TaskFacts f) {
            boolean fresh = f.fresh();
            return new Cell(estHours + f.estimate(), freshHours + (fresh ? f.estimate() : 0.0), nTasks + 1, freshTasks + (fresh ? 1 : 0));
        }
    }

    private final List<MemberRow> members;
    private final List<LocalDate> weeks;
    private final Map<MemberWeek, Cell> cells;

    private WeeklySeries(List<MemberRow> members, List<LocalDate> weeks, Map<MemberWeek, Cell> cells) {
        this.members = List.copyOf(members);
        this.weeks = List.copyOf(weeks);
        this.cells = Map.copyOf(cells);
    }

    public static WeeklySeries build(Lifecycle lc, List<MemberRow> members, List<LocalDate> weeks) {
        Set<UUID> ids = members.stream().map(MemberRow::id).collect(Collectors.toSet());
        Set<LocalDate> inRange = Set.copyOf(weeks);
        Map<MemberWeek, Cell> cells = new HashMap<>();
        for (TaskFacts f : lc.all()) {
            if (!f.isAssigned() || !ids.contains(f.assignee())) {
                continue;
            }
            LocalDate week = f.assignedWeek();
            if (!inRange.contains(week)) {
                continue;
            }
            cells.merge(new MemberWeek(f.assignee(), week), Cell.ZERO.plus(f), (a, b) -> a.plus(f));
        }
        return new WeeklySeries(members, weeks, cells);
    }

    public Cell cell(UUID member, LocalDate week) {
        return cells.getOrDefault(new MemberWeek(member, week), Cell.ZERO);
    }

    public List<LocalDate> weeks() {
        return weeks;
    }

    public List<MemberRow> members() {
        return members;
    }

    public double[] fresh(UUID member) {
        return weeks.stream().mapToDouble(w -> cell(member, w).freshHours()).toArray();
    }

    public double[] est(UUID member) {
        return weeks.stream().mapToDouble(w -> cell(member, w).estHours()).toArray();
    }
}
```

The `merge` lambda `(a, b) -> a.plus(f)` ignores `b` on purpose: `b` is the fresh single-task cell and `a.plus(f)` adds the same task once.

```java
package com.workloadhub.forecast.features;

import java.util.ArrayList;
import java.util.List;

/** Column names and constants of the feature matrix (spec section 6). */
public final class Features {

    public static final int[] HORIZONS = {1, 2, 3};
    public static final int[] LAGS = {1, 2, 3, 4, 8, 13};
    public static final int[] ROLL_WINDOWS = {4, 8, 13};
    public static final int WINDOW_13 = 13;
    /** Weeks of history a run loads before the origin: a year for the floor plus the longest window. */
    public static final int HISTORY_WEEKS = 65;
    public static final List<String> CATEGORICAL = List.of("member_id", "team_id", "role", "job_title");
    public static final String FRESH = "fresh_hours";
    public static final String EST = "est_hours";

    private static final List<String> SHARED = build();

    private Features() {
    }

    private static List<String> build() {
        List<String> c = new ArrayList<>();
        for (int lag : LAGS) {
            c.add("lag" + lag);
        }
        for (int w : ROLL_WINDOWS) {
            c.add("roll_mean_" + w);
            c.add("roll_std_" + w);
        }
        c.add("weeks_since_last_arrival");
        c.add("arrivals_13w");
        c.add("share_defect_13w");
        c.add("share_delivery_13w");
        c.add("share_support_13w");
        c.add("share_high_priority_13w");
        c.add("share_self_picked_13w");
        c.add("share_manual_13w");
        c.add("share_project_13w");
        c.add("reopen_rate_13w");
        for (int k = 1; k <= 4; k++) {
            c.add("logged_hours_lag" + k);
        }
        c.add("open_tasks");
        c.add("open_remaining_hrs");
        c.add("overdue_open");
        c.add("in_progress_tasks");
        c.add("estimate_ratio_13w");
        c.add("cycle_days_13w");
        c.add("team_backlog_unassigned_hrs");
        c.add("proj_active");
        c.add("proj_planning");
        c.add("proj_first_due_weeks");
        c.addAll(CATEGORICAL);
        c.add("tenure_weeks");
        c.add("week_of_year");
        return List.copyOf(c);
    }

    public static String target(int h) {
        return "target_h" + h;
    }

    public static List<String> horizonColumns(int h) {
        return List.of("due_hrs_h" + h, "working_days_h" + h, "absence_hrs_h" + h, "available_hrs_h" + h);
    }

    /** The 46 features of one horizon: 42 shared columns, then the four `_h{h}` columns. */
    public static List<String> featureColumns(int h) {
        List<String> c = new ArrayList<>(SHARED);
        c.addAll(horizonColumns(h));
        return List.copyOf(c);
    }

    /** Every stored column: shared features, per-horizon features and targets, and the two series values. */
    public static List<String> allColumns() {
        List<String> c = new ArrayList<>(SHARED);
        for (int h : HORIZONS) {
            c.addAll(horizonColumns(h));
        }
        for (int h : HORIZONS) {
            c.add(target(h));
        }
        c.add(FRESH);
        c.add(EST);
        return List.copyOf(c);
    }

    public static boolean isCategorical(String column) {
        return CATEGORICAL.contains(column);
    }
}
```

- [ ] **Step 4: Write `FeatureMatrix`**

```java
package com.workloadhub.forecast.features;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** An immutable table: named columns, member-week keys, doubles with NaN for unknown, code books for the categorical columns. */
public final class FeatureMatrix {

    private final List<String> columns;
    private final Map<String, Integer> index;
    private final List<MemberWeek> keys;
    private final double[][] values;
    private final Map<String, List<String>> codebooks;

    private FeatureMatrix(List<String> columns, List<MemberWeek> keys, double[][] values, Map<String, List<String>> codebooks) {
        this.columns = List.copyOf(columns);
        this.index = new LinkedHashMap<>();
        for (int i = 0; i < this.columns.size(); i++) {
            this.index.put(this.columns.get(i), i);
        }
        this.keys = List.copyOf(keys);
        this.values = values;
        Map<String, List<String>> books = new LinkedHashMap<>();
        codebooks.forEach((k, v) -> books.put(k, List.copyOf(v)));
        this.codebooks = books;
    }

    public static FeatureMatrix of(List<String> columns, List<MemberWeek> keys, double[][] values, Map<String, List<String>> codebooks) {
        if (keys.size() != values.length) {
            throw new IllegalArgumentException("keys " + keys.size() + " rows " + values.length);
        }
        double[][] copy = new double[values.length][];
        for (int i = 0; i < values.length; i++) {
            if (values[i].length != columns.size()) {
                throw new IllegalArgumentException("row " + i + " has " + values[i].length + " values for " + columns.size() + " columns");
            }
            copy[i] = values[i].clone();
        }
        return new FeatureMatrix(columns, keys, copy, codebooks);
    }

    public List<String> columns() {
        return columns;
    }

    public int rowCount() {
        return keys.size();
    }

    public MemberWeek key(int i) {
        return keys.get(i);
    }

    public List<MemberWeek> keys() {
        return keys;
    }

    public Map<String, List<String>> codebooks() {
        return codebooks;
    }

    public int columnIndex(String column) {
        Integer i = index.get(column);
        if (i == null) {
            throw new IllegalArgumentException("unknown column " + column);
        }
        return i;
    }

    public double get(int row, String column) {
        return values[row][columnIndex(column)];
    }

    public double[] column(String column) {
        int c = columnIndex(column);
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i][c];
        }
        return out;
    }

    public double[] target(int h) {
        return column(Features.target(h));
    }

    public FeatureMatrix filter(Predicate<MemberWeek> keep) {
        List<MemberWeek> k = new ArrayList<>();
        List<double[]> v = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            if (keep.test(keys.get(i))) {
                k.add(keys.get(i));
                v.add(values[i]);
            }
        }
        return new FeatureMatrix(columns, k, v.toArray(double[][]::new), codebooks);
    }

    public FeatureMatrix rowsWithKnown(String column) {
        int c = columnIndex(column);
        List<MemberWeek> k = new ArrayList<>();
        List<double[]> v = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            if (!Double.isNaN(values[i][c])) {
                k.add(keys.get(i));
                v.add(values[i]);
            }
        }
        return new FeatureMatrix(columns, k, v.toArray(double[][]::new), codebooks);
    }

    public List<String> nonEmptyColumns(List<String> candidates) {
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            int ci = columnIndex(c);
            for (double[] row : values) {
                if (!Double.isNaN(row[ci])) {
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }

    /** Row-major floats over the given columns, NaN where unknown: the layout XGBoost's DMatrix takes. */
    public float[] flatten(List<String> cols) {
        int[] ci = cols.stream().mapToInt(this::columnIndex).toArray();
        float[] out = new float[values.length * ci.length];
        int p = 0;
        for (double[] row : values) {
            for (int c : ci) {
                out[p++] = (float) row[c];
            }
        }
        return out;
    }

    public String decode(String column, double code) {
        List<String> book = codebooks.get(column);
        if (book == null || Double.isNaN(code)) {
            return null;
        }
        return book.get((int) code);
    }
}
```

Rows returned by `filter` and `rowsWithKnown` share the backing arrays with the parent; that is safe because no method mutates them and `of` is the only entry point that takes caller arrays.

- [ ] **Step 5: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='WeeklySeriesTest,FeaturesTest,FeatureMatrixTest'`
Expected: 8 passed.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/features server/forecast-core/src/test/java/com/workloadhub/forecast/features
git commit -m "feat(server): weekly arrival series, column names and the feature matrix value

The series splits fresh arrivals from backlog ones; the matrix is an
immutable table with code books, row filters and a DMatrix-ready layout."
```

---

### Task 5: `FeatureBuilder`, part 1: own history, availability, identity, targets

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/features/FeatureBuilder.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/features/FeatureBuilderTest.java`

**Interfaces:**
- `new FeatureBuilder(ForecastData data, Lifecycle lc, WorkingCalendar cal, CapacityRule rule)`.
- `FeatureMatrix build(List<MemberRow> members, LocalDate origin)`: columns `Features.allColumns()`; weeks from `origin − (HISTORY_WEEKS − 1)` weeks to `origin` (Mondays); one row per member and week from the member's start week (the earlier of the join week and the first assignment week, never before the first loaded week) to the origin, skipping weeks on or after the member's `left` date; rows sorted by `MemberWeek`. Code books: `member_id` = the members' ids as strings, sorted; `team_id` = distinct primary team ids, sorted; `role` and `job_title` = distinct values sorted, null job title stored as `"(none)"`.
- Column rules for this task (the series is `fresh_hours`, `f[i]` at week index `i`, `s` = the member's start index):
  - `lag{k}` = `f[i − (k − 1)]` when `i − (k − 1) ≥ s`, else NaN (so `lag1` is the row's own week, as the Python `shift(lag − 1)`).
  - `roll_mean_{w}` = mean of `f[max(s, i − w + 1) .. i]`; `roll_std_{w}` = sample standard deviation (n − 1) of the same values, 0 when fewer than two.
  - `weeks_since_last_arrival` = `i − j` for the latest `j ≤ i, j ≥ s` with `f[j] > 0`, else 52.
  - `arrivals_13w`, `share_{defect,delivery,support}_13w`, `share_high_priority_13w`, `share_{self_picked,manual,project}_13w`: over the member's tasks whose assignment week lies in `[w − 12 weeks, w]` (all arrivals, fresh or not); family and priority shares are 0 with no tasks, mode shares are 1/3 each with no tasks (as the Python `_style_shares`); `HIGHEST` and `HIGH` count as high priority.
  - `reopen_rate_13w` = share of the member's tasks finished in that window with `reopened_from_done`, 0 with none. The flag is the current one (the row has no reopen timestamp); accepted approximation, listed in the plan's closing notes.
  - `estimate_ratio_13w` = Σ actual / Σ estimate over the member's tasks finished in the window with estimate > 0 and actual > 0, 1.0 with none; `cycle_days_13w` = median `cycleDays()` over the tasks finished in the window, NaN with none.
  - `working_days_h{h}` = `cal.workingDaysInWeek(w + h)`; `absence_hrs_h{h}` = `rule.absenceHours(member, w + h, …)`; `available_hrs_h{h}` = `rule.capacity(member, w + h, …)`; memoised per `MemberWeek` because neighbouring rows ask for the same target week.
  - `member_id`, `team_id`, `role`, `job_title` codes; `tenure_weeks` = `Weeks.weeksBetween(joined, w)`; `week_of_year` = `Weeks.isoWeek(w)`.
  - `target_h{h}` = `f[i + h]` when `i + h ≤` the origin's index, else NaN; `fresh_hours` = `f[i]`; `est_hours` = the est series at `i`.
  - The throughput and target-week columns of Task 6 (`logged_hours_lag1..4`, `open_tasks`, `open_remaining_hrs`, `overdue_open`, `in_progress_tasks`, `team_backlog_unassigned_hrs`, `proj_active`, `proj_planning`, `proj_first_due_weeks`, `due_hrs_h{h}`) are NaN in this task and filled in the next one.

- [ ] **Step 1: Write the failing tests**

```java
package com.workloadhub.forecast.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeatureBuilderTest {

    static final WorkingCalendar CAL = WorkingCalendar.fromHolidays(List.of());
    static final CapacityRule RULE = new CapacityRule(40);
    static final LocalDate ORIGIN = LocalDate.of(2026, 8, 24);
    static final MemberRow ANA = TestData.member("ana", TestData.TEAM).withJoined(ORIGIN.minusWeeks(6));

    /** Ana receives 8 h in week −5, 4 h (backlog, lag 3 days) in week −4, 12 h in week −2, a Bug in week −1. */
    static ForecastData ana() {
        List<TaskRow> tasks = new ArrayList<>();
        LocalDateTime w5 = ORIGIN.minusWeeks(5).atTime(9, 0);
        tasks.add(TestData.task("1", ANA.id(), w5, 8));
        TaskRow lagged = TestData.task("2", ANA.id(), ORIGIN.minusWeeks(4).atTime(9, 0), 4);
        tasks.add(lagged);
        tasks.add(TestData.task("3", ANA.id(), ORIGIN.minusWeeks(2).atTime(9, 0), 12).withPriority("HIGH"));
        tasks.add(TestData.task("4", ANA.id(), ORIGIN.minusWeeks(1).atTime(9, 0), 2).withType("Bug").withReporter(ANA.id()));
        return TestData.data(List.of(ANA), tasks,
                List.of(TestData.assignee(lagged.id(), ANA.fullName(), lagged.createdDate().plusDays(3))), List.of());
    }

    static FeatureMatrix matrix(ForecastData data) {
        return new FeatureBuilder(data, Lifecycle.derive(data), CAL, RULE).build(data.members(), ORIGIN);
    }

    static int row(FeatureMatrix m, LocalDate week) {
        return m.keys().indexOf(new MemberWeek(ANA.id(), week));
    }

    @Test
    void rowsRunFromTheJoinWeekToTheOriginWithAllColumns() {
        FeatureMatrix m = matrix(ana());
        assertEquals(7, m.rowCount(), "join week −6 to origin inclusive");
        assertEquals(Features.allColumns(), m.columns());
        assertEquals(new MemberWeek(ANA.id(), ORIGIN.minusWeeks(6)), m.key(0));
        assertEquals(new MemberWeek(ANA.id(), ORIGIN), m.key(6));
    }

    @Test
    void lagsRollingStatsAndGapFollowTheFreshSeries() {
        FeatureMatrix m = matrix(ana());
        int origin = row(m, ORIGIN);
        assertEquals(0.0, m.get(origin, "lag1"));
        assertEquals(2.0, m.get(origin, "lag2"));
        assertEquals(12.0, m.get(origin, "lag3"));
        assertEquals(0.0, m.get(origin, "lag4"), "week −3 had nothing");
        assertTrue(Double.isNaN(m.get(origin, "lag8")), "before the member's first row");
        assertEquals(0.0, m.get(origin, "fresh_hours"));
        int w4 = row(m, ORIGIN.minusWeeks(4));
        assertEquals(0.0, m.get(w4, "fresh_hours"), "the 4 h task had a 3-day lag");
        assertEquals(4.0, m.get(w4, "est_hours"));
        assertEquals((0 + 8 + 0) / 3.0, m.get(w4, "roll_mean_4"), 1e-9, "three rows so far");
        assertEquals((0 + 8 + 0 + 0 + 12 + 2 + 0) / 7.0, m.get(origin, "roll_mean_8"), 1e-9);
        assertEquals(0.0, m.get(0, "roll_std_4"), "one value, no deviation");
        assertEquals(Math.sqrt(32.0), m.get(row(m, ORIGIN.minusWeeks(5)), "roll_std_4"), 1e-9, "sample std of {0, 8}");
        assertEquals(1.0, m.get(origin, "weeks_since_last_arrival"));
        assertEquals(52.0, m.get(0, "weeks_since_last_arrival"));
        assertEquals(1.0, m.get(w4, "weeks_since_last_arrival"), "week −5 was the last fresh arrival");
    }

    @Test
    void thirteenWeekSharesCountEveryArrivalFreshOrNot() {
        FeatureMatrix m = matrix(ana());
        int origin = row(m, ORIGIN);
        assertEquals(4.0, m.get(origin, "arrivals_13w"));
        assertEquals(0.25, m.get(origin, "share_defect_13w"));
        assertEquals(0.75, m.get(origin, "share_delivery_13w"));
        assertEquals(0.0, m.get(origin, "share_support_13w"));
        assertEquals(0.25, m.get(origin, "share_high_priority_13w"));
        assertEquals(0.25, m.get(origin, "share_self_picked_13w"));
        assertEquals(0.75, m.get(origin, "share_manual_13w"));
        assertEquals(0.0, m.get(origin, "share_project_13w"));
        assertEquals(1.0 / 3, m.get(0, "share_manual_13w"), 1e-9, "no arrivals yet: one third each");
        assertEquals(0.0, m.get(0, "share_defect_13w"));
        assertEquals(0.0, m.get(origin, "reopen_rate_13w"));
        assertEquals(1.0, m.get(origin, "estimate_ratio_13w"), "nothing finished: neutral ratio");
        assertTrue(Double.isNaN(m.get(origin, "cycle_days_13w")));
    }

    @Test
    void targetsAreTheFutureFreshHoursAndUnknownPastTheOrigin() {
        FeatureMatrix m = matrix(ana());
        int w3 = row(m, ORIGIN.minusWeeks(3));
        assertEquals(12.0, m.get(w3, "target_h1"));
        assertEquals(2.0, m.get(w3, "target_h2"));
        assertEquals(0.0, m.get(w3, "target_h3"));
        int w1 = row(m, ORIGIN.minusWeeks(1));
        assertEquals(0.0, m.get(w1, "target_h1"));
        assertTrue(Double.isNaN(m.get(w1, "target_h2")));
        assertTrue(Double.isNaN(m.get(row(m, ORIGIN), "target_h1")));
    }

    @Test
    void availabilityIdentityAndTenure() {
        FeatureMatrix m = matrix(ana());
        int origin = row(m, ORIGIN);
        assertEquals(5.0, m.get(origin, "working_days_h1"));
        assertEquals(0.0, m.get(origin, "absence_hrs_h2"));
        assertEquals(40.0, m.get(origin, "available_hrs_h3"));
        assertEquals(0.0, m.get(origin, "member_id"));
        assertEquals(ANA.id().toString(), m.decode("member_id", 0));
        assertEquals(TestData.TEAM.toString(), m.decode("team_id", m.get(origin, "team_id")));
        assertEquals("MEMBER", m.decode("role", m.get(origin, "role")));
        assertEquals("Engineer", m.decode("job_title", m.get(origin, "job_title")));
        assertEquals(6.0, m.get(origin, "tenure_weeks"));
        assertEquals(0.0, m.get(0, "tenure_weeks"));
        assertEquals(35.0, m.get(origin, "week_of_year"));
    }

    @Test
    void seededMatrixHasEveryFeatureColumnPopulated() {
        ForecastData data = SeededData.data();
        LocalDate origin = com.workloadhub.forecast.calendar.Weeks.lastCompleteWeek(SeededData.asOf());
        FeatureMatrix m = new FeatureBuilder(data, Lifecycle.derive(data), WorkingCalendar.fromHolidays(data.holidays()), RULE)
                .build(data.members(), origin);
        assertTrue(m.rowCount() > data.members().size() * 20, "rows " + m.rowCount());
        List<String> expected = new ArrayList<>(Features.featureColumns(1));
        expected.removeAll(List.of("logged_hours_lag1", "logged_hours_lag2", "logged_hours_lag3", "logged_hours_lag4", "open_tasks",
                "open_remaining_hrs", "overdue_open", "in_progress_tasks", "team_backlog_unassigned_hrs", "proj_active",
                "proj_planning", "proj_first_due_weeks", "due_hrs_h1"));
        assertEquals(expected, m.nonEmptyColumns(expected), "Task 6 fills the rest");
        for (int i = 1; i < m.rowCount(); i++) {
            assertTrue(m.key(i - 1).compareTo(m.key(i)) < 0, "rows sorted");
        }
        assertEquals(data.members().size(), m.codebooks().get("member_id").size());
    }
}
```

`TestData` gains `MemberRow.withJoined(LocalDate)` and `TaskRow.withPriority(String)` copies (on the records, as decided in Task 3). `week_of_year` 35 is ISO week of 2026-08-24; the implementer checks with `LocalDate.of(2026, 8, 24).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)` before trusting the number.

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=FeatureBuilderTest`
Expected: compilation errors.

- [ ] **Step 3: Write `FeatureBuilder`**

```java
package com.workloadhub.forecast.features;

import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.lifecycle.Family;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.Mode;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Builds the feature matrix of spec section 6 for a set of members up to an origin week. */
public final class FeatureBuilder {

    static final double NEVER_WEEKS = 52.0;
    static final String NO_TITLE = "(none)";

    private final ForecastData data;
    private final Lifecycle lc;
    private final WorkingCalendar cal;
    private final CapacityRule rule;

    public FeatureBuilder(ForecastData data, Lifecycle lc, WorkingCalendar cal, CapacityRule rule) {
        this.data = data;
        this.lc = lc;
        this.cal = cal;
        this.rule = rule;
    }

    public FeatureMatrix build(List<MemberRow> membersIn, LocalDate origin) {
        List<MemberRow> members = membersIn.stream().sorted((a, b) -> a.id().toString().compareTo(b.id().toString())).toList();
        LocalDate firstWeek = origin.minusWeeks(Features.HISTORY_WEEKS - 1);
        List<LocalDate> weeks = Weeks.between(firstWeek, origin);
        WeeklySeries series = WeeklySeries.build(lc, members, weeks);
        Map<String, List<String>> books = codebooks(members);
        List<String> columns = Features.allColumns();
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            col.put(columns.get(i), i);
        }
        TeamContext teams = new TeamContext(data, lc, weeks);          // Task 6
        Map<MemberWeek, double[]> availability = new HashMap<>();
        List<MemberWeek> keys = new ArrayList<>();
        List<double[]> rows = new ArrayList<>();
        int originIndex = weeks.size() - 1;
        for (MemberRow m : members) {
            List<TaskFacts> tasks = lc.assignedTo(m.id());
            MemberContext mc = new MemberContext(m, tasks, data);       // Task 6 uses the log index
            double[] fresh = series.fresh(m.id());
            double[] est = series.est(m.id());
            int start = startIndex(m, tasks, weeks);
            for (int i = start; i <= originIndex; i++) {
                LocalDate w = weeks.get(i);
                if (m.left() != null && !w.isBefore(m.left())) {
                    break;
                }
                double[] r = new double[columns.size()];
                Arrays.fill(r, Double.NaN);
                ownHistory(r, col, fresh, start, i);
                windowStats(r, col, mc, w);
                throughput(r, col, mc, w);                               // Task 6
                teams.fill(r, col, m.primaryTeamId(), w);                // Task 6
                for (int h : Features.HORIZONS) {
                    LocalDate target = w.plusWeeks(h);
                    double[] avail = availability.computeIfAbsent(new MemberWeek(m.id(), target), k -> new double[] {
                            cal.workingDaysInWeek(target), rule.absenceHours(m.id(), target, data, cal), rule.capacity(m, target, data, cal)});
                    r[col.get("working_days_h" + h)] = avail[0];
                    r[col.get("absence_hrs_h" + h)] = avail[1];
                    r[col.get("available_hrs_h" + h)] = avail[2];
                    r[col.get("due_hrs_h" + h)] = mc.dueHours(w, target);   // Task 6
                    r[col.get(Features.target(h))] = i + h <= originIndex ? fresh[i + h] : Double.NaN;
                }
                r[col.get("member_id")] = books.get("member_id").indexOf(m.id().toString());
                r[col.get("team_id")] = books.get("team_id").indexOf(m.primaryTeamId().toString());
                r[col.get("role")] = books.get("role").indexOf(m.role());
                r[col.get("job_title")] = books.get("job_title").indexOf(title(m));
                r[col.get("tenure_weeks")] = Weeks.weeksBetween(m.joined(), w);
                r[col.get("week_of_year")] = Weeks.isoWeek(w);
                r[col.get(Features.FRESH)] = fresh[i];
                r[col.get(Features.EST)] = est[i];
                keys.add(new MemberWeek(m.id(), w));
                rows.add(r);
            }
        }
        return FeatureMatrix.of(columns, keys, rows.toArray(double[][]::new), books);
    }

    private static String title(MemberRow m) {
        return m.jobTitle() == null || m.jobTitle().isBlank() ? NO_TITLE : m.jobTitle();
    }

    private static Map<String, List<String>> codebooks(List<MemberRow> members) {
        Map<String, List<String>> books = new LinkedHashMap<>();
        books.put("member_id", members.stream().map(m -> m.id().toString()).sorted().toList());
        books.put("team_id", members.stream().map(m -> m.primaryTeamId().toString()).distinct().sorted().toList());
        books.put("role", members.stream().map(MemberRow::role).distinct().sorted().toList());
        books.put("job_title", members.stream().map(FeatureBuilder::title).distinct().sorted().toList());
        return books;
    }

    /** The earlier of the join week and the first assignment week, never before the first loaded week. */
    private static int startIndex(MemberRow m, List<TaskFacts> tasks, List<LocalDate> weeks) {
        LocalDate start = Weeks.mondayOf(m.joined());
        if (!tasks.isEmpty() && tasks.get(0).assignedWeek().isBefore(start)) {
            start = tasks.get(0).assignedWeek();
        }
        int idx = weeks.indexOf(start);
        if (idx >= 0) {
            return idx;
        }
        return start.isBefore(weeks.get(0)) ? 0 : weeks.size();
    }

    private static void ownHistory(double[] r, Map<String, Integer> col, double[] f, int start, int i) {
        for (int lag : Features.LAGS) {
            int j = i - (lag - 1);
            r[col.get("lag" + lag)] = j >= start ? f[j] : Double.NaN;
        }
        for (int w : Features.ROLL_WINDOWS) {
            int from = Math.max(start, i - w + 1);
            int n = i - from + 1;
            double sum = 0;
            for (int j = from; j <= i; j++) {
                sum += f[j];
            }
            double mean = sum / n;
            double sq = 0;
            for (int j = from; j <= i; j++) {
                sq += (f[j] - mean) * (f[j] - mean);
            }
            r[col.get("roll_mean_" + w)] = mean;
            r[col.get("roll_std_" + w)] = n >= 2 ? Math.sqrt(sq / (n - 1)) : 0.0;
        }
        double since = NEVER_WEEKS;
        for (int j = i; j >= start; j--) {
            if (f[j] > 0) {
                since = i - j;
                break;
            }
        }
        r[col.get("weeks_since_last_arrival")] = since;
    }

    /** Thirteen-week arrival mix and finish statistics; hours are those logged by the week's end. */
    private static void windowStats(double[] r, Map<String, Integer> col, MemberContext mc, LocalDate w) {
        LocalDate from = w.minusWeeks(Features.WINDOW_13 - 1);
        LocalDate end = w.plusDays(6);
        int n = 0;
        int defect = 0;
        int delivery = 0;
        int support = 0;
        int high = 0;
        int self = 0;
        int manual = 0;
        int project = 0;
        int finished = 0;
        int reopened = 0;
        double actual = 0;
        double estimate = 0;
        List<Integer> cycles = new ArrayList<>();
        for (TaskFacts t : mc.tasks) {
            LocalDate aw = t.assignedWeek();
            if (!aw.isBefore(from) && !aw.isAfter(w)) {
                n++;
                if (t.family() == Family.DEFECT) {
                    defect++;
                } else if (t.family() == Family.DELIVERY) {
                    delivery++;
                } else if (t.family() == Family.SUPPORT) {
                    support++;
                }
                String p = t.task().priority();
                if ("HIGHEST".equals(p) || "HIGH".equals(p)) {
                    high++;
                }
                if (t.mode() == Mode.SELF_PICKED) {
                    self++;
                } else if (t.mode() == Mode.MANUAL) {
                    manual++;
                } else {
                    project++;
                }
            }
            if (t.finished() != null) {
                LocalDate fd = t.finished().toLocalDate();
                if (!fd.isBefore(from) && !fd.isAfter(end)) {
                    finished++;
                    if (t.task().reopened()) {
                        reopened++;
                    }
                    double hours = t.unlogged() ? t.actualHours() : mc.loggedOnTaskBy(t, end);
                    if (t.estimate() > 0 && hours > 0) {
                        actual += hours;
                        estimate += t.estimate();
                    }
                    Integer c = t.cycleDays();
                    if (c != null) {
                        cycles.add(c);
                    }
                }
            }
        }
        r[col.get("arrivals_13w")] = n;
        r[col.get("share_defect_13w")] = n == 0 ? 0.0 : (double) defect / n;
        r[col.get("share_delivery_13w")] = n == 0 ? 0.0 : (double) delivery / n;
        r[col.get("share_support_13w")] = n == 0 ? 0.0 : (double) support / n;
        r[col.get("share_high_priority_13w")] = n == 0 ? 0.0 : (double) high / n;
        r[col.get("share_self_picked_13w")] = n == 0 ? 1.0 / 3 : (double) self / n;
        r[col.get("share_manual_13w")] = n == 0 ? 1.0 / 3 : (double) manual / n;
        r[col.get("share_project_13w")] = n == 0 ? 1.0 / 3 : (double) project / n;
        r[col.get("reopen_rate_13w")] = finished == 0 ? 0.0 : (double) reopened / finished;
        r[col.get("estimate_ratio_13w")] = estimate == 0 ? 1.0 : actual / estimate;
        r[col.get("cycle_days_13w")] = cycles.isEmpty() ? Double.NaN : median(cycles);
    }

    static double median(List<Integer> values) {
        List<Integer> s = values.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    /** Per-member indexes: the member's tasks and their time logs, by week and by task. */
    static final class MemberContext {
        final List<TaskFacts> tasks;
        private final Map<LocalDate, Double> loggedByWeek = new HashMap<>();
        private final Map<UUID, List<TimeLogRow>> logsByTask = new HashMap<>();

        MemberContext(MemberRow m, List<TaskFacts> tasks, ForecastData data) {
            this.tasks = tasks;
            for (TimeLogRow l : data.timeLogs()) {
                if (!l.userId().equals(m.id())) {
                    continue;
                }
                loggedByWeek.merge(Weeks.mondayOf(l.day()), l.hours(), Double::sum);
                logsByTask.computeIfAbsent(l.taskId(), k -> new ArrayList<>()).add(l);
            }
        }

        double loggedInWeek(LocalDate monday) {
            return loggedByWeek.getOrDefault(monday, 0.0);
        }

        /** The member's hours on the task logged on or before the day. */
        double loggedOnTaskBy(TaskFacts t, LocalDate day) {
            double logged = 0;
            for (TimeLogRow l : logsByTask.getOrDefault(t.id(), List.of())) {
                if (!l.day().isAfter(day)) {
                    logged += l.hours();
                }
            }
            return logged;
        }

        /** Estimate minus the hours logged on or before the day, never negative. */
        double remainingAsOf(TaskFacts t, LocalDate day) {
            return Math.max(0.0, t.estimate() - loggedOnTaskBy(t, day));
        }

        double dueHours(LocalDate w, LocalDate target) {
            return Double.NaN;                                                   // Task 6
        }
    }

    // ---- Task 6 fills these in; in Task 5 they leave their columns NaN ----

    private void throughput(double[] r, Map<String, Integer> col, MemberContext mc, LocalDate w) {
    }

    /** Per-team, per-week values shared by every member of the team. */
    static final class TeamContext {
        TeamContext(ForecastData data, Lifecycle lc, List<LocalDate> weeks) {
        }

        void fill(double[] r, Map<String, Integer> col, UUID team, LocalDate w) {
        }
    }
}
```

Add the import `com.workloadhub.forecast.data.rows.TimeLogRow`; drop `Set` and `TreeSet` from the import list above if the compiler warns about them.

- [ ] **Step 4: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=FeatureBuilderTest`
Expected: 6 passed.

- [ ] **Step 5: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/features/FeatureBuilder.java server/forecast-core/src/test/java/com/workloadhub/forecast/features/FeatureBuilderTest.java server/forecast-core/src/main/java/com/workloadhub/forecast/data/rows
git commit -m "feat(server): feature matrix rows from the fresh series, the arrival mix and availability

Lags, rolling statistics, the thirteen-week shares, availability of the
target week, identity codes and the horizon targets; the throughput and
team columns follow."
```

---

### Task 6: `FeatureBuilder`, part 2: throughput, team backlog, due hours, and the leakage guard

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/features/FeatureBuilder.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/lifecycle/Truncation.java`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/features/FeatureBuilderTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/lifecycle/TruncationTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/features/FeatureLeakageTest.java`

**Interfaces:**
- Column rules (`end` = `w + 6 days`, "remaining as of `end`" = `max(0, estimate − Σ assignee logs on or before end)`; the application's `remaining_estimate_hrs` is a present-day value and is used by the effort placement, never by a historical row):
  - `logged_hours_lag{k}` = the member's `time_logs.hours` on any task in week `w − (k − 1)`.
  - `open_tasks` = the member's tasks with `openAtEndOf(end)`; `open_remaining_hrs` = their remaining as of `end`; `overdue_open` = those with `due_date ≤ end`; `in_progress_tasks` = those with `inProgressAtEndOf(end)`.
  - `due_hrs_h{h}` = remaining as of `end` of the member's open tasks whose `due_date` falls in the target week `[w + h, w + h + 6]`.
  - `team_backlog_unassigned_hrs` = Σ estimate of the tasks of the team's projects (`projectIdsOfTeamAndParent`) created on or before `end`, not finished by `end`, and not assigned by `end` (never assigned, or assigned after `end`).
  - `proj_active` = the team's projects with status `ACTIVE` and at least one task open or in the backlog at `end`; `proj_planning` = the team's projects with status `PLANNING`; `proj_first_due_weeks` = min over the team's projects of `(due − w) / 7` over their tasks open at `end` with `due ≥ w`, 52 with none. Project status is the present-day value (no history), accepted approximation.
- `Truncation.at(ForecastData data, LocalDate cutoff)` returns a `ForecastData` as the database would have looked at the end of `cutoff`: tasks created after `cutoff` dropped; transitions and logs after `cutoff` dropped; for each kept task, the assignee at `cutoff` (the last assignee transition on or before `cutoff` resolved with the lifecycle resolver; without such a row the current assignee when its assignment date is on or before `cutoff`, else null; an unresolvable value keeps the current assignee when assigned on or before cutoff), `started`/`finished` columns cleared when after `cutoff`, `statusCategory` recomputed (`DONE` only when finished on or before cutoff, else `IN_PROGRESS` when started on or before cutoff, else `TO_DO`), `remaining` = remaining as of `cutoff` for tasks not done at cutoff. Members, teams, projects, capacity, absences, holidays and users are kept. This is also the replay the evaluation harness of the next plan uses.

- [ ] **Step 1: Write the failing tests**

Append to `FeatureBuilderTest`:

```java
    @Test
    void throughputCountsOpenWorkAndLogsAtTheEndOfTheWeek() {
        LocalDate w2 = ORIGIN.minusWeeks(2);
        TaskRow done = TestData.task("1", ANA.id(), ORIGIN.minusWeeks(4).atTime(9, 0), 8)
                .withStatus("DONE").withFinished(w2.atTime(17, 0)).withRemaining(0.0);
        TaskRow running = TestData.task("2", ANA.id(), ORIGIN.minusWeeks(3).atTime(9, 0), 10)
                .withStatus("IN_PROGRESS").withStarted(w2.atTime(10, 0)).withDue(w2.plusDays(3)).withRemaining(1.0);
        TaskRow queued = TestData.task("3", ANA.id(), w2.atTime(9, 0), 6).withDue(ORIGIN.plusWeeks(1).plusDays(2));
        ForecastData data = TestData.data(List.of(ANA), List.of(done, running, queued), List.of(), List.of(
                TestData.log(done.id(), ANA.id(), ORIGIN.minusWeeks(3), 5),
                TestData.log(done.id(), ANA.id(), w2, 3),
                TestData.log(running.id(), ANA.id(), w2.plusDays(1), 4),
                TestData.log(running.id(), ANA.id(), ORIGIN, 2)));
        FeatureMatrix m = matrix(data);
        int atW2 = row(m, w2);
        assertEquals(7.0, m.get(atW2, "logged_hours_lag1"));
        assertEquals(5.0, m.get(atW2, "logged_hours_lag2"));
        assertEquals(0.0, m.get(atW2, "logged_hours_lag3"));
        assertEquals(2.0, m.get(atW2, "open_tasks"), "running and queued; done finished this week");
        assertEquals(6.0 + 6.0, m.get(atW2, "open_remaining_hrs"), "10 − 4 logged, plus 6 untouched");
        assertEquals(1.0, m.get(atW2, "overdue_open"), "running is due inside the week");
        assertEquals(1.0, m.get(atW2, "in_progress_tasks"));
        int origin = row(m, ORIGIN);
        assertEquals(10.0, m.get(origin, "open_remaining_hrs"), 1e-9, "running 10 − 6 logged by the origin, plus queued 6");
        assertEquals(6.0, m.get(origin, "due_hrs_h1"), "queued is due in the first forecast week");
        assertEquals(0.0, m.get(origin, "due_hrs_h2"));
        assertEquals(1.0, m.get(origin, "estimate_ratio_13w"), 1e-9, "8 h logged on an 8 h estimate");
        assertEquals(15.0, m.get(origin, "cycle_days_13w"), "assigned −4 w, finished −2 w: 14 days + 1");
    }

    @Test
    void teamColumnsSeeTheBacklogAndTheProjects() {
        MemberRow ben = TestData.member("ben", TestData.TEAM).withJoined(ORIGIN.minusWeeks(6));
        UUID active = TestData.id("proj-active");
        UUID planning = TestData.id("proj-planning");
        List<ProjectRow> projects = List.of(
                new ProjectRow(active, "ACT", "Active", "ACTIVE", TestData.TEAM),
                new ProjectRow(planning, "PLN", "Planning", "PLANNING", TestData.PARENT_TEAM));
        LocalDate w1 = ORIGIN.minusWeeks(1);
        TaskRow backlog = TestData.task("1", null, w1.atTime(9, 0), 9).withProject(active);
        TaskRow assignedLater = TestData.task("2", ANA.id(), ORIGIN.minusWeeks(3).atTime(9, 0), 5).withProject(active).withDue(ORIGIN.plusWeeks(2));
        TaskRow bens = TestData.task("3", ben.id(), w1.atTime(9, 0), 7).withProject(active).withDue(ORIGIN.plusDays(3));
        ForecastData data = TestData.data(List.of(ANA, ben), List.of(backlog, assignedLater, bens),
                List.of(TestData.assignee(assignedLater.id(), ANA.fullName(), w1.atTime(12, 0))), List.of()).withProjects(projects);
        FeatureMatrix m = matrix(data);
        int atW3 = row(m, ORIGIN.minusWeeks(3));
        assertEquals(5.0, m.get(atW3, "team_backlog_unassigned_hrs"), "task 2 waited in the backlog until week −1");
        assertEquals(1.0, m.get(atW3, "proj_active"));
        assertEquals(1.0, m.get(atW3, "proj_planning"), "the parent team's project counts");
        assertEquals(52.0, m.get(atW3, "proj_first_due_weeks"), "nothing open with a due date yet");
        int atW1 = row(m, w1);
        assertEquals(9.0, m.get(atW1, "team_backlog_unassigned_hrs"));
        assertEquals(10.0 / 7, m.get(atW1, "proj_first_due_weeks"), 1e-9, "Ben's task is due 10 days after week −1");
        assertEquals(m.get(atW1, "team_backlog_unassigned_hrs"), m.get(m.keys().indexOf(new MemberWeek(ben.id(), w1)), "team_backlog_unassigned_hrs"));
    }
```

`TestData.data(...)` gains `ForecastData withProjects(List<ProjectRow>)` (a copy on the record) and `TaskRow.withDue`, `withProject` copies.

`TruncationTest`:

```java
package com.workloadhub.forecast.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TruncationTest {

    static final MemberRow ANA = TestData.member("ana", TestData.TEAM);
    static final MemberRow BEN = TestData.member("ben", TestData.TEAM);
    static final LocalDate CUT = LocalDate.of(2026, 8, 9);
    static final LocalDateTime BEFORE = CUT.minusDays(5).atTime(9, 0);

    @Test
    void dropsWhatDidNotExistAndRewindsStatusAssigneeAndRemaining() {
        TaskRow future = TestData.task("1", ANA.id(), CUT.plusDays(1).atTime(9, 0), 8);
        TaskRow finishedLater = TestData.task("2", ANA.id(), BEFORE, 10).withStatus("DONE")
                .withStarted(CUT.minusDays(2).atTime(9, 0)).withFinished(CUT.plusDays(3).atTime(9, 0)).withRemaining(0.0);
        TaskRow assignedLater = TestData.task("3", ANA.id(), BEFORE, 6);
        TaskRow reassigned = TestData.task("4", BEN.id(), BEFORE, 4);
        ForecastData data = TestData.data(List.of(ANA, BEN), List.of(future, finishedLater, assignedLater, reassigned), List.of(
                TestData.assignee(assignedLater.id(), ANA.fullName(), CUT.plusDays(2).atTime(9, 0)),
                TestData.assignee(reassigned.id(), ANA.fullName(), BEFORE.plusDays(1)),
                TestData.assignee(reassigned.id(), BEN.fullName(), CUT.plusDays(4).atTime(9, 0)),
                TestData.status(finishedLater.id(), "Done", CUT.plusDays(3).atTime(9, 0))), List.of(
                TestData.log(finishedLater.id(), ANA.id(), CUT.minusDays(1), 3),
                TestData.log(finishedLater.id(), ANA.id(), CUT.plusDays(1), 7)));
        ForecastData cut = Truncation.at(data, CUT);
        assertEquals(3, cut.tasks().size());
        TaskRow t2 = cut.taskById().get(finishedLater.id());
        assertEquals("IN_PROGRESS", t2.statusCategory());
        assertNull(t2.finishedDate());
        assertEquals(7.0, t2.remaining(), 1e-9, "10 − 3 logged by the cutoff");
        assertNull(cut.taskById().get(assignedLater.id()).assigneeId());
        assertEquals(ANA.id(), cut.taskById().get(reassigned.id()).assigneeId(), "Ana still held it at the cutoff");
        assertEquals(1, cut.timeLogs().size());
        assertTrue(cut.transitions().stream().allMatch(t -> !t.changedAt().toLocalDate().isAfter(CUT)));
        Lifecycle lc = Lifecycle.derive(cut);
        assertEquals(BEFORE.plusDays(1), lc.of(reassigned.id()).assigned());
        assertNull(lc.of(finishedLater.id()).finished());
    }

    @Test
    void seededTruncationKeepsOnlyWhatWasVisible() {
        ForecastData data = SeededData.data();
        LocalDate cut = SeededData.asOf().minusWeeks(8);
        ForecastData t = Truncation.at(data, cut);
        assertTrue(t.tasks().size() < data.tasks().size());
        assertEquals(data.members().size(), t.members().size());
        for (TaskRow task : t.tasks()) {
            assertTrue(!task.createdDate().toLocalDate().isAfter(cut));
            if (task.finishedDate() != null) {
                assertTrue(!task.finishedDate().toLocalDate().isAfter(cut));
            }
        }
        Lifecycle lc = Lifecycle.derive(t);
        assertTrue(lc.all().stream().filter(TaskFacts::isAssigned).allMatch(f -> !f.assigned().toLocalDate().isAfter(cut)));
    }
}
```

`FeatureLeakageTest`:

```java
package com.workloadhub.forecast.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.Truncation;
import com.workloadhub.forecast.testing.SeededData;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every non-target column of a row at week w must be computable from the database as it stood at the end of w. */
class FeatureLeakageTest {

    static FeatureMatrix build(ForecastData data, LocalDate origin) {
        return new FeatureBuilder(data, Lifecycle.derive(data), WorkingCalendar.fromHolidays(data.holidays()), new CapacityRule(40))
                .build(data.members(), origin);
    }

    @Test
    void rowsAtAPastWeekMatchTheRowsBuiltFromTheTruncatedDatabase() {
        ForecastData full = SeededData.data();
        LocalDate origin = Weeks.lastCompleteWeek(SeededData.asOf());
        LocalDate past = origin.minusWeeks(4);
        FeatureMatrix fromFull = build(full, origin).filter(k -> k.week().equals(past));
        FeatureMatrix fromCut = build(Truncation.at(full, past.plusDays(6)), past).filter(k -> k.week().equals(past));
        assertEquals(fromFull.keys(), fromCut.keys());
        List<String> columns = Features.allColumns().stream().filter(c -> !c.startsWith("target_h")).toList();
        int compared = 0;
        for (int i = 0; i < fromFull.rowCount(); i++) {
            for (String c : columns) {
                double a = fromFull.get(i, c);
                double b = fromCut.get(i, c);
                assertTrue(Double.isNaN(a) == Double.isNaN(b) && (Double.isNaN(a) || Math.abs(a - b) < 1e-9),
                        c + " at " + fromFull.key(i) + ": " + a + " vs " + b);
                compared++;
            }
        }
        assertTrue(compared > 0);
    }

    @Test
    void everyFeatureColumnIsPopulatedOnTheSeed() {
        ForecastData data = SeededData.data();
        FeatureMatrix m = build(data, Weeks.lastCompleteWeek(SeededData.asOf()));
        for (int h : Features.HORIZONS) {
            List<String> cols = Features.featureColumns(h);
            assertEquals(cols, m.nonEmptyColumns(cols), "all-NaN columns at horizon " + h);
        }
    }
}
```

Truncation cannot rewind the present-day project status, the `reopened_from_done` flag, or the code books; the leakage test tolerates none of these because they are identical on both sides by construction (kept as-is), which is exactly the accepted approximation: they are stable, not leaked.

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='FeatureBuilderTest,TruncationTest,FeatureLeakageTest'`
Expected: compilation errors (`Truncation`, `withProjects`), then NaN assertions.

- [ ] **Step 3: Fill in the Task 6 methods of `FeatureBuilder`**

Replace the `throughput` stub, `MemberContext.dueHours` and `TeamContext`:

```java
    private void throughput(double[] r, Map<String, Integer> col, MemberContext mc, LocalDate w) {
        for (int k = 1; k <= 4; k++) {
            r[col.get("logged_hours_lag" + k)] = mc.loggedInWeek(w.minusWeeks(k - 1));
        }
        LocalDate end = w.plusDays(6);
        int open = 0;
        int overdue = 0;
        int running = 0;
        double remaining = 0;
        for (TaskFacts t : mc.tasks) {
            if (!t.openAtEndOf(end)) {
                continue;
            }
            open++;
            remaining += mc.remainingAsOf(t, end);
            if (t.task().dueDate() != null && !t.task().dueDate().isAfter(end)) {
                overdue++;
            }
            if (t.inProgressAtEndOf(end)) {
                running++;
            }
        }
        r[col.get("open_tasks")] = open;
        r[col.get("open_remaining_hrs")] = remaining;
        r[col.get("overdue_open")] = overdue;
        r[col.get("in_progress_tasks")] = running;
    }

    // in MemberContext, replacing the stub:
        double dueHours(LocalDate w, LocalDate target) {
            LocalDate end = w.plusDays(6);
            LocalDate targetEnd = target.plusDays(6);
            double sum = 0;
            for (TaskFacts t : tasks) {
                LocalDate due = t.task().dueDate();
                if (due != null && t.openAtEndOf(end) && !due.isBefore(target) && !due.isAfter(targetEnd)) {
                    sum += remainingAsOf(t, end);
                }
            }
            return sum;
        }

    /** Per-team, per-week values shared by every member of the team. */
    static final class TeamContext {
        private final ForecastData data;
        private final Lifecycle lc;
        private final Map<UUID, Map<LocalDate, double[]>> cache = new HashMap<>();
        private final Map<UUID, List<TaskFacts>> tasksByProject = new HashMap<>();

        TeamContext(ForecastData data, Lifecycle lc, List<LocalDate> weeks) {
            this.data = data;
            this.lc = lc;
            for (TaskFacts f : lc.all()) {
                if (f.task().projectId() != null) {
                    tasksByProject.computeIfAbsent(f.task().projectId(), k -> new ArrayList<>()).add(f);
                }
            }
        }

        void fill(double[] r, Map<String, Integer> col, UUID team, LocalDate w) {
            double[] v = cache.computeIfAbsent(team, k -> new HashMap<>()).computeIfAbsent(w, k -> compute(team, w));
            r[col.get("team_backlog_unassigned_hrs")] = v[0];
            r[col.get("proj_active")] = v[1];
            r[col.get("proj_planning")] = v[2];
            r[col.get("proj_first_due_weeks")] = v[3];
        }

        private double[] compute(UUID team, LocalDate w) {
            LocalDate end = w.plusDays(6);
            double backlog = 0;
            int active = 0;
            int planning = 0;
            double firstDue = NEVER_WEEKS;
            Map<UUID, ProjectRow> projects = data.projectById();
            for (UUID pid : data.projectIdsOfTeamAndParent(team)) {
                ProjectRow p = projects.get(pid);
                boolean hasWork = false;
                double projectDue = Double.NaN;
                for (TaskFacts t : tasksByProject.getOrDefault(pid, List.of())) {
                    if (t.task().createdDate().toLocalDate().isAfter(end)) {
                        continue;
                    }
                    boolean finishedByEnd = t.finished() != null && !t.finished().toLocalDate().isAfter(end);
                    boolean assignedByEnd = t.isAssigned() && !t.assigned().toLocalDate().isAfter(end);
                    if (!finishedByEnd && !assignedByEnd) {
                        backlog += t.estimate();
                        hasWork = true;
                    }
                    if (t.openAtEndOf(end)) {
                        hasWork = true;
                        LocalDate due = t.task().dueDate();
                        if (due != null && !due.isBefore(w)) {
                            double weeks = java.time.temporal.ChronoUnit.DAYS.between(w, due) / 7.0;
                            projectDue = Double.isNaN(projectDue) ? weeks : Math.min(projectDue, weeks);
                        }
                    }
                }
                if ("ACTIVE".equals(p.status()) && hasWork) {
                    active++;
                }
                if ("PLANNING".equals(p.status())) {
                    planning++;
                }
                if (!Double.isNaN(projectDue)) {
                    firstDue = Math.min(firstDue, projectDue);
                }
            }
            return new double[] {backlog, active, planning, firstDue};
        }
    }
```

Add the import `com.workloadhub.forecast.data.rows.ProjectRow`. The Task 6 additions to `FeatureBuilderTest` need `com.workloadhub.forecast.data.rows.ProjectRow` and `java.util.UUID` imported too.

- [ ] **Step 4: Write `Truncation`**

```java
package com.workloadhub.forecast.lifecycle;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.data.rows.TransitionRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The database as it stood at the end of a day: the replay behind the leakage test and the evaluation harness. */
public final class Truncation {

    private Truncation() {
    }

    public static ForecastData at(ForecastData data, LocalDate cutoff) {
        Lifecycle full = Lifecycle.derive(data);
        Lifecycle.Resolver resolver = new Lifecycle.Resolver(data);
        Map<UUID, List<TransitionRow>> history = data.transitionsByTask();
        Map<UUID, List<TimeLogRow>> logs = data.logsByTask();
        List<TransitionRow> keptTransitions = data.transitions().stream().filter(t -> !after(t.changedAt(), cutoff)).toList();
        List<TimeLogRow> keptLogs = data.timeLogs().stream().filter(l -> !l.day().isAfter(cutoff)).toList();
        List<TaskRow> tasks = new ArrayList<>();
        for (TaskRow t : data.tasks()) {
            if (after(t.createdDate(), cutoff)) {
                continue;
            }
            TaskFacts f = full.of(t.id());
            UUID assignee = assigneeAt(t, f, history.getOrDefault(t.id(), List.of()), resolver, cutoff);
            LocalDateTime started = after(t.startedDate(), cutoff) ? null : t.startedDate();
            LocalDateTime finished = f.finished() != null && !after(f.finished(), cutoff) ? f.finished() : null;
            String status = finished != null ? "DONE" : (started != null || (f.started() != null && !after(f.started(), cutoff)) ? "IN_PROGRESS" : "TO_DO");
            Double remaining = t.remaining();
            if (finished == null) {
                double logged = 0;
                for (TimeLogRow l : logs.getOrDefault(t.id(), List.of())) {
                    if (assignee != null && assignee.equals(l.userId()) && !l.day().isAfter(cutoff)) {
                        logged += l.hours();
                    }
                }
                remaining = t.estimate() == null ? null : Math.max(0.0, t.estimate() - logged);
            }
            tasks.add(new TaskRow(t.id(), t.key(), t.title(), t.projectId(), assignee, t.reporterId(), t.parentId(), t.typeName(),
                    status, t.priority(), t.estimate(), remaining, t.createdDate(), started, finished, t.dueDate(), t.reopened(), t.archived()));
        }
        return new ForecastData(data.members(), data.teams(), data.projects(), tasks, keptTransitions, keptLogs, data.capacity(),
                data.absences(), data.holidays(), data.users(), data.statusCategoryByName());
    }

    private static boolean after(LocalDateTime t, LocalDate cutoff) {
        return t != null && t.toLocalDate().isAfter(cutoff);
    }

    private static UUID assigneeAt(TaskRow t, TaskFacts f, List<TransitionRow> history, Lifecycle.Resolver resolver, LocalDate cutoff) {
        TransitionRow last = null;
        for (TransitionRow row : history) {
            if ("assignee".equals(row.field()) && !after(row.changedAt(), cutoff)) {
                last = row;
            }
        }
        boolean currentAssignedByCutoff = f.isAssigned() && !after(f.assigned(), cutoff);
        if (last == null) {
            return currentAssignedByCutoff ? t.assigneeId() : null;
        }
        String value = last.newValue();
        if (value == null || value.isBlank() || value.equalsIgnoreCase("none") || value.equalsIgnoreCase("null")) {
            return null;
        }
        UUID resolved = resolver.resolve(value, t.assigneeId());
        return resolved != null ? resolved : (currentAssignedByCutoff ? t.assigneeId() : null);
    }
}
```

`Lifecycle.Resolver` and its constructor become package-private (they are), which is why `Truncation` lives in `lifecycle`. The status for a task open at the cutoff uses the lifecycle's derived `started` (column or history), so a task started only in the history is `IN_PROGRESS` too.

- [ ] **Step 5: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='FeatureBuilderTest,TruncationTest,FeatureLeakageTest,LifecycleTest'`
Expected: all pass. If the leakage test reports a difference, the column named in the message reads something dated after the row's week; fix the builder, never relax the test.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/features/FeatureBuilder.java server/forecast-core/src/main/java/com/workloadhub/forecast/lifecycle/Truncation.java server/forecast-core/src/test/java/com/workloadhub/forecast/features server/forecast-core/src/test/java/com/workloadhub/forecast/lifecycle/TruncationTest.java server/forecast-core/src/test/java/com/workloadhub/forecast/testing/TestData.java server/forecast-core/src/main/java/com/workloadhub/forecast/data
git commit -m "feat(server): throughput, team backlog and due-hour features with a leakage guard

Rows describe open work and logged hours as of their week's end; a
truncated replay of the database proves no column reads the future."
```

---

### Task 7: Arrival models: the seasonal-naive floor and XGBoost

**Files:**
- Modify: `server/forecast-core/pom.xml` (add `ml.dmlc:xgboost4j_2.12`, version managed by the parent)
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/model/ArrivalModel.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/model/ModelUnavailable.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/model/SeasonalNaive.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/model/XgboostArrival.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/testing/SyntheticMatrix.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/model/SeasonalNaiveTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/model/XgboostArrivalTest.java`

**Interfaces:**
- `interface ArrivalModel { String name(); ArrivalModel fit(FeatureMatrix train, int[] horizons); double[] predict(FeatureMatrix rows, int horizon); }`; predictions are clipped at 0; `predict` before `fit` or for an unfitted horizon throws `IllegalStateException`.
- `class ModelUnavailable extends RuntimeException` (message = the reason recorded in the backtest).
- `SeasonalNaive` (`name() = "seasonal_naive"`): `fit` stores `fresh_hours` per `MemberWeek` of the training rows; `predict(rows, h)` returns, per row, the stored value at `(member, week + h − 52 weeks)` when present, else the row's `roll_mean_4`, floored at 0. This is the Python floor (`target − 364 days`); the spec's sentence "lag13 else roll_mean_4" is corrected in the closing notes.
- `XgboostArrival` (`name() = "xgboost"`): constructor `XgboostArrival()` (categorical support probed once per JVM) and `XgboostArrival(boolean categorical)` for tests; `fit` trains one booster per horizon on `train.rowsWithKnown(target(h))` over `train.nonEmptyColumns(featureColumns(h))` with the parameters of the Global Constraints plus `max_cat_to_onehot = 4` when categorical; throws `ModelUnavailable` when a horizon has no training row or XGBoost fails to load its native library; `predict` builds a `DMatrix` over the same columns and clips; `static boolean categoricalSupported()` probes by training two rounds on a four-row matrix with a `"c"` feature type and caches the answer; `List<String> columnsUsed(int h)` for the facts; `close()` disposes the boosters (`AutoCloseable`).
- Test helper `SyntheticMatrix.arrivals(int members, int weeks, long seed)`: a matrix with `Features.allColumns()` where each member has a level `L_m ∈ [2, 30]`, `fresh_hours[w] = max(0, L_m + 6·sin(2πw/9) + noise)` (a nine-week season is out of phase with the 52-week floor, so the floor is beatable), targets at `w + h`, lags and rolling means computed exactly as `FeatureBuilder` does, identity codes filled, every other column NaN. Deterministic per seed.

- [ ] **Step 1: Write the failing tests**

```java
package com.workloadhub.forecast.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.Features;
import com.workloadhub.forecast.testing.SyntheticMatrix;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SeasonalNaiveTest {

    @Test
    void usesLastYearsWeekWhenKnownElseTheFourWeekMean() {
        FeatureMatrix m = SyntheticMatrix.arrivals(2, 60, 1);
        LocalDate origin = m.key(m.rowCount() - 1).week();
        FeatureMatrix train = m.filter(k -> !k.week().isAfter(origin.minusWeeks(3)));
        FeatureMatrix test = m.filter(k -> k.week().equals(origin));
        SeasonalNaive naive = new SeasonalNaive();
        naive.fit(train, Features.HORIZONS);
        double[] p1 = naive.predict(test, 1);
        assertEquals(test.rowCount(), p1.length);
        for (int i = 0; i < test.rowCount(); i++) {
            LocalDate lastYear = origin.plusWeeks(1).minusWeeks(52);
            int j = m.keys().indexOf(new com.workloadhub.forecast.features.MemberWeek(test.key(i).member(), lastYear));
            assertEquals(m.get(j, Features.FRESH), p1[i], 1e-9, "same week last year");
        }
        FeatureMatrix shortTrain = m.filter(k -> k.week().isAfter(origin.minusWeeks(20)) && !k.week().isAfter(origin.minusWeeks(3)));
        SeasonalNaive recent = new SeasonalNaive();
        recent.fit(shortTrain, Features.HORIZONS);
        assertArrayEquals(test.column("roll_mean_4"), recent.predict(test, 2), 1e-9);
        assertEquals("seasonal_naive", naive.name());
        assertThrows(IllegalStateException.class, () -> new SeasonalNaive().predict(test, 1));
    }
}
```

```java
package com.workloadhub.forecast.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.Features;
import com.workloadhub.forecast.testing.SyntheticMatrix;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class XgboostArrivalTest {

    static final FeatureMatrix M = SyntheticMatrix.arrivals(12, 70, 7);
    static final LocalDate ORIGIN = M.key(M.rowCount() - 1).week();
    static final FeatureMatrix TRAIN = M.filter(k -> !k.week().isAfter(ORIGIN.minusWeeks(6)));
    static final FeatureMatrix TEST = M.filter(k -> k.week().equals(ORIGIN.minusWeeks(3)));

    static double mae(double[] y, double[] p) {
        double s = 0;
        for (int i = 0; i < y.length; i++) {
            s += Math.abs(y[i] - p[i]);
        }
        return s / y.length;
    }

    @Test
    void beatsTheFloorOnAPlantedSeasonalSignalAndNeverPredictsBelowZero() {
        try (XgboostArrival xgb = new XgboostArrival()) {
            xgb.fit(TRAIN, Features.HORIZONS);
            SeasonalNaive naive = new SeasonalNaive().fit(TRAIN, Features.HORIZONS);
            for (int h : Features.HORIZONS) {
                double[] y = TEST.target(h);
                double[] p = xgb.predict(TEST, h);
                assertEquals(TEST.rowCount(), p.length);
                for (double v : p) {
                    assertTrue(v >= 0.0);
                }
                double model = mae(y, p);
                double floor = mae(y, naive.predict(TEST, h));
                assertTrue(model < floor, "h" + h + ": xgboost " + model + " vs floor " + floor);
            }
            assertFalse(xgb.columnsUsed(1).contains("open_tasks"), "all-NaN columns are dropped");
            assertTrue(xgb.columnsUsed(1).contains("lag1"));
            assertEquals("xgboost", xgb.name());
        }
    }

    @Test
    void isDeterministicAcrossFits() {
        double[] first;
        try (XgboostArrival a = new XgboostArrival()) {
            first = a.fit(TRAIN, new int[] {1}).predict(TEST, 1);
        }
        try (XgboostArrival b = new XgboostArrival()) {
            assertArrayEquals(first, b.fit(TRAIN, new int[] {1}).predict(TEST, 1), 1e-6);
        }
    }

    @Test
    void numericFallbackTrainsAndPredictsToo() {
        try (XgboostArrival plain = new XgboostArrival(false)) {
            double[] p = plain.fit(TRAIN, new int[] {2}).predict(TEST, 2);
            assertEquals(TEST.rowCount(), p.length);
            assertTrue(mae(TEST.target(2), p) < mae(TEST.target(2), new SeasonalNaive().fit(TRAIN, Features.HORIZONS).predict(TEST, 2)));
        }
    }

    @Test
    void failsClearlyWithoutTrainingRowsOrAnUnfittedHorizon() {
        FeatureMatrix empty = TRAIN.filter(k -> false);
        try (XgboostArrival xgb = new XgboostArrival()) {
            assertThrows(ModelUnavailable.class, () -> xgb.fit(empty, new int[] {1}));
            xgb.fit(TRAIN, new int[] {1});
            assertThrows(IllegalStateException.class, () -> xgb.predict(TEST, 3));
        }
        assertTrue(List.of(true, false).contains(XgboostArrival.categoricalSupported()));
    }
}
```

`SyntheticMatrix` (test scope):

```java
package com.workloadhub.forecast.testing;

import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.Features;
import com.workloadhub.forecast.features.MemberWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** A feature matrix with a planted level-plus-season signal, for model and backtest tests. */
public final class SyntheticMatrix {

    public static final LocalDate FIRST_WEEK = LocalDate.of(2025, 6, 2);

    private SyntheticMatrix() {
    }

    public static FeatureMatrix arrivals(int members, int weeks, long seed) {
        Random rnd = new Random(seed);
        List<String> columns = Features.allColumns();
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            col.put(columns.get(i), i);
        }
        List<String> memberIds = new ArrayList<>();
        for (int m = 0; m < members; m++) {
            memberIds.add(new UUID(0x3000_0000_0000_0000L, m + 1).toString());
        }
        List<MemberWeek> keys = new ArrayList<>();
        List<double[]> rows = new ArrayList<>();
        for (int m = 0; m < members; m++) {
            double level = 2 + rnd.nextDouble() * 28;
            double[] f = new double[weeks];
            for (int w = 0; w < weeks; w++) {
                f[w] = Math.max(0.0, level + 6 * Math.sin(2 * Math.PI * w / 9.0) + rnd.nextGaussian() * 1.5);
            }
            for (int i = 0; i < weeks; i++) {
                double[] r = new double[columns.size()];
                Arrays.fill(r, Double.NaN);
                for (int lag : Features.LAGS) {
                    int j = i - (lag - 1);
                    r[col.get("lag" + lag)] = j >= 0 ? f[j] : Double.NaN;
                }
                for (int win : Features.ROLL_WINDOWS) {
                    int from = Math.max(0, i - win + 1);
                    double sum = 0;
                    for (int j = from; j <= i; j++) {
                        sum += f[j];
                    }
                    r[col.get("roll_mean_" + win)] = sum / (i - from + 1);
                    r[col.get("roll_std_" + win)] = 0.0;
                }
                r[col.get("weeks_since_last_arrival")] = 0.0;
                r[col.get("member_id")] = m;
                r[col.get("team_id")] = m % 3;
                r[col.get("role")] = 0;
                r[col.get("job_title")] = 0;
                r[col.get("tenure_weeks")] = i;
                r[col.get("week_of_year")] = FIRST_WEEK.plusWeeks(i).get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                for (int h : Features.HORIZONS) {
                    r[col.get(Features.target(h))] = i + h < weeks ? f[i + h] : Double.NaN;
                    r[col.get("working_days_h" + h)] = 5;
                }
                r[col.get(Features.FRESH)] = f[i];
                r[col.get(Features.EST)] = f[i];
                keys.add(new MemberWeek(UUID.fromString(memberIds.get(m)), FIRST_WEEK.plusWeeks(i)));
                rows.add(r);
            }
        }
        Map<String, List<String>> books = new HashMap<>();
        books.put("member_id", memberIds);
        books.put("team_id", List.of("t0", "t1", "t2"));
        books.put("role", List.of("MEMBER"));
        books.put("job_title", List.of("(none)"));
        return FeatureMatrix.of(columns, keys, rows.toArray(double[][]::new), books);
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='SeasonalNaiveTest,XgboostArrivalTest'`
Expected: compilation errors.

- [ ] **Step 3: Add the dependency and write the interface, the exception and the floor**

In `forecast-core/pom.xml`, after the Flyway dependencies:

```xml
    <dependency><groupId>ml.dmlc</groupId><artifactId>xgboost4j_2.12</artifactId></dependency>
```

The parent already pins 3.4.0 in `dependencyManagement`; the jar ships the Linux, Windows and macOS native libraries and loads the right one at first use.

```java
package com.workloadhub.forecast.model;

import com.workloadhub.forecast.features.FeatureMatrix;

/** A model of weekly fresh arrival hours per member, one prediction per row for a horizon. */
public interface ArrivalModel {

    String name();

    ArrivalModel fit(FeatureMatrix train, int[] horizons);

    /** Hours per row, never negative. */
    double[] predict(FeatureMatrix rows, int horizon);
}
```

```java
package com.workloadhub.forecast.model;

/** A model cannot run here (missing native library, no training rows); the backtest records the reason. */
public class ModelUnavailable extends RuntimeException {

    public ModelUnavailable(String message) {
        super(message);
    }

    public ModelUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
```

```java
package com.workloadhub.forecast.model;

import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.Features;
import com.workloadhub.forecast.features.MemberWeek;
import java.util.HashMap;
import java.util.Map;

/** Same week last year when known, else the recent four-week mean. The floor every model must beat. */
public final class SeasonalNaive implements ArrivalModel {

    public static final String NAME = "seasonal_naive";
    static final int WEEKS_PER_YEAR = 52;

    private Map<MemberWeek, Double> history;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public SeasonalNaive fit(FeatureMatrix train, int[] horizons) {
        Map<MemberWeek, Double> h = new HashMap<>();
        double[] fresh = train.column(Features.FRESH);
        for (int i = 0; i < train.rowCount(); i++) {
            h.put(train.key(i), fresh[i]);
        }
        history = h;
        return this;
    }

    @Override
    public double[] predict(FeatureMatrix rows, int horizon) {
        if (history == null) {
            throw new IllegalStateException("fit before predict");
        }
        double[] fallback = rows.column("roll_mean_4");
        double[] out = new double[rows.rowCount()];
        for (int i = 0; i < out.length; i++) {
            MemberWeek k = rows.key(i);
            Double v = history.get(new MemberWeek(k.member(), k.week().plusWeeks(horizon).minusWeeks(WEEKS_PER_YEAR)));
            double value = v != null ? v : (Double.isNaN(fallback[i]) ? 0.0 : fallback[i]);
            out[i] = Math.max(0.0, value);
        }
        return out;
    }
}
```

- [ ] **Step 4: Write `XgboostArrival`**

```java
package com.workloadhub.forecast.model;

import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.Features;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;
import ml.dmlc.xgboost4j.java.XGBoostError;

/** One Poisson booster per horizon over the feature columns, single-threaded and seeded. */
public final class XgboostArrival implements ArrivalModel, AutoCloseable {

    public static final String NAME = "xgboost";
    static final int ROUNDS = 300;
    private static volatile Boolean categoricalProbe;

    private final boolean categorical;
    private final Map<Integer, Booster> boosters = new HashMap<>();
    private final Map<Integer, List<String>> columns = new HashMap<>();

    public XgboostArrival() {
        this(categoricalSupported());
    }

    public XgboostArrival(boolean categorical) {
        this.categorical = categorical;
    }

    @Override
    public String name() {
        return NAME;
    }

    static Map<String, Object> params(boolean categorical) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("objective", "count:poisson");
        p.put("tree_method", "hist");
        p.put("max_bin", 255);
        p.put("eta", 0.05);
        p.put("max_leaves", 31);
        p.put("grow_policy", "lossguide");
        p.put("max_depth", 0);
        p.put("min_child_weight", 1);
        p.put("lambda", 1);
        p.put("max_delta_step", 0.7);
        p.put("seed", 0);
        p.put("nthread", 1);
        p.put("verbosity", 0);
        if (categorical) {
            p.put("max_cat_to_onehot", 4);
        }
        return p;
    }

    /** Whether this XGBoost build accepts categorical feature types on a dense DMatrix; probed once. */
    public static boolean categoricalSupported() {
        Boolean known = categoricalProbe;
        if (known != null) {
            return known;
        }
        boolean ok;
        try {
            DMatrix probe = new DMatrix(new float[] {0, 1, 1, 2, 0, 3, 1, 4}, 4, 2, Float.NaN);
            try {
                probe.setFeatureTypes(new String[] {"c", "q"});
                probe.setLabel(new float[] {1, 2, 1, 2});
                XGBoost.train(probe, params(true), 2, Map.of(), null, null).dispose();
                ok = true;
            } finally {
                probe.dispose();
            }
        } catch (XGBoostError | UnsatisfiedLinkError | RuntimeException e) {
            ok = false;
        }
        categoricalProbe = ok;
        return ok;
    }

    @Override
    public XgboostArrival fit(FeatureMatrix train, int[] horizons) {
        for (int h : horizons) {
            FeatureMatrix rows = train.rowsWithKnown(Features.target(h));
            if (rows.rowCount() == 0) {
                throw new ModelUnavailable("no training rows with a known target at horizon " + h);
            }
            List<String> cols = rows.nonEmptyColumns(Features.featureColumns(h));
            if (cols.isEmpty()) {
                throw new ModelUnavailable("no usable feature column at horizon " + h);
            }
            DMatrix dm = null;
            try {
                dm = matrix(rows, cols);
                double[] target = rows.target(h);
                float[] label = new float[target.length];
                for (int i = 0; i < label.length; i++) {
                    label[i] = (float) target[i];
                }
                dm.setLabel(label);
                Booster old = boosters.put(h, XGBoost.train(dm, params(categorical), ROUNDS, Map.of(), null, null));
                if (old != null) {
                    old.dispose();
                }
                columns.put(h, cols);
            } catch (XGBoostError e) {
                throw new ModelUnavailable("xgboost training failed: " + e.getMessage(), e);
            } catch (UnsatisfiedLinkError e) {
                throw new ModelUnavailable("xgboost native library unavailable: " + e.getMessage(), e);
            } finally {
                if (dm != null) {
                    dm.dispose();
                }
            }
        }
        return this;
    }

    @Override
    public double[] predict(FeatureMatrix rows, int horizon) {
        Booster b = boosters.get(horizon);
        if (b == null) {
            throw new IllegalStateException("no booster fitted for horizon " + horizon);
        }
        if (rows.rowCount() == 0) {
            return new double[0];
        }
        DMatrix dm = null;
        try {
            dm = matrix(rows, columns.get(horizon));
            float[][] raw = b.predict(dm);
            double[] out = new double[raw.length];
            for (int i = 0; i < raw.length; i++) {
                out[i] = Math.max(0.0, raw[i][0]);
            }
            return out;
        } catch (XGBoostError e) {
            throw new ModelUnavailable("xgboost prediction failed: " + e.getMessage(), e);
        } finally {
            if (dm != null) {
                dm.dispose();
            }
        }
    }

    private DMatrix matrix(FeatureMatrix rows, List<String> cols) throws XGBoostError {
        DMatrix dm = new DMatrix(rows.flatten(cols), rows.rowCount(), cols.size(), Float.NaN);
        if (categorical) {
            String[] types = new String[cols.size()];
            for (int i = 0; i < types.length; i++) {
                types[i] = Features.isCategorical(cols.get(i)) ? "c" : "q";
            }
            dm.setFeatureTypes(types);
        }
        return dm;
    }

    public List<String> columnsUsed(int horizon) {
        return columns.getOrDefault(horizon, List.of());
    }

    @Override
    public void close() {
        boosters.values().forEach(Booster::dispose);
        boosters.clear();
    }
}
```

Categorical codes must be the same integers at training and prediction time; they are, because both come from one `FeatureMatrix` built once per run. A row whose categorical code was never seen in training is handled by XGBoost as missing.

- [ ] **Step 5: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='SeasonalNaiveTest,XgboostArrivalTest'`
Expected: 5 passed in well under a minute (300 rounds on about 800 rows is fast). If `beatsTheFloorOnAPlantedSeasonalSignal` fails at one horizon, raise `weeks` to 90 in the fixture before touching the parameters: the parameters are the contract with the Python model.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-core/pom.xml server/forecast-core/src/main/java/com/workloadhub/forecast/model server/forecast-core/src/test/java/com/workloadhub/forecast/model server/forecast-core/src/test/java/com/workloadhub/forecast/testing/SyntheticMatrix.java
git commit -m "feat(server): seasonal-naive floor and the XGBoost arrival model

One Poisson booster per horizon with the parameters mirroring the Python
gradient boosting, categorical identity columns when the build allows,
single-threaded and seeded for identical results across runs."
```

---

### Task 8: Rolling backtest, champion and interval bounds

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/backtest/Backtest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/backtest/BacktestTest.java`

**Interfaces:**
- `record Score(String model, LocalDate origin, int horizon, double mae, double mase)`.
- `record Champion(String model, double meanMase)`.
- `record Result(List<Score> scores, Map<String, Map<Integer, double[]>> residuals, Map<String, String> unavailable, Map<String, Double> secondsPerModel)` with `double meanMase(String model)` (NaN when the model has no score) and `Map<String, Double> meanMaseByModel()` (sorted by name).
- `Backtest`: `static List<LocalDate> origins(LocalDate lastCompleteWeek, LocalDate firstWeek)` (`lastCompleteWeek − 2k` weeks for `k = 1..6`, kept when `origin − firstWeek ≥ 13` weeks, ordered from the oldest); `static double mase(double[] y, double[] yHat, double[] yNaive)` (NaN when the naive error is 0); `static Result run(FeatureMatrix feat, Map<String, Supplier<ArrivalModel>> factories, List<LocalDate> origins, int[] horizons)` (per origin: train = rows with `week ≤ origin − max(h)` weeks, test = rows at the origin, both skipped when empty; fit every factory not yet unavailable, catching `ModelUnavailable`; the floor fitted separately for the denominator; per horizon with a fully known target: MAE, MASE, residuals `y − ŷ` appended per model and horizon; models that became unavailable lose all scores, residuals and timings; models implementing `AutoCloseable` are closed after each origin); `static Champion selectChampion(List<Score> scores)` (lowest mean MASE over the scores ignoring NaN; the floor with its own mean, or 1.0 when it has none, when the best is the floor or scores ≥ 1.0 or nothing is scored); `static double[] intervalBounds(double[] residuals)` (10th and 90th percentiles with linear interpolation as NumPy's default; `{0, 0}` when empty); `static final String FLOOR = SeasonalNaive.NAME`.

- [ ] **Step 1: Write the failing tests**

```java
package com.workloadhub.forecast.backtest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.Features;
import com.workloadhub.forecast.model.ArrivalModel;
import com.workloadhub.forecast.model.ModelUnavailable;
import com.workloadhub.forecast.model.SeasonalNaive;
import com.workloadhub.forecast.model.XgboostArrival;
import com.workloadhub.forecast.testing.SyntheticMatrix;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class BacktestTest {

    static final FeatureMatrix M = SyntheticMatrix.arrivals(8, 70, 3);
    static final LocalDate LAST = M.key(M.rowCount() - 1).week();

    @Test
    void originsStepBackTwoWeeksAndNeedThirteenWeeksOfHistory() {
        List<LocalDate> all = Backtest.origins(LAST, LAST.minusWeeks(69));
        assertEquals(6, all.size());
        assertEquals(LAST.minusWeeks(12), all.get(0));
        assertEquals(LAST.minusWeeks(2), all.get(5));
        List<LocalDate> few = Backtest.origins(LAST, LAST.minusWeeks(18));
        assertEquals(List.of(LAST.minusWeeks(4), LAST.minusWeeks(2)), few, "origins 6 and 4 weeks back have under 13 weeks");
        assertTrue(Backtest.origins(LAST, LAST.minusWeeks(10)).isEmpty());
    }

    @Test
    void maseIsTheErrorRatioAgainstTheNaiveAndNaNWhenTheNaiveIsPerfect() {
        assertEquals(0.5, Backtest.mase(new double[] {1, 2, 3}, new double[] {1.5, 2.5, 3.5}, new double[] {2, 3, 4}), 1e-9);
        assertTrue(Double.isNaN(Backtest.mase(new double[] {1, 2}, new double[] {1, 3}, new double[] {1, 2})));
    }

    @Test
    void scoresEveryModelAtEveryOriginAndHorizonAndPoolsResiduals() {
        Map<String, Supplier<ArrivalModel>> factories = new LinkedHashMap<>();
        factories.put(Backtest.FLOOR, SeasonalNaive::new);
        factories.put(XgboostArrival.NAME, XgboostArrival::new);
        List<LocalDate> origins = Backtest.origins(LAST, M.key(0).week()).subList(2, 5);   // targets at h3 stay inside the fixture
        Backtest.Result r = Backtest.run(M, factories, origins, Features.HORIZONS);
        assertEquals(2 * 3 * 3, r.scores().size());
        for (Backtest.Score s : r.scores()) {
            if (s.model().equals(Backtest.FLOOR)) {
                assertEquals(1.0, s.mase(), 1e-9, "the floor scores 1 against itself");
            }
            assertTrue(s.mae() >= 0);
        }
        assertTrue(r.meanMase(XgboostArrival.NAME) < 1.0, "planted signal: " + r.meanMase(XgboostArrival.NAME));
        assertEquals(8 * 3, r.residuals().get(XgboostArrival.NAME).get(1).length, "8 members × 3 origins");
        assertTrue(r.unavailable().isEmpty());
        assertTrue(r.secondsPerModel().get(XgboostArrival.NAME) > 0);
        Backtest.Champion c = Backtest.selectChampion(r.scores());
        assertEquals(XgboostArrival.NAME, c.model());
        assertEquals(r.meanMase(XgboostArrival.NAME), c.meanMase(), 1e-12);
    }

    @Test
    void aModelUnavailableAtAnyOriginLosesEverything() {
        ArrivalModel flaky = new ArrivalModel() {
            int fits;

            public String name() {
                return "flaky";
            }

            public ArrivalModel fit(FeatureMatrix train, int[] horizons) {
                if (++fits == 2) {
                    throw new ModelUnavailable("gone at the second origin");
                }
                return this;
            }

            public double[] predict(FeatureMatrix rows, int h) {
                return rows.column("roll_mean_4");
            }
        };
        Map<String, Supplier<ArrivalModel>> factories = new LinkedHashMap<>();
        factories.put(Backtest.FLOOR, SeasonalNaive::new);
        factories.put("flaky", () -> flaky);
        List<LocalDate> origins = Backtest.origins(LAST, M.key(0).week()).subList(3, 6);
        Backtest.Result r = Backtest.run(M, factories, origins, new int[] {1});
        assertEquals("gone at the second origin", r.unavailable().get("flaky"));
        assertTrue(r.scores().stream().noneMatch(s -> s.model().equals("flaky")));
        assertFalse(r.residuals().containsKey("flaky"));
        assertFalse(r.secondsPerModel().containsKey("flaky"));
        assertEquals(Backtest.FLOOR, Backtest.selectChampion(r.scores()).model());
    }

    @Test
    void championFallsBackToTheFloorWhenNothingBeatsIt() {
        List<Backtest.Score> weak = List.of(
                new Backtest.Score(Backtest.FLOOR, LAST, 1, 1, 1.0),
                new Backtest.Score("x", LAST, 1, 2, 1.2),
                new Backtest.Score("x", LAST, 2, 2, Double.NaN));
        Backtest.Champion c = Backtest.selectChampion(weak);
        assertEquals(Backtest.FLOOR, c.model());
        assertEquals(1.0, c.meanMase());
        assertEquals(Backtest.FLOOR, Backtest.selectChampion(List.of()).model());
        assertTrue(Double.isNaN(Backtest.selectChampion(List.of()).meanMase()));
        assertEquals("x", Backtest.selectChampion(List.of(new Backtest.Score("x", LAST, 1, 1, 0.8))).model());
    }

    @Test
    void intervalBoundsAreTheTenthAndNinetiethPercentilesWithLinearInterpolation() {
        assertArrayEquals(new double[] {0, 0}, Backtest.intervalBounds(new double[0]));
        assertArrayEquals(new double[] {1.9, 9.1}, Backtest.intervalBounds(new double[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}), 1e-9);
        assertArrayEquals(new double[] {5, 5}, Backtest.intervalBounds(new double[] {5}), 1e-9);
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=BacktestTest`
Expected: compilation errors.

- [ ] **Step 3: Write `Backtest`**

```java
package com.workloadhub.forecast.backtest;

import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.model.ArrivalModel;
import com.workloadhub.forecast.model.ModelUnavailable;
import com.workloadhub.forecast.model.SeasonalNaive;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Rolling-origin tournament: every model against the seasonal-naive floor, MASE per origin and horizon. */
public final class Backtest {

    public static final String FLOOR = SeasonalNaive.NAME;
    public static final int ORIGIN_COUNT = 6;
    public static final int ORIGIN_STEP_WEEKS = 2;
    public static final int MIN_HISTORY_WEEKS = 13;
    static final double LOW_QUANTILE = 0.1;
    static final double HIGH_QUANTILE = 0.9;

    public record Score(String model, LocalDate origin, int horizon, double mae, double mase) {
    }

    public record Champion(String model, double meanMase) {
    }

    public record Result(List<Score> scores, Map<String, Map<Integer, double[]>> residuals, Map<String, String> unavailable,
            Map<String, Double> secondsPerModel) {

        public double meanMase(String model) {
            return scores.stream().filter(s -> s.model().equals(model) && !Double.isNaN(s.mase()))
                    .mapToDouble(Score::mase).average().orElse(Double.NaN);
        }

        public Map<String, Double> meanMaseByModel() {
            Map<String, Double> out = new TreeMap<>();
            for (Score s : scores) {
                out.computeIfAbsent(s.model(), this::meanMase);
            }
            return out;
        }
    }

    private Backtest() {
    }

    public static List<LocalDate> origins(LocalDate lastCompleteWeek, LocalDate firstWeek) {
        List<LocalDate> out = new ArrayList<>();
        for (int k = ORIGIN_COUNT; k >= 1; k--) {
            LocalDate origin = lastCompleteWeek.minusWeeks((long) k * ORIGIN_STEP_WEEKS);
            if (ChronoUnit.WEEKS.between(firstWeek, origin) >= MIN_HISTORY_WEEKS) {
                out.add(origin);
            }
        }
        return out;
    }

    public static double mase(double[] y, double[] yHat, double[] yNaive) {
        double num = 0;
        double den = 0;
        for (int i = 0; i < y.length; i++) {
            num += Math.abs(y[i] - yHat[i]);
            den += Math.abs(y[i] - yNaive[i]);
        }
        return den == 0.0 ? Double.NaN : num / den;
    }

    static double mae(double[] y, double[] yHat) {
        double s = 0;
        for (int i = 0; i < y.length; i++) {
            s += Math.abs(y[i] - yHat[i]);
        }
        return y.length == 0 ? Double.NaN : s / y.length;
    }

    public static Result run(FeatureMatrix feat, Map<String, Supplier<ArrivalModel>> factories, List<LocalDate> origins, int[] horizons) {
        int maxH = Arrays.stream(horizons).max().orElse(1);
        List<Score> scores = new ArrayList<>();
        Map<String, Map<Integer, List<Double>>> residuals = new LinkedHashMap<>();
        Map<String, String> unavailable = new LinkedHashMap<>();
        Map<String, Double> seconds = new LinkedHashMap<>();
        for (LocalDate origin : origins) {
            FeatureMatrix train = feat.filter(k -> !k.week().isAfter(origin.minusWeeks(maxH)));
            FeatureMatrix test = feat.filter(k -> k.week().equals(origin));
            if (train.rowCount() == 0 || test.rowCount() == 0) {
                continue;
            }
            Map<String, ArrivalModel> fitted = new LinkedHashMap<>();
            for (Map.Entry<String, Supplier<ArrivalModel>> e : factories.entrySet()) {
                String name = e.getKey();
                if (unavailable.containsKey(name)) {
                    continue;
                }
                long started = System.nanoTime();
                try {
                    fitted.put(name, e.getValue().get().fit(train, horizons));
                } catch (ModelUnavailable ex) {
                    unavailable.put(name, ex.getMessage());
                    continue;
                }
                seconds.merge(name, (System.nanoTime() - started) / 1e9, Double::sum);
            }
            ArrivalModel naive = new SeasonalNaive().fit(train, horizons);
            for (int h : horizons) {
                double[] y = test.target(h);
                if (Arrays.stream(y).anyMatch(Double::isNaN)) {
                    continue;
                }
                double[] yNaive = naive.predict(test, h);
                for (Map.Entry<String, ArrivalModel> e : fitted.entrySet()) {
                    long started = System.nanoTime();
                    double[] yHat = e.getValue().predict(test, h);
                    seconds.merge(e.getKey(), (System.nanoTime() - started) / 1e9, Double::sum);
                    scores.add(new Score(e.getKey(), origin, h, mae(y, yHat), mase(y, yHat, yNaive)));
                    List<Double> pool = residuals.computeIfAbsent(e.getKey(), k -> new TreeMap<>()).computeIfAbsent(h, k -> new ArrayList<>());
                    for (int i = 0; i < y.length; i++) {
                        pool.add(y[i] - yHat[i]);
                    }
                }
            }
            for (ArrivalModel m : fitted.values()) {
                if (m instanceof AutoCloseable c) {
                    try {
                        c.close();
                    } catch (Exception ignored) {
                        // a booster that fails to dispose leaks a little native memory until the JVM exits
                    }
                }
            }
        }
        scores.removeIf(s -> unavailable.containsKey(s.model()));
        unavailable.keySet().forEach(residuals::remove);
        unavailable.keySet().forEach(seconds::remove);
        Map<String, Map<Integer, double[]>> pooled = new LinkedHashMap<>();
        residuals.forEach((model, byH) -> {
            Map<Integer, double[]> arrays = new TreeMap<>();
            byH.forEach((h, list) -> arrays.put(h, list.stream().mapToDouble(Double::doubleValue).toArray()));
            pooled.put(model, arrays);
        });
        return new Result(List.copyOf(scores), pooled, unavailable, seconds);
    }

    public static Champion selectChampion(List<Score> scores) {
        Map<String, List<Double>> byModel = new TreeMap<>();
        for (Score s : scores) {
            if (!Double.isNaN(s.mase())) {
                byModel.computeIfAbsent(s.model(), k -> new ArrayList<>()).add(s.mase());
            }
        }
        if (byModel.isEmpty()) {
            return new Champion(FLOOR, Double.NaN);
        }
        String best = null;
        double bestMean = Double.POSITIVE_INFINITY;
        Map<String, Double> means = new HashMap<>();
        for (Map.Entry<String, List<Double>> e : byModel.entrySet()) {
            double mean = e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            means.put(e.getKey(), mean);
            if (mean < bestMean) {
                best = e.getKey();
                bestMean = mean;
            }
        }
        if (best.equals(FLOOR) || bestMean >= 1.0) {
            return new Champion(FLOOR, means.getOrDefault(FLOOR, 1.0));
        }
        return new Champion(best, bestMean);
    }

    /** NumPy's default (linear) quantiles at 0.1 and 0.9. */
    public static double[] intervalBounds(double[] residuals) {
        if (residuals.length == 0) {
            return new double[] {0.0, 0.0};
        }
        double[] s = residuals.clone();
        Arrays.sort(s);
        return new double[] {quantile(s, LOW_QUANTILE), quantile(s, HIGH_QUANTILE)};
    }

    static double quantile(double[] sorted, double q) {
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(lo + 1, sorted.length - 1);
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
    }
}
```

`TreeMap` iteration in `selectChampion` makes ties deterministic (alphabetical), which matters only in tests. A forced model (spec section 8) is a matter of which factories the caller passes and which champion it uses; that is the next plan's `run` and needs nothing here.

- [ ] **Step 4: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=BacktestTest`
Expected: 6 passed.

- [ ] **Step 5: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/backtest server/forecast-core/src/test/java/com/workloadhub/forecast/backtest
git commit -m "feat(server): rolling backtest with MASE scoring, champion selection and residual bounds

Six origins two weeks apart, the floor as denominator, a model that
fails anywhere loses all its scores, and the floor wins any tie at 1.0."
```

---

### Task 9: Effort model and hour placement

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/model/EffortModel.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/model/EffortModelTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/model/EffortModelPropertyTest.java`

**Interfaces:**
- `EffortModel` with constants `SHRINK_K = 5.0`, `RATIO_MIN = 0.5`, `RATIO_MAX = 2.5`, `DEFAULT_CYCLE_DAYS = 5.0`.
- `static EffortModel fit(Lifecycle lc, ForecastData data)`: over tasks with `finished != null`, `estimate > 0`, `actualHours > 0` and a counted assignee: ratio `actual / estimate`, cycle `cycleDays()`, lateness `latenessDays()` when present; team = the assignee's `primaryTeamId`; levels `(member, family)`, `member`, `(team, family)`, `team`, global (ratio = mean, cycle and lateness = median, as the Python model); global defaults 1.0, 5.0, 0.0 with no data. The Python cycle booster (50+ rows) is not ported: ruling recorded in the closing notes (hierarchy fallback only in v1).
- `double estimateRatio(UUID member, Family family, UUID team)` (`family` null for new arrivals): member-level mean shrunk with `k` toward the `(team, family)` mean, else the team mean, else global; clipped to `[RATIO_MIN, RATIO_MAX]`.
- `double memberCycleDays(UUID member, UUID team)`: member median shrunk toward team median else global, at least 1.
- `double familyCycleDays(UUID member, Family family, UUID team)`: `(member, family)` median shrunk toward `(team, family)` median, else `memberCycleDays`, at least 1.
- `double memberLatenessDays(UUID member, UUID team)`: member median, else team median, else global.
- `static SortedMap<MemberWeek, Double> placeOpenTasks(List<TaskFacts> open, EffortModel model, LocalDate placementStart, Function<UUID, UUID> teamOf, Function<UUID, Set<LocalDate>> offDaysOf, WorkingCalendar cal)`: per task, hours = `remaining_estimate_hrs` when not null, else `max(0, estimate × ratio − actualHours)`; skipped when hours ≤ 0; `start = max(placementStart, assigned day)`; `cycle = round(familyCycleDays)`; `minEnd = start + cycle − 1`; `end = max(minEnd, due + round(lateness))` when the task has a due date, else `max(minEnd, assigned day + cycle)`; `HourPlacement.placeHours(hours, start, end, cal, off)` summed per `MemberWeek`.
- `static SortedMap<MemberWeek, Double> placeNewArrivals(Map<MemberWeek, Double> predictedEst, EffortModel model, Function<UUID, UUID> teamOf, Function<UUID, Set<LocalDate>> offDaysOf, WorkingCalendar cal)`: hours = `est × estimateRatio(member, null, team)`; `span = round(memberCycleDays)`; placed from the week's Monday to `Monday + span − 1`.
- `static double shrink(int n, double mean, double prior, double k)` = `(n·mean + k·prior) / (n + k)`.

- [ ] **Step 1: Write the failing tests**

```java
package com.workloadhub.forecast.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Family;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EffortModelTest {

    static final WorkingCalendar CAL = WorkingCalendar.fromHolidays(List.of());
    static final MemberRow ANA = TestData.member("ana", TestData.TEAM);
    static final MemberRow BEN = TestData.member("ben", TestData.TEAM);
    static final LocalDate MON = LocalDate.of(2026, 8, 3);

    /** Ana: three delivery tasks at ratio 2.0, cycle 3; Ben: one bug at ratio 1.0, cycle 10. */
    static ForecastData history() {
        List<TaskRow> tasks = new ArrayList<>();
        List<com.workloadhub.forecast.data.rows.TimeLogRow> logs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            LocalDateTime created = MON.minusWeeks(6 - i).atTime(9, 0);
            TaskRow t = TestData.task("a" + i, ANA.id(), created, 4).withStatus("DONE")
                    .withFinished(created.plusDays(2)).withDue(created.toLocalDate().plusDays(1)).withRemaining(0.0);
            tasks.add(t);
            logs.add(TestData.log(t.id(), ANA.id(), created.toLocalDate(), 8));
        }
        LocalDateTime created = MON.minusWeeks(4).atTime(9, 0);
        TaskRow bug = TestData.task("b", BEN.id(), created, 6).withType("Bug").withStatus("DONE").withFinished(created.plusDays(9)).withRemaining(0.0);
        tasks.add(bug);
        logs.add(TestData.log(bug.id(), BEN.id(), created.toLocalDate(), 6));
        return TestData.data(List.of(ANA, BEN), tasks, List.of(), logs);
    }

    static EffortModel model() {
        ForecastData data = history();
        return EffortModel.fit(Lifecycle.derive(data), data);
    }

    @Test
    void ratiosShrinkTowardTheTeamAndClip() {
        EffortModel m = model();
        double team = (2.0 * 3 + 1.0) / 4;                     // team mean over four tasks
        double teamDelivery = 2.0;
        double expected = EffortModel.shrink(3, 2.0, teamDelivery, EffortModel.SHRINK_K);
        assertEquals(expected, m.estimateRatio(ANA.id(), Family.DELIVERY, TestData.TEAM), 1e-9);
        assertEquals(EffortModel.shrink(3, 2.0, team, EffortModel.SHRINK_K), m.estimateRatio(ANA.id(), null, TestData.TEAM), 1e-9);
        assertEquals(team, m.estimateRatio(UUID.randomUUID(), null, TestData.TEAM), 1e-9, "unknown member: the team prior");
        assertEquals(team, m.estimateRatio(ANA.id(), Family.SUPPORT, TestData.TEAM), 1e-9,
                "no support history at any level: the team prior itself");
        assertTrue(m.estimateRatio(UUID.randomUUID(), null, UUID.randomUUID()) >= EffortModel.RATIO_MIN);
    }

    @Test
    void cyclesAndLatenessUseMediansWithTheSameShrinkage() {
        EffortModel m = model();
        double teamCycle = 3.0;                                  // median of {3, 3, 3, 10}
        assertEquals(EffortModel.shrink(3, 3.0, teamCycle, EffortModel.SHRINK_K), m.memberCycleDays(ANA.id(), TestData.TEAM), 1e-9);
        assertEquals(EffortModel.shrink(1, 10.0, teamCycle, EffortModel.SHRINK_K), m.memberCycleDays(BEN.id(), TestData.TEAM), 1e-9);
        assertEquals(EffortModel.shrink(1, 10.0, 10.0, EffortModel.SHRINK_K), m.familyCycleDays(BEN.id(), Family.DEFECT, TestData.TEAM), 1e-9,
                "team defect median is Ben's own bug");
        assertEquals(1.0, m.memberLatenessDays(ANA.id(), TestData.TEAM), "finished one day after due");
        assertEquals(1.0, m.memberLatenessDays(BEN.id(), TestData.TEAM), "no due dates: the team median");
        assertEquals(EffortModel.DEFAULT_CYCLE_DAYS, EffortModel.fit(Lifecycle.derive(TestData.data(List.of(ANA), List.of(), List.of(), List.of())),
                TestData.data(List.of(ANA), List.of(), List.of(), List.of())).memberCycleDays(ANA.id(), TestData.TEAM), 1e-9);
    }

    @Test
    void openTasksPlaceTheirRemainingHoursFromTheStartToTheLaterOfCycleAndDue() {
        ForecastData data = history();
        EffortModel m = EffortModel.fit(Lifecycle.derive(data), data);
        TaskRow open = TestData.task("o", ANA.id(), MON.minusDays(3).atTime(9, 0), 10).withRemaining(6.0).withDue(MON.plusDays(8));
        TaskRow noRemaining = TestData.task("p", ANA.id(), MON.atTime(9, 0), 10).withRemaining(null);
        ForecastData withOpen = TestData.data(List.of(ANA, BEN), List.of(open, noRemaining), List.of(), List.of(
                TestData.log(noRemaining.id(), ANA.id(), MON, 3)));
        Lifecycle lc = Lifecycle.derive(withOpen);
        List<TaskFacts> openFacts = List.of(lc.of(open.id()), lc.of(noRemaining.id()));
        SortedMap<MemberWeek, Double> placed = EffortModel.placeOpenTasks(openFacts, m, MON, id -> TestData.TEAM, id -> Set.of(), CAL);
        double total = placed.values().stream().mapToDouble(Double::doubleValue).sum();
        double ratio = m.estimateRatio(ANA.id(), Family.DELIVERY, TestData.TEAM);
        assertEquals(6.0 + Math.max(0, 10 * ratio - 3), total, 1e-9, "remaining column, else estimate × ratio minus logged");
        assertTrue(placed.containsKey(new MemberWeek(ANA.id(), MON)));
        assertTrue(placed.containsKey(new MemberWeek(ANA.id(), MON.plusWeeks(1))), "due in week 2 plus one day of lateness");
        assertTrue(placed.keySet().stream().allMatch(k -> !k.week().isBefore(MON)), "nothing before the placement start");
    }

    @Test
    void newArrivalsAreScaledByTheRatioAndSpreadOverTheCycle() {
        EffortModel m = model();
        SortedMap<MemberWeek, Double> placed = EffortModel.placeNewArrivals(
                Map.of(new MemberWeek(BEN.id(), MON), 10.0), m, id -> TestData.TEAM, id -> Set.of(), CAL);
        double ratio = m.estimateRatio(BEN.id(), null, TestData.TEAM);
        assertEquals(10.0 * ratio, placed.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        int span = (int) Math.round(m.memberCycleDays(BEN.id(), TestData.TEAM));
        assertEquals(span > 5 ? 2 : 1, placed.size(), "a cycle longer than a working week spills into the next");
    }

    @Test
    void seededFitIsWithinTheClipsAndPlacesEveryOpenHour() {
        ForecastData data = SeededData.data();
        Lifecycle lc = Lifecycle.derive(data);
        EffortModel m = EffortModel.fit(lc, data);
        for (MemberRow member : data.members()) {
            double r = m.estimateRatio(member.id(), null, member.primaryTeamId());
            assertTrue(r >= EffortModel.RATIO_MIN && r <= EffortModel.RATIO_MAX);
            assertTrue(m.memberCycleDays(member.id(), member.primaryTeamId()) >= 1.0);
        }
        List<TaskFacts> open = lc.all().stream().filter(f -> f.isAssigned() && !f.done()).toList();
        LocalDate f1 = com.workloadhub.forecast.calendar.Weeks.forecastWeeks(SeededData.asOf())[0];
        Map<UUID, MemberRow> members = data.memberById();
        SortedMap<MemberWeek, Double> placed = EffortModel.placeOpenTasks(
                open.stream().filter(f -> members.containsKey(f.assignee())).toList(), m, f1,
                id -> members.get(id).primaryTeamId(), id -> Set.of(), WorkingCalendar.fromHolidays(data.holidays()));
        double expected = open.stream().filter(f -> members.containsKey(f.assignee()))
                .mapToDouble(f -> f.remaining() != null ? f.remaining() : Math.max(0, f.estimate() * m.estimateRatio(f.assignee(), f.family(), members.get(f.assignee()).primaryTeamId()) - f.actualHours()))
                .sum();
        assertEquals(expected, placed.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-6, "hours are moved, never lost");
    }
}
```

The `Family.SUPPORT` assertion spells out the fallback chain: no `(member, SUPPORT)` rows and no `(team, SUPPORT)` rows, so the prior is the team mean and the member has `n = 0` at that level, giving the team prior itself.

`EffortModelPropertyTest` (jqwik):

```java
package com.workloadhub.forecast.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.features.MemberWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

class EffortModelPropertyTest {

    @Property
    void shrinkStaysBetweenTheMeanAndThePrior(@ForAll @IntRange(min = 0, max = 50) int n, @ForAll @DoubleRange(min = 0.1, max = 5) double mean,
            @ForAll @DoubleRange(min = 0.1, max = 5) double prior) {
        double s = EffortModel.shrink(n, mean, prior, EffortModel.SHRINK_K);
        assertTrue(s >= Math.min(mean, prior) - 1e-12 && s <= Math.max(mean, prior) + 1e-12);
        if (n == 0) {
            assertEquals(prior, s, 1e-12);
        }
    }

    @Property
    void placedNewHoursAreConservedAndLandOnOrAfterTheWeek(@ForAll @DoubleRange(min = 0, max = 80) double est, @ForAll @IntRange(min = 0, max = 200) int weekOffset) {
        LocalDate week = LocalDate.of(2026, 1, 5).plusWeeks(weekOffset);
        UUID member = UUID.fromString("30000000-0000-0000-0000-000000000001");
        EffortModel empty = EffortModel.fit(
                com.workloadhub.forecast.lifecycle.Lifecycle.derive(com.workloadhub.forecast.testing.TestData.data(List.of(), List.of(), List.of(), List.of())),
                com.workloadhub.forecast.testing.TestData.data(List.of(), List.of(), List.of(), List.of()));
        SortedMap<MemberWeek, Double> placed = EffortModel.placeNewArrivals(Map.of(new MemberWeek(member, week), est), empty,
                id -> UUID.randomUUID(), id -> Set.of(), WorkingCalendar.fromHolidays(List.of()));
        double total = placed.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(est * empty.estimateRatio(member, null, null), total, 1e-9);
        assertTrue(placed.keySet().stream().allMatch(k -> !k.week().isBefore(week)));
        assertTrue(placed.values().stream().allMatch(v -> v >= 0));
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='EffortModelTest,EffortModelPropertyTest'`
Expected: compilation errors.

- [ ] **Step 3: Write `EffortModel`**

```java
package com.workloadhub.forecast.model;

import com.workloadhub.forecast.calendar.HourPlacement;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Family;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/** How long tasks take and how estimates deviate, per member with shrinkage toward the team, and where hours land. */
public final class EffortModel {

    public static final double SHRINK_K = 5.0;
    public static final double RATIO_MIN = 0.5;
    public static final double RATIO_MAX = 2.5;
    public static final double DEFAULT_CYCLE_DAYS = 5.0;

    private record Stat(int n, double value) {
    }

    private record MemberFamily(UUID member, Family family) {
    }

    private record TeamFamily(UUID team, Family family) {
    }

    private final Map<MemberFamily, Stat> ratioMemberFamily = new HashMap<>();
    private final Map<UUID, Stat> ratioMember = new HashMap<>();
    private final Map<TeamFamily, Double> ratioTeamFamily = new HashMap<>();
    private final Map<UUID, Double> ratioTeam = new HashMap<>();
    private double ratioGlobal = 1.0;
    private final Map<MemberFamily, Stat> cycleMemberFamily = new HashMap<>();
    private final Map<UUID, Stat> cycleMember = new HashMap<>();
    private final Map<TeamFamily, Double> cycleTeamFamily = new HashMap<>();
    private final Map<UUID, Double> cycleTeam = new HashMap<>();
    private double cycleGlobal = DEFAULT_CYCLE_DAYS;
    private final Map<UUID, Double> latenessMember = new HashMap<>();
    private final Map<UUID, Double> latenessTeam = new HashMap<>();
    private double latenessGlobal = 0.0;

    private EffortModel() {
    }

    public static EffortModel fit(Lifecycle lc, ForecastData data) {
        EffortModel m = new EffortModel();
        Map<UUID, MemberRow> members = data.memberById();
        Map<MemberFamily, List<Double>> rMf = new HashMap<>();
        Map<UUID, List<Double>> rM = new HashMap<>();
        Map<TeamFamily, List<Double>> rTf = new HashMap<>();
        Map<UUID, List<Double>> rT = new HashMap<>();
        List<Double> rG = new ArrayList<>();
        Map<MemberFamily, List<Double>> cMf = new HashMap<>();
        Map<UUID, List<Double>> cM = new HashMap<>();
        Map<TeamFamily, List<Double>> cTf = new HashMap<>();
        Map<UUID, List<Double>> cT = new HashMap<>();
        List<Double> cG = new ArrayList<>();
        Map<UUID, List<Double>> lM = new HashMap<>();
        Map<UUID, List<Double>> lT = new HashMap<>();
        List<Double> lG = new ArrayList<>();
        for (TaskFacts f : lc.all()) {
            MemberRow member = f.assignee() == null ? null : members.get(f.assignee());
            if (member == null || f.finished() == null || f.estimate() <= 0 || f.actualHours() <= 0 || f.cycleDays() == null) {
                continue;
            }
            UUID team = member.primaryTeamId();
            double ratio = f.actualHours() / f.estimate();
            double cycle = f.cycleDays();
            MemberFamily mf = new MemberFamily(member.id(), f.family());
            TeamFamily tf = new TeamFamily(team, f.family());
            rMf.computeIfAbsent(mf, k -> new ArrayList<>()).add(ratio);
            rM.computeIfAbsent(member.id(), k -> new ArrayList<>()).add(ratio);
            rTf.computeIfAbsent(tf, k -> new ArrayList<>()).add(ratio);
            rT.computeIfAbsent(team, k -> new ArrayList<>()).add(ratio);
            rG.add(ratio);
            cMf.computeIfAbsent(mf, k -> new ArrayList<>()).add(cycle);
            cM.computeIfAbsent(member.id(), k -> new ArrayList<>()).add(cycle);
            cTf.computeIfAbsent(tf, k -> new ArrayList<>()).add(cycle);
            cT.computeIfAbsent(team, k -> new ArrayList<>()).add(cycle);
            cG.add(cycle);
            Integer late = f.latenessDays();
            if (late != null) {
                lM.computeIfAbsent(member.id(), k -> new ArrayList<>()).add((double) late);
                lT.computeIfAbsent(team, k -> new ArrayList<>()).add((double) late);
                lG.add((double) late);
            }
        }
        if (!rG.isEmpty()) {
            m.ratioGlobal = mean(rG);
            m.cycleGlobal = median(cG);
        }
        if (!lG.isEmpty()) {
            m.latenessGlobal = median(lG);
        }
        rMf.forEach((k, v) -> m.ratioMemberFamily.put(k, new Stat(v.size(), mean(v))));
        rM.forEach((k, v) -> m.ratioMember.put(k, new Stat(v.size(), mean(v))));
        rTf.forEach((k, v) -> m.ratioTeamFamily.put(k, mean(v)));
        rT.forEach((k, v) -> m.ratioTeam.put(k, mean(v)));
        cMf.forEach((k, v) -> m.cycleMemberFamily.put(k, new Stat(v.size(), median(v))));
        cM.forEach((k, v) -> m.cycleMember.put(k, new Stat(v.size(), median(v))));
        cTf.forEach((k, v) -> m.cycleTeamFamily.put(k, median(v)));
        cT.forEach((k, v) -> m.cycleTeam.put(k, median(v)));
        lM.forEach((k, v) -> m.latenessMember.put(k, median(v)));
        lT.forEach((k, v) -> m.latenessTeam.put(k, median(v)));
        return m;
    }

    public static double shrink(int n, double mean, double prior, double k) {
        return (n * mean + k * prior) / (n + k);
    }

    static double mean(List<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    static double median(List<Double> v) {
        List<Double> s = v.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    public double estimateRatio(UUID member, Family family, UUID team) {
        double teamPrior = ratioTeam.getOrDefault(team, ratioGlobal);
        double prior;
        Stat own;
        if (family != null) {
            prior = ratioTeamFamily.getOrDefault(new TeamFamily(team, family), teamPrior);
            own = ratioMemberFamily.getOrDefault(new MemberFamily(member, family), new Stat(0, prior));
        } else {
            prior = teamPrior;
            own = ratioMember.getOrDefault(member, new Stat(0, prior));
        }
        return Math.clamp(shrink(own.n(), own.value(), prior, SHRINK_K), RATIO_MIN, RATIO_MAX);
    }

    public double memberCycleDays(UUID member, UUID team) {
        double prior = cycleTeam.getOrDefault(team, cycleGlobal);
        Stat own = cycleMember.getOrDefault(member, new Stat(0, prior));
        return Math.max(1.0, shrink(own.n(), own.value(), prior, SHRINK_K));
    }

    public double familyCycleDays(UUID member, Family family, UUID team) {
        double prior = cycleTeamFamily.getOrDefault(new TeamFamily(team, family), memberCycleDays(member, team));
        Stat own = cycleMemberFamily.getOrDefault(new MemberFamily(member, family), new Stat(0, prior));
        return Math.max(1.0, shrink(own.n(), own.value(), prior, SHRINK_K));
    }

    public double memberLatenessDays(UUID member, UUID team) {
        return latenessMember.getOrDefault(member, latenessTeam.getOrDefault(team, latenessGlobal));
    }

    public static SortedMap<MemberWeek, Double> placeOpenTasks(List<TaskFacts> open, EffortModel model, LocalDate placementStart,
            Function<UUID, UUID> teamOf, Function<UUID, Set<LocalDate>> offDaysOf, WorkingCalendar cal) {
        SortedMap<MemberWeek, Double> out = new TreeMap<>();
        for (TaskFacts t : open) {
            UUID member = t.assignee();
            UUID team = teamOf.apply(member);
            double hours = t.remaining() != null ? t.remaining()
                    : Math.max(0.0, t.estimate() * model.estimateRatio(member, t.family(), team) - t.actualHours());
            if (hours <= 0) {
                continue;
            }
            LocalDate assigned = t.assignedDay();
            LocalDate start = assigned.isAfter(placementStart) ? assigned : placementStart;
            int cycle = (int) Math.round(model.familyCycleDays(member, t.family(), team));
            LocalDate minEnd = start.plusDays(Math.max(cycle - 1, 0));
            LocalDate end;
            if (t.task().dueDate() != null) {
                LocalDate late = t.task().dueDate().plusDays(Math.round(model.memberLatenessDays(member, team)));
                end = late.isAfter(minEnd) ? late : minEnd;
            } else {
                LocalDate byCycle = assigned.plusDays(cycle);
                end = byCycle.isAfter(minEnd) ? byCycle : minEnd;
            }
            HourPlacement.placeHours(hours, start, end, cal, offDaysOf.apply(member))
                    .forEach((week, h) -> out.merge(new MemberWeek(member, week), h, Double::sum));
        }
        return out;
    }

    public static SortedMap<MemberWeek, Double> placeNewArrivals(Map<MemberWeek, Double> predictedEst, EffortModel model,
            Function<UUID, UUID> teamOf, Function<UUID, Set<LocalDate>> offDaysOf, WorkingCalendar cal) {
        SortedMap<MemberWeek, Double> out = new TreeMap<>();
        for (Map.Entry<MemberWeek, Double> e : new TreeMap<>(predictedEst).entrySet()) {
            UUID member = e.getKey().member();
            UUID team = teamOf.apply(member);
            double hours = e.getValue() * model.estimateRatio(member, null, team);
            int span = (int) Math.round(model.memberCycleDays(member, team));
            LocalDate start = e.getKey().week();
            LocalDate end = start.plusDays(Math.max(span - 1, 0));
            HourPlacement.placeHours(hours, start, end, cal, offDaysOf.apply(member))
                    .forEach((week, h) -> out.merge(new MemberWeek(member, week), h, Double::sum));
        }
        return out;
    }
}
```

`Math.clamp(double, double, double)` exists since Java 21. The remaining-fraction heuristic of the Python model is gone on purpose: the application knows `remaining_estimate_hrs`, and the fallback for a null column is `estimate × ratio − logged`, which is what the throughput features use too.

- [ ] **Step 4: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='EffortModelTest,EffortModelPropertyTest'`
Expected: 7 passed (jqwik reports each property once).

- [ ] **Step 5: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/model/EffortModel.java server/forecast-core/src/test/java/com/workloadhub/forecast/model/EffortModelTest.java server/forecast-core/src/test/java/com/workloadhub/forecast/model/EffortModelPropertyTest.java
git commit -m "feat(server): effort model with shrunk ratios, cycles, lateness and hour placement

Open tasks place their remaining estimate from the first forecast week
to the later of their cycle and due date; new arrivals are scaled by the
member's ratio and spread over their cycle."
```

---

### Task 10: Planned-work allocation

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/planned/PlannedWork.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/planned/PlannedWorkTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/planned/PlannedWorkPropertyTest.java`

**Interfaces:**
- Constants `WINDOW_WEEKS = 26`, `SHRINK_K = 3.0`, `MIN_PROJECT_TASKS_FOR_LAG = 5`.
- `record Piece(UUID taskId, String key, String title, UUID projectId, Family family, double estimate, UUID member, double share, LocalDate expectedDate, LocalDate expectedWeek, double hoursInWindow, double hoursAfterWindow)`: one allocated (task, member) pair; `expectedWeek` is null when the expected date falls after the second forecast week.
- `record Allocation(SortedMap<MemberWeek, Double> hours, List<Piece> pieces, double hoursAfterWindow, int candidateCount, double candidateHours)` with `static Allocation empty()`.
- `record Request(UUID teamId, List<MemberRow> members, LocalDate asOf, LocalDate[] forecastWeeks)`.
- `static List<TaskFacts> candidates(Lifecycle lc, ForecastData data, UUID teamId, LocalDate asOf)`: tasks with no assignee, not done, `created ≤ asOf − BACKLOG_LAG_DAYS`, whose project is in `projectIdsOfTeamAndParent(teamId)`; sorted by id.
- `static Map<UUID, Double> weights(TaskFacts candidate, List<MemberRow> eligible, List<TaskFacts> history)`: `history` = tasks assigned to the eligible members with an assignment day in `(asOf − 26 weeks, asOf]`; levels (project, family) → (project) → (team, family) → equal split, each shrunk toward the next with `SHRINK_K` as `(n_m + k·prior_m) / (n_total + k)`; keys = every eligible member id; values sum to 1. A level with `n_total = 0` returns its prior unchanged.
- `static long lagDays(TaskFacts candidate, Lifecycle lc, ForecastData data, Set<UUID> teamMembers)`: median `lagDays()` over assigned tasks of the candidate's project when at least `MIN_PROJECT_TASKS_FOR_LAG`, else over assigned tasks of the team's members, else over every assigned task, else `BACKLOG_LAG_DAYS`; rounded half up.
- `static Allocation allocate(Request req, Lifecycle lc, ForecastData data, EffortModel effort, WorkingCalendar cal, Function<UUID, Set<LocalDate>> offDaysOf)`: eligible members = `req.members()` employed on `asOf`; for each candidate: expected date = `created + lag`, floored at `f1`; for each member with weight > 0: allocated estimate = `estimate × weight`; placed like a new arrival from the expected date with the member's `estimateRatio(member, family, team)` and `familyCycleDays` through `HourPlacement.placeHours`; weeks ≤ `f2` count in `hours`, later ones in `hoursAfterWindow`; a piece with `expectedDate > f2 + 6 days` has `expectedWeek = null` and all its hours after the window. Returns `Allocation.empty()` with the candidate counts when there is no eligible member.

- [ ] **Step 1: Write the failing tests**

```java
package com.workloadhub.forecast.planned;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import com.workloadhub.forecast.model.EffortModel;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlannedWorkTest {

    static final WorkingCalendar CAL = WorkingCalendar.fromHolidays(List.of());
    static final LocalDate AS_OF = LocalDate.of(2026, 9, 2);              // a Wednesday
    static final LocalDate F1 = LocalDate.of(2026, 9, 7);
    static final MemberRow ANA = TestData.member("ana", TestData.TEAM);
    static final MemberRow BEN = TestData.member("ben", TestData.TEAM);
    static final MemberRow CID = TestData.member("cid", TestData.TEAM);
    static final UUID PROJECT = TestData.id("proj");
    static final UUID OTHER = TestData.id("other-proj");

    /** History: Ana took 6 of the project's tasks (4 Bugs), Ben 2 (Tasks), Cid none; each assigned 3 days after creation. */
    static ForecastData world(List<TaskRow> extra) {
        List<TaskRow> tasks = new ArrayList<>();
        List<com.workloadhub.forecast.data.rows.TransitionRow> history = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            LocalDateTime created = AS_OF.minusWeeks(10 - i).atTime(9, 0);
            TaskRow t = TestData.task("a" + i, ANA.id(), created, 4).withProject(PROJECT).withType(i < 4 ? "Bug" : "Task");
            tasks.add(t);
            history.add(TestData.assignee(t.id(), ANA.fullName(), created.plusDays(3)));
        }
        for (int i = 0; i < 2; i++) {
            LocalDateTime created = AS_OF.minusWeeks(8 - i).atTime(9, 0);
            TaskRow t = TestData.task("b" + i, BEN.id(), created, 4).withProject(PROJECT);
            tasks.add(t);
            history.add(TestData.assignee(t.id(), BEN.fullName(), created.plusDays(3)));
        }
        tasks.addAll(extra);
        return TestData.data(List.of(ANA, BEN, CID), tasks, history, List.of()).withProjects(List.of(
                new ProjectRow(PROJECT, "PRJ", "Project", "ACTIVE", TestData.TEAM),
                new ProjectRow(OTHER, "OTH", "Other", "ACTIVE", TestData.id("far-team"))));
    }

    @Test
    void candidatesAreUnassignedOpenTasksOfTheTeamsProjectsOlderThanTheLag() {
        TaskRow fresh = TestData.task("c1", null, AS_OF.minusDays(1).atTime(9, 0), 8).withProject(PROJECT);
        TaskRow old = TestData.task("c2", null, AS_OF.minusDays(4).atTime(9, 0), 8).withProject(PROJECT);
        TaskRow done = TestData.task("c3", null, AS_OF.minusDays(4).atTime(9, 0), 8).withProject(PROJECT).withStatus("DONE");
        TaskRow elsewhere = TestData.task("c4", null, AS_OF.minusDays(4).atTime(9, 0), 8).withProject(OTHER);
        ForecastData data = world(List.of(fresh, old, done, elsewhere));
        List<TaskFacts> c = PlannedWork.candidates(Lifecycle.derive(data), data, TestData.TEAM, AS_OF);
        assertEquals(List.of(old.id()), c.stream().map(TaskFacts::id).toList());
    }

    @Test
    void weightsFollowTheProjectAndFamilyHistoryWithShrinkage() {
        TaskRow bug = TestData.task("c", null, AS_OF.minusDays(4).atTime(9, 0), 8).withProject(PROJECT).withType("Bug");
        ForecastData data = world(List.of(bug));
        Lifecycle lc = Lifecycle.derive(data);
        List<MemberRow> eligible = List.of(ANA, BEN, CID);
        List<TaskFacts> history = PlannedWork.history(lc, eligible, AS_OF);
        Map<UUID, Double> w = PlannedWork.weights(lc.of(bug.id()), eligible, history);
        double k = PlannedWork.SHRINK_K;
        double equal = 1.0 / 3;
        // level 3: team × DEFECT: Ana 4 of 4
        double ana3 = (4 + k * equal) / (4 + k);
        double ben3 = (0 + k * equal) / (4 + k);
        // level 2: project: Ana 6, Ben 2 of 8
        double ana2 = (6 + k * ana3) / (8 + k);
        double ben2 = (2 + k * ben3) / (8 + k);
        // level 1: project × DEFECT: Ana 4 of 4
        assertEquals((4 + k * ana2) / (4 + k), w.get(ANA.id()), 1e-9);
        assertEquals((0 + k * ben2) / (4 + k), w.get(BEN.id()), 1e-9);
        assertEquals(1.0, w.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        assertTrue(w.get(CID.id()) > 0, "shrinkage keeps a newcomer in the running");
        assertTrue(w.get(ANA.id()) > w.get(BEN.id()) && w.get(BEN.id()) > w.get(CID.id()));
    }

    @Test
    void unknownProjectFallsThroughToTheTeamLevelAndNoHistoryIsAnEqualSplit() {
        TaskRow task = TestData.task("c", null, AS_OF.minusDays(4).atTime(9, 0), 8).withProject(TestData.id("new-proj")).withType("Task");
        ForecastData data = world(List.of(task));
        Lifecycle lc = Lifecycle.derive(data);
        List<MemberRow> eligible = List.of(ANA, BEN, CID);
        Map<UUID, Double> w = PlannedWork.weights(lc.of(task.id()), eligible, PlannedWork.history(lc, eligible, AS_OF));
        double k = PlannedWork.SHRINK_K;
        double equal = 1.0 / 3;
        assertEquals((2 + k * equal) / (4 + k), w.get(ANA.id()), 1e-9, "team × DELIVERY: Ana 2, Ben 2");
        assertEquals(w.get(ANA.id()), w.get(BEN.id()), 1e-9);
        Map<UUID, Double> none = PlannedWork.weights(lc.of(task.id()), eligible, List.of());
        assertEquals(equal, none.get(CID.id()), 1e-9);
    }

    @Test
    void lagIsTheProjectMedianWhenFiveTasksExistElseTheTeamThenAllThenTwoDays() {
        TaskRow task = TestData.task("c", null, AS_OF.minusDays(4).atTime(9, 0), 8).withProject(PROJECT);
        ForecastData data = world(List.of(task));
        Lifecycle lc = Lifecycle.derive(data);
        assertEquals(3, PlannedWork.lagDays(lc.of(task.id()), lc, data, Set.of(ANA.id(), BEN.id(), CID.id())));
        TaskRow elsewhere = TestData.task("d", null, AS_OF.minusDays(4).atTime(9, 0), 8).withProject(OTHER);
        ForecastData data2 = world(List.of(elsewhere));
        Lifecycle lc2 = Lifecycle.derive(data2);
        assertEquals(3, PlannedWork.lagDays(lc2.of(elsewhere.id()), lc2, data2, Set.of(ANA.id(), BEN.id())), "no project history: the team's");
        ForecastData bare = TestData.data(List.of(ANA), List.of(elsewhere), List.of(), List.of());
        Lifecycle lc3 = Lifecycle.derive(bare);
        assertEquals(Lifecycle.BACKLOG_LAG_DAYS, PlannedWork.lagDays(lc3.of(elsewhere.id()), lc3, bare, Set.of(ANA.id())));
    }

    @Test
    void allocationPlacesSharesFromTheExpectedDateAndReportsWhatFallsAfterTheWindow() {
        TaskRow soon = TestData.task("c1", null, AS_OF.minusDays(4).atTime(9, 0), 10).withProject(PROJECT).withType("Bug");
        TaskRow overdue = TestData.task("c2", null, AS_OF.minusWeeks(3).atTime(9, 0), 6).withProject(PROJECT);
        TaskRow far = TestData.task("c3", null, AS_OF.minusDays(2).atTime(9, 0), 4).withProject(PROJECT);
        ForecastData data = world(List.of(soon, overdue, far));
        Lifecycle lc = Lifecycle.derive(data);
        EffortModel effort = EffortModel.fit(lc, data);
        PlannedWork.Request req = new PlannedWork.Request(TestData.TEAM, List.of(ANA, BEN, CID), AS_OF, new LocalDate[] {F1, F1.plusWeeks(1)});
        PlannedWork.Allocation a = PlannedWork.allocate(req, lc, data, effort, CAL, id -> Set.of());
        assertEquals(3, a.candidateCount());
        assertEquals(20.0, a.candidateHours(), 1e-9);
        assertEquals(9, a.pieces().size(), "three candidates × three members");
        for (PlannedWork.Piece p : a.pieces()) {
            assertTrue(!p.expectedDate().isBefore(F1), "expected dates are floored at the first forecast week");
            assertEquals(p.estimate() * p.share() * effort.estimateRatio(p.member(), p.family(), TestData.TEAM),
                    p.hoursInWindow() + p.hoursAfterWindow(), 1e-9, "hours are conserved");
            if (p.taskId().equals(overdue.id())) {
                assertEquals(F1, p.expectedDate(), "created + 3 days is in the past: overdue for assignment");
                assertEquals(F1, p.expectedWeek());
            }
        }
        double inWindow = a.hours().values().stream().mapToDouble(Double::doubleValue).sum();
        double total = a.pieces().stream().mapToDouble(p -> p.hoursInWindow() + p.hoursAfterWindow()).sum();
        assertEquals(total - a.hoursAfterWindow(), inWindow, 1e-9);
        assertTrue(a.hours().keySet().stream().allMatch(k -> k.week().equals(F1) || k.week().equals(F1.plusWeeks(1))));
        assertTrue(a.hours().containsKey(new MemberWeek(ANA.id(), F1)));
    }

    @Test
    void noEligibleMemberMeansAnEmptyAllocationThatStillCountsTheBacklog() {
        TaskRow c = TestData.task("c1", null, AS_OF.minusDays(4).atTime(9, 0), 10).withProject(PROJECT);
        ForecastData data = world(List.of(c));
        Lifecycle lc = Lifecycle.derive(data);
        MemberRow gone = ANA.withLeft(AS_OF.minusDays(1));
        PlannedWork.Request req = new PlannedWork.Request(TestData.TEAM, List.of(gone), AS_OF, new LocalDate[] {F1, F1.plusWeeks(1)});
        PlannedWork.Allocation a = PlannedWork.allocate(req, lc, data, EffortModel.fit(lc, data), CAL, id -> Set.of());
        assertTrue(a.pieces().isEmpty());
        assertTrue(a.hours().isEmpty());
        assertEquals(1, a.candidateCount());
        assertEquals(10.0, a.candidateHours(), 1e-9);
    }

    @Test
    void seededTeamsHaveABacklogAndTheAllocationIsConserved() {
        ForecastData data = SeededData.data();
        Lifecycle lc = Lifecycle.derive(data);
        EffortModel effort = EffortModel.fit(lc, data);
        LocalDate asOf = SeededData.asOf();
        LocalDate[] weeks = com.workloadhub.forecast.calendar.Weeks.forecastWeeks(asOf);
        int teamsWithBacklog = 0;
        for (com.workloadhub.forecast.data.rows.TeamRow team : data.teams()) {
            List<MemberRow> members = data.membersOfTeam(team.id());
            if (members.isEmpty()) {
                continue;
            }
            PlannedWork.Allocation a = PlannedWork.allocate(new PlannedWork.Request(team.id(), members, asOf, weeks), lc, data, effort,
                    WorkingCalendar.fromHolidays(data.holidays()), id -> Set.of());
            if (a.candidateCount() > 0) {
                teamsWithBacklog++;
            }
            double pieces = a.pieces().stream().mapToDouble(p -> p.hoursInWindow() + p.hoursAfterWindow()).sum();
            double placed = a.hours().values().stream().mapToDouble(Double::doubleValue).sum() + a.hoursAfterWindow();
            assertEquals(pieces, placed, 1e-6, team.name());
        }
        assertTrue(teamsWithBacklog > 0, "the seed leaves an unassigned backlog");
    }
}
```

`PlannedWork.history(lc, eligible, asOf)` is a public helper the test uses (assigned tasks of the eligible members within the window); `MemberRow.withLeft(LocalDate)` is another record copy.

`PlannedWorkPropertyTest` (jqwik):

```java
package com.workloadhub.forecast.planned;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class PlannedWorkPropertyTest {

    static final LocalDate AS_OF = LocalDate.of(2026, 9, 2);
    static final UUID PROJECT = TestData.id("proj");

    @Provide
    Arbitrary<List<Integer>> assignments() {
        // each entry: which of four members took a task, and 0..3 the project/type combination
        return Arbitraries.integers().between(0, 15).list().ofMinSize(0).ofMaxSize(40);
    }

    @Property(tries = 60)
    void weightsArePositiveAndSumToOneForAnyHistory(@ForAll("assignments") List<Integer> codes) {
        List<MemberRow> members = List.of(TestData.member("m0", TestData.TEAM), TestData.member("m1", TestData.TEAM),
                TestData.member("m2", TestData.TEAM), TestData.member("m3", TestData.TEAM));
        List<TaskRow> tasks = new ArrayList<>();
        int i = 0;
        for (int code : codes) {
            MemberRow m = members.get(code % 4);
            UUID project = (code / 4) % 2 == 0 ? PROJECT : TestData.id("other");
            String type = (code / 8) % 2 == 0 ? "Bug" : "Task";
            tasks.add(TestData.task("h" + i++, m.id(), AS_OF.minusDays(1 + (i % 100)).atTime(9, 0), 4).withProject(project).withType(type));
        }
        TaskRow candidate = TestData.task("c", null, AS_OF.minusDays(5).atTime(9, 0), 8).withProject(PROJECT).withType("Bug");
        tasks.add(candidate);
        ForecastData data = TestData.data(members, tasks, List.of(), List.of());
        Lifecycle lc = Lifecycle.derive(data);
        List<TaskFacts> history = PlannedWork.history(lc, members, AS_OF);
        Map<UUID, Double> w = PlannedWork.weights(lc.of(candidate.id()), members, history);
        assertEquals(4, w.size());
        assertEquals(1.0, w.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        assertTrue(w.values().stream().allMatch(v -> v > 0));
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='PlannedWorkTest,PlannedWorkPropertyTest'`
Expected: compilation errors.

- [ ] **Step 3: Write `PlannedWork`**

```java
package com.workloadhub.forecast.planned;

import com.workloadhub.forecast.calendar.HourPlacement;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Family;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import com.workloadhub.forecast.model.EffortModel;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Who will take the unassigned backlog and when, per the planned-work design, section 5. */
public final class PlannedWork {

    public static final int WINDOW_WEEKS = 26;
    public static final double SHRINK_K = 3.0;
    public static final int MIN_PROJECT_TASKS_FOR_LAG = 5;

    public record Piece(UUID taskId, String key, String title, UUID projectId, Family family, double estimate, UUID member, double share,
            LocalDate expectedDate, LocalDate expectedWeek, double hoursInWindow, double hoursAfterWindow) {
    }

    public record Allocation(SortedMap<MemberWeek, Double> hours, List<Piece> pieces, double hoursAfterWindow, int candidateCount,
            double candidateHours) {
        public static Allocation empty() {
            return new Allocation(new TreeMap<>(), List.of(), 0.0, 0, 0.0);
        }
    }

    public record Request(UUID teamId, List<MemberRow> members, LocalDate asOf, LocalDate[] forecastWeeks) {
    }

    private PlannedWork() {
    }

    public static List<TaskFacts> candidates(Lifecycle lc, ForecastData data, UUID teamId, LocalDate asOf) {
        Set<UUID> projects = data.projectIdsOfTeamAndParent(teamId);
        LocalDate latestCreation = asOf.minusDays(Lifecycle.BACKLOG_LAG_DAYS);
        return lc.all().stream()
                .filter(f -> f.assignee() == null && !f.done() && f.task().projectId() != null && projects.contains(f.task().projectId()))
                .filter(f -> !f.task().createdDate().toLocalDate().isAfter(latestCreation))
                .sorted(Comparator.comparing(f -> f.id().toString()))
                .toList();
    }

    /** Assigned tasks of the members inside the window (asOf − 26 weeks, asOf]. */
    public static List<TaskFacts> history(Lifecycle lc, List<MemberRow> members, LocalDate asOf) {
        LocalDate from = asOf.minusWeeks(WINDOW_WEEKS);
        List<TaskFacts> out = new ArrayList<>();
        for (MemberRow m : members) {
            for (TaskFacts f : lc.assignedTo(m.id())) {
                LocalDate day = f.assignedDay();
                if (day.isAfter(from) && !day.isAfter(asOf)) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    public static Map<UUID, Double> weights(TaskFacts candidate, List<MemberRow> eligible, List<TaskFacts> history) {
        List<UUID> ids = eligible.stream().map(MemberRow::id).sorted(Comparator.comparing(UUID::toString)).toList();
        Map<UUID, Double> level = new LinkedHashMap<>();
        for (UUID id : ids) {
            level.put(id, 1.0 / ids.size());
        }
        UUID project = candidate.task().projectId();
        Family family = candidate.family();
        level = shrinkToward(level, history, f -> f.family() == family);
        if (project != null) {
            level = shrinkToward(level, history, f -> project.equals(f.task().projectId()));
            level = shrinkToward(level, history, f -> project.equals(f.task().projectId()) && f.family() == family);
        }
        return level;
    }

    private static Map<UUID, Double> shrinkToward(Map<UUID, Double> prior, List<TaskFacts> history, Predicate<TaskFacts> at) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (TaskFacts f : history) {
            if (at.test(f) && prior.containsKey(f.assignee())) {
                counts.merge(f.assignee(), 1, Integer::sum);
                total++;
            }
        }
        if (total == 0) {
            return prior;
        }
        Map<UUID, Double> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, Double> e : prior.entrySet()) {
            out.put(e.getKey(), (counts.getOrDefault(e.getKey(), 0) + SHRINK_K * e.getValue()) / (total + SHRINK_K));
        }
        return out;
    }

    public static long lagDays(TaskFacts candidate, Lifecycle lc, ForecastData data, Set<UUID> teamMembers) {
        UUID project = candidate.task().projectId();
        List<Long> projectLags = new ArrayList<>();
        List<Long> teamLags = new ArrayList<>();
        List<Long> allLags = new ArrayList<>();
        for (TaskFacts f : lc.all()) {
            if (!f.isAssigned()) {
                continue;
            }
            allLags.add(f.lagDays());
            if (project != null && project.equals(f.task().projectId())) {
                projectLags.add(f.lagDays());
            }
            if (teamMembers.contains(f.assignee())) {
                teamLags.add(f.lagDays());
            }
        }
        if (projectLags.size() >= MIN_PROJECT_TASKS_FOR_LAG) {
            return median(projectLags);
        }
        if (!teamLags.isEmpty()) {
            return median(teamLags);
        }
        if (!allLags.isEmpty()) {
            return median(allLags);
        }
        return Lifecycle.BACKLOG_LAG_DAYS;
    }

    static long median(List<Long> values) {
        List<Long> s = values.stream().sorted().toList();
        int n = s.size();
        double m = n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
        return Math.round(m);
    }

    public static Allocation allocate(Request req, Lifecycle lc, ForecastData data, EffortModel effort, WorkingCalendar cal,
            Function<UUID, Set<LocalDate>> offDaysOf) {
        List<TaskFacts> candidates = candidates(lc, data, req.teamId(), req.asOf());
        int count = candidates.size();
        double candidateHours = candidates.stream().mapToDouble(TaskFacts::estimate).sum();
        List<MemberRow> eligible = req.members().stream().filter(m -> m.employedOn(req.asOf())).toList();
        if (eligible.isEmpty() || candidates.isEmpty()) {
            return new Allocation(new TreeMap<>(), List.of(), 0.0, count, candidateHours);
        }
        Set<UUID> teamMembers = req.members().stream().map(MemberRow::id).collect(Collectors.toSet());
        List<TaskFacts> history = history(lc, eligible, req.asOf());
        LocalDate f1 = req.forecastWeeks()[0];
        LocalDate windowEnd = req.forecastWeeks()[req.forecastWeeks().length - 1].plusDays(6);
        SortedMap<MemberWeek, Double> hours = new TreeMap<>();
        List<Piece> pieces = new ArrayList<>();
        double after = 0.0;
        for (TaskFacts c : candidates) {
            LocalDate expected = c.task().createdDate().toLocalDate().plusDays(lagDays(c, lc, data, teamMembers));
            if (expected.isBefore(f1)) {
                expected = f1;
            }
            Map<UUID, Double> w = weights(c, eligible, history);
            for (Map.Entry<UUID, Double> e : w.entrySet()) {
                if (e.getValue() <= 0) {
                    continue;
                }
                UUID member = e.getKey();
                double allocated = c.estimate() * e.getValue();
                double scaled = allocated * effort.estimateRatio(member, c.family(), req.teamId());
                int span = (int) Math.round(effort.familyCycleDays(member, c.family(), req.teamId()));
                LocalDate end = expected.plusDays(Math.max(span - 1, 0));
                double in = 0.0;
                double out = 0.0;
                for (Map.Entry<LocalDate, Double> placed : HourPlacement.placeHours(scaled, expected, end, cal, offDaysOf.apply(member)).entrySet()) {
                    if (placed.getKey().isAfter(windowEnd)) {
                        out += placed.getValue();
                    } else {
                        in += placed.getValue();
                        hours.merge(new MemberWeek(member, placed.getKey()), placed.getValue(), Double::sum);
                    }
                }
                after += out;
                LocalDate expectedWeek = expected.isAfter(windowEnd) ? null : com.workloadhub.forecast.calendar.Weeks.mondayOf(expected);
                pieces.add(new Piece(c.id(), c.task().key(), c.task().title(), c.task().projectId(), c.family(), c.estimate(), member,
                        e.getValue(), expected, expectedWeek, in, out));
            }
        }
        return new Allocation(hours, List.copyOf(pieces), after, count, candidateHours);
    }
}
```

The level order in `weights` is deliberate: the equal split is the innermost prior, then `(team, family)`, then `(project)`, then `(project, family)`, so the most specific level is applied last, on top of the others, exactly as section 5.2 lists them from 1 to 4. `Piece.share` is the unrounded weight; the facts of the next plan round it to two decimals.

- [ ] **Step 4: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='PlannedWorkTest,PlannedWorkPropertyTest'`
Expected: 8 passed. `weightsFollowTheProjectAndFamilyHistoryWithShrinkage` computes the expected numbers by hand in the same order; if it fails, check that the window in `history` includes tasks assigned exactly 10 weeks before as-of (it does: `asOf − 26 weeks` is the exclusive bound).

- [ ] **Step 5: Run the whole module gate**

Run: `cd server && mvn -B -q verify`
Expected: green, under three minutes without Docker. If the XGBoost tests dominate, lower `weeks` in `SyntheticMatrix` calls of the backtest test rather than the number of rounds.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/planned server/forecast-core/src/test/java/com/workloadhub/forecast/planned server/forecast-core/src/main/java/com/workloadhub/forecast/data/rows
git commit -m "feat(server): planned-work allocation of the unassigned backlog

Share weights with shrinkage over 26 weeks through project and family
levels, expected assignment from the lag rule floored at the first
forecast week, hours placed like new arrivals and split at the window."
```

---

## Closing notes for the executor and the reviewer

**Rulings this plan takes, to report to the owner at the end:**

1. `SeasonalNaive` predicts the same week one year earlier (52 weeks), else `roll_mean_4`, as the Python floor does; the spec's sentence "lag13 when present" in section 7 is wrong and is corrected in the docs step below.
2. The Python effort model's gradient-boosting cycle regressor (fitted with 50+ rows) is not ported: hierarchy fallback only. The parity gate of the next plan measures the arrival level, which it does not touch.
3. `reopen_rate_13w` uses the present-day `reopened_from_done` flag, and `proj_active` / `proj_planning` use the present-day project status; both are stable under the truncation replay and are the accepted approximations, stated in the feature-matrix document.
4. Historical rows compute "remaining hours" as estimate minus hours logged by the assignee up to the row's week; only the effort placement uses the application's `remaining_estimate_hrs`.
5. `arrivals_13w` and the share columns count every arrival (fresh or backlog); only the hours series and the targets are fresh.
6. The feature matrix has 46 columns per horizon, not 42 (spec section 6) nor 45 (schema mapping document, section 5).
7. Rows start at the earlier of the member's join week and first assignment week (spec section 6 said "first assignment or join date").

**Docs step, part of the last task's commit or a small follow-up commit on `dev`:** in `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md` section 6 replace "42 columns" with "46 columns" and in section 7 replace the `SeasonalNaive` sentence with the rule above; in `docs/design/2026-09-08-workloadhub-schema-and-feature-matrix.md` section 5 replace "45 columns" with "46 columns (after dropping the three planned-week columns)"; add a line to `docs/backlog.md` under "Java migration" for the cycle regressor and the two approximations.

**What the next plan takes from here:** `ForecastRepository.loadAll()` → `Lifecycle.derive` → `WorkingCalendar`, `CapacityRule` → `FeatureBuilder.build(members, lastCompleteWeek)` → `Backtest.origins/run/selectChampion` → champion `fit` on every row and `predict` at the origin rows for `h1 = weeksBetween(origin, f1)` and `h1 + 1` → `EffortModel.fit`, `placeOpenTasks(open, f1)`, `placeNewArrivals` → `PlannedWork.allocate` → capacity, overload, bands (`Backtest.intervalBounds` on the champion's residuals at each horizon, applied to new hours only) → persistence, facts, CLI `run` and `eval` (the truncated replay is `Truncation.at`), the Python parity check.
