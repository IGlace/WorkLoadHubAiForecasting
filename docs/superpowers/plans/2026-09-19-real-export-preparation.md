# Real-export preparation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one driver verb, `prepare`, that rewrites a real WorkloadHub JSON export so the forecast can count
its people — every user active, and `teams` / `team_members` derived from `department` and `manager_id`.

**Architecture:** A pure function, `ExportPreparer.prepare(ExportEnvelope, LocalDate, long)`, reads an envelope and
returns a new one with exactly three tables rewritten (`users`, `teams`, `team_members`) and everything else copied
through. `Experiment.prepare` is the file-in/file-out shell around it. Nothing in `forecast-core`, `SeedGenerator`,
`Directory` or `ExportImporter` changes, except that `Directory.uniqueName` becomes public so the new code reuses it
instead of restating it.

**Tech Stack:** Java 21, Maven 3.9, JUnit 6, jqwik. Module: `forecast-tools` (never shipped).

**Spec:** `docs/superpowers/specs/2026-09-19-real-export-preparation-design.md` — read it before Task 1. Its
sections 4, 4.1, 5, 5.1 and 6 are the requirements; this plan implements them in that order.

## Global Constraints

- Branch: `dev`. Commit after every task. Never push to another branch.
- The real export never enters the repository, and no test may read one. Every test builds its own envelope in
  code or reads `forecast-tools/src/test/resources/fixtures/`.
- `prepare`'s output holds personal data: the command refuses to write inside a git repository without `--force`,
  exactly as `seed` does (`Experiment.java:184-187`).
- Test-driven: the failing test first, every time. jqwik property tests for the invariants (Task 4).
- Driver messages and help are **English only**.
- A property-test file's name must end in `PropertyTest` — surefire collects by that suffix, and a class mixing
  `@Test` with `@Property` has both engines overwrite the same `.txt` report.
- Java style in this repo: `final class` with a private constructor for utility classes, javadoc on every public
  type and on any method whose reason is not obvious from its name, comments that say *why*.
- Roles that exist: `ADMIN`, `CENTER_MANAGER`, `SKILL_TEAM_LEADER`, `TEAM_LEADER`, `MEMBER`, `VIEWER`
  (`users_role_check`). The forecast counts `MEMBER` and `TEAM_LEADER` only. **Never** produce
  `SKILL_TEAM_LEADER` — spec section 4.1.

## Running the tests

Tasks 1-5 are pure: no database, no container.

```bash
cd server && mvn -B -pl forecast-tools -am test -Dtest='ExportPreparer*Test' -DfailIfNoTests=false
```

Task 6 touches `ExperimentFlowTest`, which starts PostgreSQL through Testcontainers and **needs a container
engine**. If `docker info` fails, write the test, state plainly that it was not executed, and leave it for the
owner's gate — do not weaken it, and do not report it as passing.

The full gate is `bash scripts/check.sh` inside the development container (`bash scripts/devbox.sh shell`). The
owner runs it; see the closing task.

---

## File Structure

| File | Responsibility |
|---|---|
| `server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/prepare/ExportPreparer.java` | **Create.** The whole transformation: user activation, role promotion, team derivation, row writing. One public entry point. |
| `server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/seed/Directory.java` | **Modify.** `uniqueName` private → public (Task 2). Nothing else. |
| `server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/Experiment.java` | **Modify.** The `prepare` verb, its `USAGE` entry, its dispatch case (Task 5). |
| `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/prepare/ExportPreparerTest.java` | **Create.** Tasks 1, 2, 3 — the example-based tests. |
| `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/prepare/ExportPreparerPropertyTest.java` | **Create.** Task 4 — the three invariants. |
| `server/forecast-tools/src/test/resources/fixtures/raw-export.json` | **Create.** Task 6 — a small export in the shape the owner's is in: users inactive, no teams. |
| `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/ExperimentFlowTest.java` | **Modify.** Task 5 (git guard) and Task 6 (end to end). |
| `server/README.md`, `CLAUDE.md`, `docs/backlog.md` | **Modify.** Task 6. |

---

### Task 1: Users become active, and managers become team leaders

Spec sections 4 and 4.1.

**Files:**
- Create: `server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/prepare/ExportPreparer.java`
- Create: `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/prepare/ExportPreparerTest.java`

**Interfaces:**
- Consumes: `ExportEnvelope` (`com.workloadhub.forecast.tools.export`) — `rows(String)`, `data()`,
  `withData(LinkedHashMap)`.
- Produces: `ExportPreparer.SEED` (a `public static final long`), `ExportPreparer.Result` and
  `ExportPreparer.prepare(ExportEnvelope, LocalDate, long)`. Tasks 2-6 all build on these exact names. The
  `Result` record is declared in full here and **not changed afterwards**; Tasks 2 and 3 only start filling in
  fields that this task leaves at zero.

- [ ] **Step 1: Write the failing test**

Create `ExportPreparerTest.java`. The `user(...)` helper is lifted from `DirectoryTest` so the rows carry every
column the real schema has.

```java
package com.workloadhub.forecast.tools.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.tools.export.ExportEnvelope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExportPreparerTest {

    static final UUID HEAD = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final UUID MGR = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final UUID ENG1 = UUID.fromString("30000000-0000-0000-0000-000000000003");
    static final UUID ENG2 = UUID.fromString("30000000-0000-0000-0000-000000000004");
    static final UUID ORPHAN = UUID.fromString("30000000-0000-0000-0000-000000000005");
    static final UUID LOST = UUID.fromString("30000000-0000-0000-0000-000000000006");
    static final UUID BOSS = UUID.fromString("30000000-0000-0000-0000-000000000007");

    static final LocalDate JOINED = LocalDate.of(2021, 1, 4);

    static LinkedHashMap<String, Object> user(UUID id, String name, String title, String dept, UUID manager,
            String role, boolean active) {
        LinkedHashMap<String, Object> u = new LinkedHashMap<>();
        u.put("id", id.toString());
        u.put("role", role);
        u.put("email", name.toLowerCase().replace(' ', '.') + "@example.test");
        u.put("active", active);
        u.put("version", 0L);
        u.put("password", null);
        u.put("username", name.toLowerCase().replace(' ', '.'));
        u.put("full_name", name);
        u.put("job_title", title);
        u.put("object_id", null);
        u.put("created_at", "2025-01-01T08:00:00");
        u.put("department", dept);
        u.put("manager_id", manager == null ? null : manager.toString());
        u.put("updated_at", "2025-01-01T08:00:00");
        u.put("account_name", null);
        u.put("deactivated_at", active ? null : "2026-01-01T08:00:00");
        u.put("manager_object_id", null);
        return u;
    }

    /** Six people in two departments, one of them with no department at all, plus a centre manager. */
    static List<LinkedHashMap<String, Object>> users() {
        return new ArrayList<>(List.of(
                user(HEAD, "Head One", "Skill Team Leader", "PTE / CT2 Calibration & Testing 2", null, "MEMBER", false),
                user(MGR, "Manager Two", "Team Leader Calibration", "PTE / CT2 Calibration & Testing 2", HEAD, "MEMBER", false),
                user(ENG1, "Eng Three", "Calibration Engineer", "PTE / CT2", MGR, "MEMBER", false),
                user(ENG2, "Eng Four", "Calibration Engineer", "PTE / CT2 Calibration & Testing 2", MGR, "MEMBER", false),
                user(ORPHAN, "Orphan Five", "Simulation Engineer", "PTE / SIM Simulation", null, "MEMBER", true),
                user(LOST, "Lost Six", null, null, null, "MEMBER", false),
                user(BOSS, "Boss Seven", "Centre Manager", null, null, "CENTER_MANAGER", false)));
    }

    static ExportEnvelope envelope(List<LinkedHashMap<String, Object>> users,
            List<LinkedHashMap<String, Object>> teams, List<LinkedHashMap<String, Object>> members) {
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>();
        data.put("users", users);
        data.put("teams", teams);
        data.put("team_members", members);
        return new ExportEnvelope("workloadhub", "task_service", "2026-09-19T10:00:00", List.of(), data);
    }

    static ExportPreparer.Result prepared() {
        return ExportPreparer.prepare(envelope(users(), new ArrayList<>(), new ArrayList<>()), JOINED, ExportPreparer.SEED);
    }

    static LinkedHashMap<String, Object> row(List<LinkedHashMap<String, Object>> rows, UUID id) {
        return rows.stream().filter(r -> id.toString().equals(r.get("id"))).findFirst().orElseThrow();
    }

    @Test
    void everyUserComesOutActiveAndNeverDeactivated() {
        ExportPreparer.Result result = prepared();
        List<LinkedHashMap<String, Object>> out = result.envelope().rows("users");
        assertEquals(7, out.size());
        for (LinkedHashMap<String, Object> u : out) {
            assertEquals(Boolean.TRUE, u.get("active"), u.get("full_name") + " must be active");
            assertNull(u.get("deactivated_at"), u.get("full_name") + " must carry no deactivation");
        }
        assertEquals(6, result.usersActivated(), "Orphan Five arrived active and is not counted");
    }

    @Test
    void managersBecomeTeamLeadersAndNobodyBecomesASkillTeamLeader() {
        ExportPreparer.Result result = prepared();
        List<LinkedHashMap<String, Object>> out = result.envelope().rows("users");
        assertEquals("TEAM_LEADER", row(out, HEAD).get("role"), "Head One manages Manager Two");
        assertEquals("TEAM_LEADER", row(out, MGR).get("role"), "Manager Two manages two engineers");
        assertEquals("MEMBER", row(out, ENG1).get("role"), "an engineer manages nobody");
        assertEquals("MEMBER", row(out, ORPHAN).get("role"));
        assertEquals("CENTER_MANAGER", row(out, BOSS).get("role"), "the centre manager keeps their role");
        assertEquals(2, result.managersPromoted());
        assertTrue(out.stream().noneMatch(u -> "SKILL_TEAM_LEADER".equals(u.get("role"))),
                "SKILL_TEAM_LEADER is not counted by ForecastRepository; the forecast would lose these people");
    }

    @Test
    void everyOtherTableIsCopiedThroughUnchanged() {
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>();
        data.put("users", users());
        LinkedHashMap<String, Object> holiday = new LinkedHashMap<>();
        holiday.put("id", "90000000-0000-0000-0000-000000000001");
        holiday.put("date", "2026-01-01");
        holiday.put("active", true);
        data.put("holidays", new ArrayList<>(List.of(holiday)));
        ExportEnvelope input = new ExportEnvelope("workloadhub", "task_service", "2026-09-19T10:00:00", List.of("notifications"), data);

        ExportEnvelope out = ExportPreparer.prepare(input, JOINED, ExportPreparer.SEED).envelope();

        assertEquals(List.of(holiday), out.rows("holidays"));
        assertEquals("workloadhub", out.database());
        assertEquals("task_service", out.schema());
        assertEquals("2026-09-19T10:00:00", out.exportedAt());
        assertEquals(List.of("notifications"), out.excludedTables());
    }

    @Test
    void anExportWithNoUsersIsRefused() {
        ExportEnvelope empty = new ExportEnvelope("workloadhub", "task_service", null, List.of(), new LinkedHashMap<>());
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ExportPreparer.prepare(empty, JOINED, ExportPreparer.SEED));
        assertTrue(e.getMessage().contains("users"), e.getMessage());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd server && mvn -B -pl forecast-tools -am test -Dtest='ExportPreparerTest' -DfailIfNoTests=false
```

Expected: compilation failure — `package com.workloadhub.forecast.tools.prepare does not exist`.

- [ ] **Step 3: Write the implementation**

Create `ExportPreparer.java`. `Result` carries four fields that stay at zero until Tasks 2 and 3 — declared now so
later tasks never change this record's shape.

```java
package com.workloadhub.forecast.tools.prepare;

import com.workloadhub.forecast.tools.export.ExportEnvelope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Rewrites a real WorkloadHub export so the forecast can count its people.
 *
 * <p>{@code ForecastRepository} counts a member only when {@code users.active} is true, the role is MEMBER or
 * TEAM_LEADER, and there is at least one {@code team_members} row. A real export straight out of the application
 * fails the first and the third while WorkloadHub is in testing: almost nobody is active, and its teams screen
 * has not been used. This class corrects the export file, in front of the seed, so that
 * {@code SeedGenerator}'s real mode, {@code ExportImporter} and {@code forecast-core} all stay untouched.
 *
 * <p><b>Transitional.</b> The owner has confirmed WorkloadHub's teams will be populated for real. On that day
 * this class and the driver's {@code prepare} verb are deleted, and nothing else moves — which is exactly why
 * the derivation does not live inside {@code SeedGenerator}, where it would silently overwrite the company's
 * own structure on every run. Design: {@code docs/superpowers/specs/2026-09-19-real-export-preparation-design.md}.
 */
public final class ExportPreparer {

    /**
     * The {@code SeedRandom} seed the driver always passes, so two runs over one export are byte-identical.
     * It is a parameter of {@link #prepare} only so the tests can vary it.
     */
    public static final long SEED = 20260919L;

    /** The prepared export and what the run did, for the driver to print. */
    public record Result(ExportEnvelope envelope, int usersActivated, int managersPromoted,
            int departmentTeams, int managerTeams, int teamsKept, int teamsDropped) {
    }

    private ExportPreparer() {
    }

    public static Result prepare(ExportEnvelope input, LocalDate joined, long seed) {
        List<LinkedHashMap<String, Object>> inputUsers = input.rows("users");
        if (inputUsers.isEmpty()) {
            throw new IllegalArgumentException("the export carries no users; there is nothing to prepare");
        }

        // Only a manager who is themselves in the export: users.manager_id can name somebody outside it, and
        // a team whose manager_id does not resolve fails the foreign key at import.
        Set<Object> ids = new HashSet<>();
        inputUsers.forEach(u -> ids.add(u.get("id")));
        Set<Object> managerIds = new HashSet<>();
        for (LinkedHashMap<String, Object> u : inputUsers) {
            Object manager = u.get("manager_id");
            if (manager != null && ids.contains(manager)) {
                managerIds.add(manager);
            }
        }

        int activated = 0;
        int promoted = 0;
        List<LinkedHashMap<String, Object>> users = new ArrayList<>();
        for (LinkedHashMap<String, Object> u : inputUsers) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>(u);
            if (!Boolean.TRUE.equals(row.get("active")) || row.get("deactivated_at") != null) {
                activated++;
            }
            row.put("active", true);
            // Not tidiness: ForecastRepository reads deactivated_at as the member's leaving date, so a user
            // flipped active while still carrying one is counted and then forecast at zero from that day.
            row.put("deactivated_at", null);
            if (managerIds.contains(row.get("id")) && "MEMBER".equals(row.get("role"))) {
                // A team whose manager_id names a plain MEMBER is inconsistent, and Rhythm halves a
                // TEAM_LEADER's seeded hours, which is the realistic shape. SKILL_TEAM_LEADER is never
                // produced: ForecastRepository does not count it, so it would drop these people entirely.
                row.put("role", "TEAM_LEADER");
                promoted++;
            }
            users.add(row);
        }

        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>(input.data());
        data.put("users", users);
        return new Result(input.withData(data), activated, promoted, 0, 0, 0, 0);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd server && mvn -B -pl forecast-tools -am test -Dtest='ExportPreparerTest' -DfailIfNoTests=false
```

Expected: 4 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
cd /home/user/WorkLoadHubAiForecasting
git add server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/prepare/ExportPreparer.java \
        server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/prepare/ExportPreparerTest.java
git commit -m "feat(tools): activate a real export's users and promote its managers

ForecastRepository counts a member only when active is true, so an export
from a WorkloadHub still in testing forecasts nobody. Clear deactivated_at
alongside active: the repository reads it as the member's leaving date, and
a user flipped active while still carrying one is forecast at zero from that
day. Managers become TEAM_LEADER; nobody becomes SKILL_TEAM_LEADER, which
the repository does not count."
```

---

### Task 2: Derive the two-level team structure

Spec section 5.

**Files:**
- Modify: `server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/seed/Directory.java:38` — `uniqueName` private → public
- Modify: `server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/prepare/ExportPreparer.java`
- Modify: `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/prepare/ExportPreparerTest.java`

**Interfaces:**
- Consumes: `Directory.deptCode(String)` (already public), `Directory.uniqueName(String, Set<String>)` (made
  public here), `SeedRandom.uuid()`, and the record
  `Team(UUID id, String name, UUID managerId, UUID parentId, List<UUID> memberIds, boolean department, String deptCode)`
  from `com.workloadhub.forecast.tools.seed`.
- Produces: package-private `ExportPreparer.Member`, `ExportPreparer.members(List)` and
  `ExportPreparer.deriveTeams(List<Member>, Set<String>, SeedRandom)` returning `List<Team>` — department teams
  first, then manager teams. Task 3 turns that list into rows.

- [ ] **Step 1: Write the failing tests**

Append to `ExportPreparerTest.java`, and add these imports at the top of the file:

```java
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.workloadhub.forecast.tools.seed.SeedRandom;
import com.workloadhub.forecast.tools.seed.Team;
import java.util.HashSet;
```

```java
    static List<Team> derived() {
        return ExportPreparer.deriveTeams(ExportPreparer.members(users()), new HashSet<>(), new SeedRandom(ExportPreparer.SEED));
    }

    static Team named(List<Team> teams, String name) {
        return teams.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void departmentsBecomeParentlessTeamsAndManagersBecomeTheirChildren() {
        List<Team> teams = derived();
        // CT2 (five people, since "PTE / CT2" collapses to the same code), SIM (one), Unassigned (two)
        List<Team> departments = teams.stream().filter(Team::department).toList();
        assertEquals(3, departments.size(), teams.toString());
        assertTrue(departments.stream().allMatch(t -> t.parentId() == null));

        Team ct2 = named(teams, "PTE / CT2 Calibration & Testing 2");
        assertTrue(ct2.department());
        assertEquals(HEAD, ct2.managerId(), "the Skill Team Leader by job title heads the department");
        assertEquals("CT2", ct2.deptCode());

        List<Team> managerTeams = teams.stream().filter(t -> !t.department()).toList();
        assertEquals(2, managerTeams.size(), "Head One and Manager Two each have reports");
        for (Team t : managerTeams) {
            assertEquals(ct2.id(), t.parentId(), "a manager team hangs under its department team");
        }
        assertNotNull(named(teams, "CT2 · Manager Two"));
    }

    @Test
    void peopleWithNoDepartmentLandInUnassigned() {
        Team unassigned = named(derived(), "Unassigned");
        assertTrue(unassigned.department());
        assertNull(unassigned.deptCode());
        assertTrue(unassigned.memberIds().contains(LOST));
        assertTrue(unassigned.memberIds().contains(BOSS));
    }

    @Test
    void everyUserIsInAtLeastOneTeam() {
        List<Team> teams = derived();
        for (LinkedHashMap<String, Object> u : users()) {
            UUID id = UUID.fromString((String) u.get("id"));
            assertTrue(teams.stream().anyMatch(t -> t.memberIds().contains(id)),
                    u.get("full_name") + " is in no team, so ForecastRepository would not count them");
        }
    }

    @Test
    void departmentMembersAreTheHeadAndThePeopleWithNoManager() {
        Team ct2 = named(derived(), "PTE / CT2 Calibration & Testing 2");
        assertEquals(List.of(HEAD), ct2.memberIds(),
                "Manager Two and the engineers reach the department through their manager team");
    }

    @Test
    void collidingNamesGetASuffix() {
        Set<String> used = new HashSet<>();
        used.add("Unassigned");
        List<Team> teams = ExportPreparer.deriveTeams(ExportPreparer.members(users()), used, new SeedRandom(ExportPreparer.SEED));
        assertNotNull(named(teams, "Unassigned 2"), "teams.name is UNIQUE, so a taken name gets a suffix");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd server && mvn -B -pl forecast-tools -am test -Dtest='ExportPreparerTest' -DfailIfNoTests=false
```

Expected: compilation failure — `cannot find symbol: method deriveTeams`.

- [ ] **Step 3: Make `Directory.uniqueName` public**

In `server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/seed/Directory.java`, change line 38 from

```java
    private static String uniqueName(String candidate, Set<String> usedNames) {
```

to

```java
    /** Public so {@code ExportPreparer} mints names under the same UNIQUE constraint instead of restating it. */
    public static String uniqueName(String candidate, Set<String> usedNames) {
```

Change nothing else in that file.

- [ ] **Step 4: Write the derivation**

Add to `ExportPreparer.java`. New imports: `com.workloadhub.forecast.tools.seed.Directory`,
`com.workloadhub.forecast.tools.seed.SeedRandom`, `com.workloadhub.forecast.tools.seed.Team`,
`java.util.Comparator`, `java.util.Locale`, `java.util.Map`, `java.util.TreeMap`, `java.util.UUID`.

```java
    /** One user, reduced to the fields the structure is derived from. */
    record Member(UUID id, String fullName, String jobTitle, String department, String deptCode,
            UUID managerId, String role) {
    }

    /** The users as {@link Member}s, sorted by id string so the derivation is the same on every run. */
    static List<Member> members(List<LinkedHashMap<String, Object>> users) {
        List<LinkedHashMap<String, Object>> sorted = new ArrayList<>(users);
        sorted.sort(Comparator.comparing(u -> String.valueOf(u.get("id"))));
        List<Member> out = new ArrayList<>();
        for (LinkedHashMap<String, Object> u : sorted) {
            String dept = (String) u.get("department");
            out.add(new Member(UUID.fromString((String) u.get("id")), (String) u.get("full_name"),
                    (String) u.get("job_title"), dept, Directory.deptCode(dept),
                    u.get("manager_id") == null ? null : UUID.fromString((String) u.get("manager_id")),
                    String.valueOf(u.get("role"))));
        }
        return out;
    }

    /**
     * The department teams (parentless, one per department code) followed by the manager teams (one per user
     * with reports, child of that manager's department team).
     *
     * <p>The two levels are not cosmetic. {@code ForecastRepository} picks a member's primary team as the first
     * of their teams that has a parent; {@code ProjectPlanner} gives projects only to parentless teams, so a flat
     * structure produces no work at all; and {@code Rhythm} lets a manager team win over a department team.
     * This mirrors what {@code Directory} builds on the synthetic path.
     *
     * <p>{@code usedNames} is mutated as names are minted, and should be seeded with the names of any
     * pre-existing team the caller is keeping: {@code teams.name} is UNIQUE.
     */
    static List<Team> deriveTeams(List<Member> people, Set<String> usedNames, SeedRandom rnd) {
        Map<UUID, Member> byId = new LinkedHashMap<>();
        people.forEach(m -> byId.put(m.id(), m));

        Map<UUID, List<UUID>> reports = new TreeMap<>();
        Map<String, List<UUID>> byDept = new TreeMap<>();
        Map<String, String> deptLabel = new TreeMap<>();
        for (Member m : people) {
            if (m.managerId() != null && byId.containsKey(m.managerId())) {
                reports.computeIfAbsent(m.managerId(), k -> new ArrayList<>()).add(m.id());
            }
            String code = m.deptCode() == null ? "" : m.deptCode();
            byDept.computeIfAbsent(code, k -> new ArrayList<>()).add(m.id());
            // the longest department string wins, so "PTE / CT2" and "PTE / CT2 Calibration & Testing 2"
            // share a code and the team carries the fuller label
            if (m.department() != null && m.department().length() > deptLabel.getOrDefault(code, "").length()) {
                deptLabel.put(code, m.department());
            }
        }

        Map<String, UUID> heads = new TreeMap<>();
        for (Map.Entry<String, List<UUID>> e : byDept.entrySet()) {
            if (e.getKey().isEmpty()) {
                continue;
            }
            UUID head = e.getValue().stream()
                    .filter(id -> byId.get(id).jobTitle() != null
                            && byId.get(id).jobTitle().toLowerCase(Locale.ROOT).contains("skill team leader"))
                    .findFirst()
                    .orElseGet(() -> e.getValue().stream()
                            .filter(reports::containsKey)
                            .max(Comparator.comparingInt(id -> reports.get(id).size()))
                            .orElse(null));
            if (head != null) {
                heads.put(e.getKey(), head);
            }
        }

        List<Team> teams = new ArrayList<>();
        Map<String, UUID> deptTeamIds = new TreeMap<>();
        for (Map.Entry<String, List<UUID>> e : byDept.entrySet()) {
            String code = e.getKey();
            UUID teamId = rnd.uuid();
            deptTeamIds.put(code, teamId);
            List<UUID> memberIds = new ArrayList<>();
            for (UUID id : e.getValue()) {
                Member m = byId.get(id);
                boolean hasManager = m.managerId() != null && byId.containsKey(m.managerId());
                if (!hasManager || id.equals(heads.get(code))) {
                    memberIds.add(id);
                }
            }
            String name = Directory.uniqueName(code.isEmpty() ? "Unassigned" : deptLabel.getOrDefault(code, code), usedNames);
            // managerId may be null: a department with no head is legal, and ProjectPlanner.fallbackOwner
            // gives its projects to the CENTER_MANAGER or the ADMIN.
            teams.add(new Team(teamId, name, heads.get(code), null, memberIds, true, code.isEmpty() ? null : code));
        }

        for (Map.Entry<UUID, List<UUID>> e : reports.entrySet()) {
            Member m = byId.get(e.getKey());
            String code = m.deptCode();
            if (code == null) {
                // a manager with no department of their own takes the majority code of their reports
                Map<String, Integer> votes = new TreeMap<>();
                for (UUID r : e.getValue()) {
                    String c = byId.get(r).deptCode();
                    if (c != null) {
                        votes.merge(c, 1, Integer::sum);
                    }
                }
                code = votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
            }
            List<UUID> memberIds = new ArrayList<>();
            memberIds.add(m.id());
            memberIds.addAll(e.getValue());
            String name = Directory.uniqueName((code.isEmpty() ? "Team" : code) + " · " + m.fullName(), usedNames);
            teams.add(new Team(rnd.uuid(), name, m.id(), deptTeamIds.get(code), memberIds, false,
                    code.isEmpty() ? null : code));
        }
        return teams;
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd server && mvn -B -pl forecast-tools -am test -Dtest='ExportPreparerTest,DirectoryTest' -DfailIfNoTests=false
```

Expected: 9 tests in `ExportPreparerTest` and every `DirectoryTest` test, 0 failures. `DirectoryTest` is run too
because `Directory` was edited.

- [ ] **Step 6: Commit**

```bash
cd /home/user/WorkLoadHubAiForecasting
git add server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/ \
        server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/prepare/ExportPreparerTest.java
git commit -m "feat(tools): derive department and manager teams from a real export

A department team per department code, parentless, and a manager team per
user with reports, hanging under it. The two levels are load-bearing:
ForecastRepository picks a primary team from the teams that have a parent,
ProjectPlanner gives projects only to parentless teams, and Rhythm lets a
manager team win over a department team. Directory.uniqueName becomes public
so the names are minted under the same UNIQUE constraint as the seed's."
```

---

### Task 3: Write the rows, and keep a referenced stub team

Spec sections 5.1 and 6.

**Files:**
- Modify: `server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/prepare/ExportPreparer.java`
- Modify: `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/prepare/ExportPreparerTest.java`

**Interfaces:**
- Consumes: `deriveTeams`, `members`, `Result` from Tasks 1-2.
- Produces: `prepare` now fills every field of `Result`, and the returned envelope's `teams` and `team_members`
  are complete. Tasks 5 and 6 depend on nothing else.

- [ ] **Step 1: Write the failing tests**

Append to `ExportPreparerTest.java`:

```java
    static LinkedHashMap<String, Object> stubTeam(UUID id, String name, UUID parent) {
        LinkedHashMap<String, Object> t = new LinkedHashMap<>();
        t.put("id", id.toString());
        t.put("name", name);
        t.put("active", true);
        t.put("version", 0L);
        t.put("manager_id", null);
        t.put("parent_team_id", parent == null ? null : parent.toString());
        t.put("created_at", "2026-09-03T13:59:58");
        t.put("updated_at", "2026-09-03T13:59:58");
        return t;
    }

    static final UUID STUB = UUID.fromString("40000000-0000-0000-0000-000000000001");
    static final UUID STUB_PARENT = UUID.fromString("40000000-0000-0000-0000-000000000002");
    static final UUID STUB_FREE = UUID.fromString("40000000-0000-0000-0000-000000000003");

    @Test
    void teamRowsCarryTheDerivedStructure() {
        ExportPreparer.Result result = prepared();
        List<LinkedHashMap<String, Object>> teams = result.envelope().rows("teams");
        assertEquals(5, teams.size(), "three departments and two manager teams");
        assertEquals(3, result.departmentTeams());
        assertEquals(2, result.managerTeams());
        for (LinkedHashMap<String, Object> t : teams) {
            assertEquals(Boolean.TRUE, t.get("active"));
            assertEquals(0L, t.get("version"));
            assertEquals("2021-01-04T08:00", t.get("created_at"));
        }
        Set<Object> ids = new HashSet<>();
        teams.forEach(t -> ids.add(t.get("id")));
        for (LinkedHashMap<String, Object> t : teams) {
            if (t.get("parent_team_id") != null) {
                assertTrue(ids.contains(t.get("parent_team_id")), "a parent that is not in the export fails the key");
            }
        }
    }

    @Test
    void membershipRowsUseTheRequestedJoinedDate() {
        List<LinkedHashMap<String, Object>> rows = prepared().envelope().rows("team_members");
        assertTrue(rows.size() >= 7, "at least one row per user: " + rows.size());
        Set<String> pairs = new HashSet<>();
        for (LinkedHashMap<String, Object> m : rows) {
            assertEquals("2021-01-04T08:00", m.get("joined_at"),
                    "ForecastRepository folds the earliest joined_at into the member's start date");
            assertEquals("2021-01-04T08:00", m.get("created_at"));
            assertEquals("2021-01-04T08:00", m.get("updated_at"));
            assertTrue(pairs.add(m.get("team_id") + "/" + m.get("user_id")), "(team_id, user_id) is UNIQUE");
        }
    }

    @Test
    void aStubTeamNothingReferencesIsDropped() {
        ExportEnvelope input = envelope(users(),
                new ArrayList<>(List.of(stubTeam(STUB_FREE, "Frontend Team", null))),
                new ArrayList<>());
        ExportPreparer.Result result = ExportPreparer.prepare(input, JOINED, ExportPreparer.SEED);
        assertEquals(0, result.teamsKept());
        assertEquals(1, result.teamsDropped());
        assertTrue(result.envelope().rows("teams").stream().noneMatch(t -> STUB_FREE.toString().equals(t.get("id"))));
    }

    @Test
    void aStubTeamAProjectPointsAtSurvivesWithItsAncestors() {
        LinkedHashMap<String, Object> project = new LinkedHashMap<>();
        project.put("id", "80000000-0000-0000-0000-000000000001");
        project.put("key", "CT2-CAL");
        project.put("team_id", STUB.toString());

        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>();
        data.put("users", users());
        data.put("teams", new ArrayList<>(List.of(
                stubTeam(STUB_PARENT, "Engineering", null),
                stubTeam(STUB, "Backend Team", STUB_PARENT),
                stubTeam(STUB_FREE, "Frontend Team", null))));
        LinkedHashMap<String, Object> membership = new LinkedHashMap<>();
        membership.put("id", "50000000-0000-0000-0000-000000000001");
        membership.put("team_id", STUB.toString());
        membership.put("user_id", ENG1.toString());
        membership.put("joined_at", "2026-09-03T14:00:00");
        membership.put("created_at", "2026-09-03T14:00:00");
        membership.put("updated_at", "2026-09-03T14:00:00");
        data.put("team_members", new ArrayList<>(List.of(membership)));
        data.put("projects", new ArrayList<>(List.of(project)));

        ExportPreparer.Result result = ExportPreparer.prepare(
                new ExportEnvelope("workloadhub", "task_service", null, List.of(), data), JOINED, ExportPreparer.SEED);

        assertEquals(2, result.teamsKept(), "the referenced team and its parent");
        assertEquals(1, result.teamsDropped());
        Set<Object> ids = new HashSet<>();
        result.envelope().rows("teams").forEach(t -> ids.add(t.get("id")));
        assertTrue(ids.contains(STUB.toString()), "dropping it would fail projects.team_id at import");
        assertTrue(ids.contains(STUB_PARENT.toString()), "dropping it would fail teams.parent_team_id at import");
        assertTrue(!ids.contains(STUB_FREE.toString()));
        assertTrue(result.envelope().rows("team_members").stream()
                .anyMatch(m -> STUB.toString().equals(m.get("team_id"))), "a kept team keeps its memberships");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd server && mvn -B -pl forecast-tools -am test -Dtest='ExportPreparerTest' -DfailIfNoTests=false
```

Expected: FAIL — `teamRowsCarryTheDerivedStructure` gets 0 teams, the counters are 0.

- [ ] **Step 3: Write the implementation**

Add these two methods to `ExportPreparer.java`:

```java
    /**
     * The ids of pre-existing teams that must survive, because something outside {@code teams} and
     * {@code team_members} points at them: {@code projects.team_id} and {@code team_capacity.team_id}, closed
     * under {@code teams.parent_team_id} so an ancestor is never dropped from under a kept team. Dropping a
     * referenced team would fail its foreign key at import, and the seed carries the export's own projects
     * forward, so the reference reaches the database.
     */
    static Set<String> referencedTeams(ExportEnvelope input) {
        Set<String> keep = new HashSet<>();
        for (LinkedHashMap<String, Object> p : input.rows("projects")) {
            if (p.get("team_id") instanceof String s) {
                keep.add(s);
            }
        }
        for (LinkedHashMap<String, Object> c : input.rows("team_capacity")) {
            if (c.get("team_id") instanceof String s) {
                keep.add(s);
            }
        }
        Map<String, String> parentOf = new LinkedHashMap<>();
        for (LinkedHashMap<String, Object> t : input.rows("teams")) {
            if (t.get("id") instanceof String id && t.get("parent_team_id") instanceof String parent) {
                parentOf.put(id, parent);
            }
        }
        for (boolean grew = true; grew;) {
            grew = false;
            for (String id : new ArrayList<>(keep)) {
                String parent = parentOf.get(id);
                if (parent != null && keep.add(parent)) {
                    grew = true;
                }
            }
        }
        return keep;
    }

    private static LinkedHashMap<String, Object> teamRow(Team team, String stamp) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("id", team.id().toString());
        row.put("name", team.name());
        row.put("active", true);
        row.put("version", 0L);
        row.put("manager_id", team.managerId() == null ? null : team.managerId().toString());
        row.put("parent_team_id", team.parentId() == null ? null : team.parentId().toString());
        row.put("created_at", stamp);
        row.put("updated_at", stamp);
        return row;
    }
```

Then replace the last three lines of `prepare` (`LinkedHashMap ... data`, `data.put("users", users)`,
`return new Result(...)`) with:

```java
        // Pre-existing teams: the application's teams screen has not been used, so these are test stubs and go,
        // unless something still points at them (see referencedTeams).
        Set<String> keep = referencedTeams(input);
        List<LinkedHashMap<String, Object>> teamRows = new ArrayList<>();
        Set<String> keptIds = new HashSet<>();
        for (LinkedHashMap<String, Object> t : input.rows("teams")) {
            if (keep.contains(String.valueOf(t.get("id")))) {
                teamRows.add(t);
                keptIds.add(String.valueOf(t.get("id")));
            }
        }
        int kept = teamRows.size();
        int dropped = input.rows("teams").size() - kept;

        List<LinkedHashMap<String, Object>> memberRows = new ArrayList<>();
        Set<String> pairs = new HashSet<>();
        for (LinkedHashMap<String, Object> m : input.rows("team_members")) {
            if (keptIds.contains(String.valueOf(m.get("team_id")))) {
                memberRows.add(m);
                pairs.add(m.get("team_id") + "/" + m.get("user_id"));
            }
        }

        Set<String> usedNames = new HashSet<>();
        for (LinkedHashMap<String, Object> t : teamRows) {
            if (t.get("name") instanceof String s) {
                usedNames.add(s);
            }
        }
        SeedRandom rnd = new SeedRandom(seed);
        List<Team> derived = deriveTeams(members(users), usedNames, rnd);

        String stamp = joined.atTime(8, 0).toString();
        int departments = 0;
        for (Team team : derived) {
            if (team.department()) {
                departments++;
            }
            teamRows.add(teamRow(team, stamp));
            for (UUID member : team.memberIds()) {
                if (!pairs.add(team.id() + "/" + member)) {
                    continue; // (team_id, user_id) is UNIQUE
                }
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                row.put("id", rnd.uuid().toString());
                row.put("team_id", team.id().toString());
                row.put("user_id", member.toString());
                // ForecastRepository folds the earliest joined_at into the member's start date, so a stamp of
                // "now" would orphan the whole seeded history before it.
                row.put("joined_at", stamp);
                row.put("created_at", stamp);
                row.put("updated_at", stamp);
                memberRows.add(row);
            }
        }

        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>(input.data());
        data.put("users", users);
        data.put("teams", teamRows);
        data.put("team_members", memberRows);
        return new Result(input.withData(data), activated, promoted, departments, derived.size() - departments,
                kept, dropped);
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd server && mvn -B -pl forecast-tools -am test -Dtest='ExportPreparerTest' -DfailIfNoTests=false
```

Expected: 13 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
cd /home/user/WorkLoadHubAiForecasting
git add server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/prepare/ExportPreparer.java \
        server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/prepare/ExportPreparerTest.java
git commit -m "feat(tools): write the derived teams and memberships into the export

The stub teams a testing WorkloadHub carries are dropped, unless a project
or a team_capacity row still points at one: dropping a referenced team fails
its foreign key at import, and the seed carries the export's own projects
forward. joined_at is the requested date rather than now, because
ForecastRepository folds the earliest one into the member's start date and a
stamp of today orphans the whole seeded history."
```

---

### Task 4: The invariants, as properties

Spec section 11, the jqwik paragraph and items 9 and 10.

**Files:**
- Create: `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/prepare/ExportPreparerPropertyTest.java`

**Interfaces:**
- Consumes: `ExportPreparer.prepare`, `ExportPreparer.SEED`, and the `user(...)`/`envelope(...)` helpers of
  `ExportPreparerTest` (same package).
- Produces: nothing other tasks use.

- [ ] **Step 1: Write the failing test**

The file name ends in `PropertyTest` and the class holds **only** `@Property` methods — a class mixing `@Test`
and `@Property` has both engines overwrite the same surefire `.txt` report.

```java
package com.workloadhub.forecast.tools.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.tools.export.ExportEnvelope;
import com.workloadhub.forecast.tools.export.ExportFiles;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

/**
 * Three invariants of a prepared export. Broken, each produces a failure that is silent or far from its cause:
 * a user in no team is dropped by {@code ForecastRepository} without a word and simply never appears in a
 * forecast; an unresolved {@code parent_team_id} or a repeated {@code (team_id, user_id)} fails a foreign key
 * or a UNIQUE constraint at import, long after this code ran.
 *
 * <p>Random directories rather than one fixture, because the shapes that break these are structural — a manager
 * outside their reports' department, a department whose only member is its own head, nobody with a department
 * at all — and a hand-written fixture covers the shapes its author thought of.
 */
class ExportPreparerPropertyTest {

    private static final LocalDate JOINED = LocalDate.of(2021, 1, 4);

    private static final List<String> DEPARTMENTS =
            List.of("PTE / CT2 Calibration & Testing 2", "PTE / CT2", "PTE / SIM Simulation", "ADM / HR People", null);

    private static final List<String> TITLES =
            List.of("Skill Team Leader", "Calibration Engineer", "Simulation Engineer", null);

    /** A directory of 1 to 40 people with random departments, titles and managers, from one seed. */
    private static ExportEnvelope directory(long seed) {
        Random random = new Random(seed);
        int count = 1 + random.nextInt(40);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(new UUID(0x3000000000000000L, i + 1L));
        }
        List<LinkedHashMap<String, Object>> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // a manager earlier in the list, outside the list entirely, or none: all three occur in a real export
            UUID manager = null;
            int pick = random.nextInt(3);
            if (pick == 0 && i > 0) {
                manager = ids.get(random.nextInt(i));
            } else if (pick == 1) {
                manager = new UUID(0x9000000000000000L, i + 1L);
            }
            users.add(ExportPreparerTest.user(ids.get(i), "Person " + (i + 1), TITLES.get(random.nextInt(TITLES.size())),
                    DEPARTMENTS.get(random.nextInt(DEPARTMENTS.size())), manager, "MEMBER", random.nextBoolean()));
        }
        return ExportPreparerTest.envelope(users, new ArrayList<>(), new ArrayList<>());
    }

    @Property(tries = 300)
    void everyUserIsInATeamEveryParentResolvesAndNoMembershipRepeats(@ForAll @LongRange(min = 0, max = 100_000) long seed) {
        ExportEnvelope out = ExportPreparer.prepare(directory(seed), JOINED, ExportPreparer.SEED).envelope();

        Set<Object> teamIds = new HashSet<>();
        out.rows("teams").forEach(t -> teamIds.add(t.get("id")));

        Set<Object> placed = new HashSet<>();
        Set<String> pairs = new HashSet<>();
        for (LinkedHashMap<String, Object> m : out.rows("team_members")) {
            placed.add(m.get("user_id"));
            assertTrue(teamIds.contains(m.get("team_id")), "membership of a team that is not in the export");
            assertTrue(pairs.add(m.get("team_id") + "/" + m.get("user_id")), "(team_id, user_id) is UNIQUE");
        }
        for (LinkedHashMap<String, Object> u : out.rows("users")) {
            assertTrue(placed.contains(u.get("id")),
                    u.get("full_name") + " is in no team, so ForecastRepository would silently not count them");
        }
        for (LinkedHashMap<String, Object> t : out.rows("teams")) {
            if (t.get("parent_team_id") != null) {
                assertTrue(teamIds.contains(t.get("parent_team_id")), "parent_team_id does not resolve");
            }
        }
        assertTrue(out.rows("teams").stream().map(t -> t.get("name")).distinct().count() == out.rows("teams").size(),
                "teams.name is UNIQUE");
    }

    @Property(tries = 50)
    void twoRunsOverOneExportProduceTheSameFile(@ForAll @LongRange(min = 0, max = 100_000) long seed) {
        String first = ExportFiles.toJson(ExportPreparer.prepare(directory(seed), JOINED, ExportPreparer.SEED).envelope());
        String second = ExportFiles.toJson(ExportPreparer.prepare(directory(seed), JOINED, ExportPreparer.SEED).envelope());
        assertEquals(first, second);
    }
}
```

- [ ] **Step 2: Run it to verify it compiles and runs**

```bash
cd server && mvn -B -pl forecast-tools -am test -Dtest='ExportPreparerPropertyTest' -DfailIfNoTests=false
```

Expected: the helpers `ExportPreparerTest.user` and `ExportPreparerTest.envelope` are already package-private
static, so this compiles. It either passes — in which case Task 3's implementation is sound — or it reports a
shrunk seed. **If it fails, fix `ExportPreparer`, not the property**: each of these three is a real constraint of
the database or of `ForecastRepository`.

- [ ] **Step 3: Fix anything the property found**

If the property fails, read the shrunk seed's directory, reproduce it as a named example in
`ExportPreparerTest`, fix `ExportPreparer`, and keep both tests.

If it passes first time, note that in the commit body and move on.

- [ ] **Step 4: Run the whole module's tests**

```bash
cd server && mvn -B -pl forecast-tools -am test -Dtest='ExportPreparer*Test,DirectoryTest' -DfailIfNoTests=false
```

Expected: 0 failures.

- [ ] **Step 5: Commit**

```bash
cd /home/user/WorkLoadHubAiForecasting
git add server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/prepare/ExportPreparerPropertyTest.java \
        server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/prepare/ExportPreparer.java \
        server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/prepare/ExportPreparerTest.java
git commit -m "test(tools): hold the three invariants of a prepared export

Every user in at least one team, every parent_team_id resolving, and no
repeated (team_id, user_id). Broken, the first is silent — ForecastRepository
drops the member without a word — and the other two fail at import, far from
their cause. Random directories, because the shapes that break these are
structural and a hand-written fixture covers only the ones its author
thought of."
```

---

### Task 5: The driver verb

Spec section 3.

**Files:**
- Modify: `server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/Experiment.java` — `USAGE`
  (line 51-72), the dispatch switch (line 100-106), a new `prepare(Args)` method next to `seed(Args)`
- Modify: `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/ExperimentFlowTest.java`

**Interfaces:**
- Consumes: `ExportPreparer.prepare`, `ExportPreparer.SEED`, `ExportPreparer.Result`; `Args.onlyFile`,
  `Args.require`, `Args.flag`, `Args.date`; `Experiment.insideGitRepository`; `ExportFiles.read` / `.write`.
- Produces: the command `experiment.sh prepare <export.json> --out FILE [--joined ISO_DATE] [--force]`.

- [ ] **Step 1: Write the failing test**

Append to `ExperimentFlowTest.java`. It uses the existing private helpers `experiment(...)` and `assertOk(...)`.

```java
    /**
     * A prepared export carries the identities of real people, so it stays outside the repository, exactly as
     * real-mode seed output does. The refusal comes before anything is written.
     */
    @Test
    void prepareRefusesTheRepositoryAndNeedsAnOutput(@TempDir Path dir) throws Exception {
        Path inRepo = Path.of("target/prepared.json");
        assertEquals(2, experiment("prepare", FIXTURE.toString(), "--out", inRepo.toString()).exit());
        assertFalse(Files.exists(inRepo), "the repository guard refuses before writing anything");

        Path outside = dir.resolve("prepared.json");
        assertEquals(2, experiment("prepare", FIXTURE.toString()).exit(), "--out is required");
        assertEquals(2, experiment("prepare", "--out", outside.toString()).exit(), "the export to prepare is required");
        assertEquals(2, experiment("prepare", FIXTURE.toString(), "--out", outside.toString(),
                "--joined", "last-tuesday").exit(), "--joined must be an ISO date");
        assertFalse(Files.exists(outside));
    }

    @Test
    void prepareWritesAnExportEveryUserCanBeCountedIn(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("prepared.json");
        assertOk(experiment("prepare", FIXTURE.toString(), "--out", out.toString(), "--joined", "2021-01-04"),
                "department teams");
        String json = Files.readString(out);
        assertTrue(json.contains("\"active\":true"));
        assertFalse(json.contains("\"deactivated_at\":\"2026"), json);
        assertTrue(json.contains("2021-01-04T08:00"), "joined_at is the requested date");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd server && mvn -B -pl forecast-tools -am test -Dtest='ExperimentFlowTest#prepareRefusesTheRepositoryAndNeedsAnOutput+prepareWritesAnExportEveryUserCanBeCountedIn' -DfailIfNoTests=false
```

Expected: FAIL — exit 2 with `unknown command 'prepare'` is returned for every case, so the second test fails on
`assertOk`. If no container engine is available the class fails to start its PostgreSQL container instead; in
that case skip to Step 3 and rely on Task 6's note.

- [ ] **Step 3: Add the verb**

In `Experiment.java`, add to `USAGE` between the `seed` and `fixture` blocks:

```
              prepare  <export.json> --out FILE [--joined ISO_DATE] [--force]
                       Rewrite a real export so the forecast can count its people: every user active,
                       and teams and memberships derived from department and manager_id. Transitional —
                       delete it once WorkloadHub populates teams itself. Refuses to write inside a git
                       repository without --force: its output holds personal data. --joined stamps
                       team_members.joined_at and defaults to five years ago.
```

Add to the dispatch switch, after the `seed` case:

```java
                case "prepare" -> prepare(Args.parse(rest, Set.of("out", "joined"), Set.of("force")));
```

Add the method after `seed(Args)`, and add the imports
`com.workloadhub.forecast.tools.prepare.ExportPreparer` and `java.time.LocalDate` (the latter is already there):

```java
    /**
     * Rewrites a real export so its people can be counted. Transitional: see {@link ExportPreparer}. The seed
     * is a constant rather than an option, so two runs over one export give byte-identical output.
     */
    private static int prepare(Args args) throws Exception {
        Path in = args.onlyFile("the export to prepare");
        Path out = Path.of(args.require("out"));
        if (!args.flag("force") && insideGitRepository(out)) {
            System.err.println("A prepared export holds personal data; write it outside the repository or pass --force");
            return 2;
        }
        LocalDate joined = args.date("joined") == null ? LocalDate.now().minusYears(5) : args.date("joined");
        ExportPreparer.Result result = ExportPreparer.prepare(ExportFiles.read(in), joined, ExportPreparer.SEED);
        ExportFiles.write(out, result.envelope());
        System.out.printf("Wrote %s: %d users activated, %d promoted to TEAM_LEADER, %d department teams, "
                + "%d manager teams, %d existing teams kept, %d dropped, joined %s%n",
                out, result.usersActivated(), result.managersPromoted(), result.departmentTeams(),
                result.managerTeams(), result.teamsKept(), result.teamsDropped(), joined);
        return 0;
    }
```

Also update the class javadoc: *"the five things the owner does from a terminal"* becomes *"the six things"*, and
the `// ---- the five commands ---` comment becomes `// ---- the six commands ---`.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd server && mvn -B -pl forecast-tools -am test -Dtest='ExperimentFlowTest' -DfailIfNoTests=false
```

Expected: PASS. Without a container engine this class cannot run — say so plainly and do not claim it passed.

- [ ] **Step 5: Commit**

```bash
cd /home/user/WorkLoadHubAiForecasting
git add server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/Experiment.java \
        server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/ExperimentFlowTest.java
git commit -m "feat(tools): add the prepare verb to the experiment driver

experiment.sh prepare <export.json> --out FILE rewrites a real export so the
forecast can count its people. It carries the same repository guard as
real-mode seed output, for the same reason: its output holds personal data."
```

---

### Task 6: End to end, and the documentation

Spec sections 11 (the `ExperimentFlowTest` paragraph) and 12.

**Files:**
- Create: `server/forecast-tools/src/test/resources/fixtures/raw-export.json`
- Modify: `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/ExperimentFlowTest.java`
- Modify: `server/README.md`, `CLAUDE.md`, `docs/backlog.md`

**Interfaces:**
- Consumes: everything above; `DatabaseTestSupport.postgres()` and the existing `connection(...)` /
  `concat(...)` helpers in `ExperimentFlowTest`.
- Produces: nothing later depends on.

- [ ] **Step 1: Build the raw fixture**

`raw-export.json` is `mini-export.json` in the shape a real export arrives in: the two users inactive and
deactivated, no teams, no memberships, and none of the work tables (the seed writes those). The reference tables
stay, because real mode refuses an export missing a task status or type.

```bash
cd /home/user/WorkLoadHubAiForecasting/server/forecast-tools/src/test/resources/fixtures
python3 - <<'PY'
import json, collections
src = json.load(open("mini-export.json"), object_pairs_hook=collections.OrderedDict)
data = src["data"]
for u in data["users"]:
    u["active"] = False
    u["deactivated_at"] = "2026-01-01T08:00:00"
for table in ("teams", "team_members", "projects", "tasks", "task_history", "time_logs", "user_capacity"):
    if table in data:
        data[table] = []
open("raw-export.json", "w", encoding="utf-8").write(json.dumps(src, indent=2, ensure_ascii=False) + "\n")
PY
head -20 raw-export.json
```

- [ ] **Step 2: Write the failing test**

Append to `ExperimentFlowTest.java`. Add `RAW` next to the existing `FIXTURE` constant:

```java
    private static final Path RAW = Path.of("src/test/resources/fixtures/raw-export.json");
```

and these imports (`javax.sql.DataSource` is already imported):

```java
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.ForecastRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
```

`ForecastRepository` is public and `spring-boot-starter-jdbc` reaches this module transitively through
`forecast-core`, so the assertion uses the module's own loader rather than restating its rule in SQL —
`forecast-core`'s own `ForecastRepositoryTest:106` and `SeededData:44` build it the same way.

```java
    /**
     * The whole chain the {@code prepare} verb exists to unblock: a real export in which nobody is active and
     * no team exists goes in, and {@code ForecastRepository} counts members at the other end. Straight to
     * {@code seed} it would count none — every user inactive and in no team fails two of the three conditions
     * at {@code ForecastRepository:66} — which is the defect the design of 2026-09-19 is about.
     */
    @Test
    void prepareThenSeedThenImportProducesCountableMembers(@TempDir Path dir) throws Exception {
        DataSource ds = DatabaseTestSupport.postgres();
        String[] db = connection(ds);
        Path prepared = dir.resolve("prepared.json");
        Path seeded = dir.resolve("seeded.json");

        assertOk(experiment("prepare", RAW.toString(), "--out", prepared.toString(), "--joined", "2024-01-01"),
                "department teams");
        assertOk(experiment("seed", "--export", prepared.toString(), "--weeks", "8", "--end", "2026-09-06",
                "--out", seeded.toString()), "real identities");
        assertOk(experiment(concat(db, "init-db", "--force")), "Created");
        assertOk(experiment(concat(db, "import", prepared.toString())), "Imported");
        assertOk(experiment(concat(db, "import", seeded.toString())), "Imported");

        ForecastData data = new ForecastRepository(JdbcClient.create(ds)).loadAll();
        assertEquals(2, data.members().size(), "prepare exists so that this is not zero");
        assertEquals(2, data.teams().size(), "the CT2 department team and Lead One's manager team");
        assertTrue(data.members().stream().allMatch(m -> m.joined().equals(java.time.LocalDate.of(2024, 1, 1))),
                "joined_at reaches the member's start date");
        assertTrue(data.members().stream().allMatch(m -> m.left() == null), "deactivated_at was cleared");
    }
```

`MemberRow` is `record MemberRow(UUID id, String fullName, String email, String role, String jobTitle,
List<UUID> teamIds, UUID primaryTeamId, LocalDate joined, LocalDate left)`, so `joined()` and `left()` are the
accessors used above.

- [ ] **Step 3: Run it**

```bash
cd server && mvn -B -pl forecast-tools -am test -Dtest='ExperimentFlowTest' -DfailIfNoTests=false
```

Expected: PASS. **Without a container engine this cannot run.** Check with `docker info`; if it fails, say so
plainly in the commit body and in the report, and do not claim the test passed.

- [ ] **Step 4: Update `server/README.md`**

Add `prepare` to the driver's command list, and a section after the seed's:

```markdown
### Running the forecast over a real export

**Transitional.** WorkloadHub is still in its testing phase: almost no user is `active`, and its teams screen
has not been used, so a real export carries a handful of stub teams instead of the company's structure. Two of
the three conditions the module counts a member by therefore fail, and a forecast over that export covers
nobody. `prepare` corrects the export file before the seed sees it; delete the step once WorkloadHub populates
`teams` and `team_members` itself.

```bash
bash scripts/postgres.sh up
bash server/tools/experiment.sh init-db --force
bash server/tools/experiment.sh prepare ~/export.json --out ~/prepared.json
bash server/tools/experiment.sh import ~/prepared.json
bash server/tools/experiment.sh seed --export ~/prepared.json --out ~/seeded.json
bash server/tools/experiment.sh import ~/seeded.json
```

`prepare` sets every user active, clears `deactivated_at`, promotes anyone with direct reports to
`TEAM_LEADER`, and derives one parentless team per department plus one child team per manager. It writes no
database and reads no credentials. Its output holds real names and stays outside the repository, like the
seed's.

A note on what it does not fix: real mode still gives about a tenth of people a late start date and a few a
leaving date inside the window, and shapes their generated work accordingly, while leaving `users.active`
alone. A handful of people will show work that begins late or stops early. That is the seed's existing
behaviour, not a fault of the prepared export.
```

- [ ] **Step 5: Update `CLAUDE.md` and `docs/backlog.md`**

In `CLAUDE.md`, add `prepare` to the `forecast-tools` line of the Layout section — the driver's verb list
becomes `init-db, import, export, seed, fixture, prepare` — and append a paragraph to "Where the project
stands" recording this change and the spec's path.

In `docs/backlog.md`, under "Java migration", add two items:

```markdown
- Delete `experiment.sh prepare` and `ExportPreparer` once WorkloadHub populates `teams` and `team_members`
  itself. It exists only because a WorkloadHub in testing has almost no active user and no real team structure
  (`docs/superpowers/specs/2026-09-19-real-export-preparation-design.md`). Left in place it would be harmless —
  it writes a file, not a database — but its derived structure is a guess, and once the real structure exists
  the guess is worse than nothing.
- Real mode gives ~10% of people a late join date and ~3% a leaving date inside the window
  (`Directory.LATE_JOIN_SHARE`, `LEAVE_SHARE`) and shapes their generated work by them, but never writes them
  back to `users.active` / `deactivated_at`, because `Directory.derive` returns before the row-rewriting step
  on the real path. After a real seed a few people show work that starts late or stops early while the database
  calls them active. Decide whether real mode should stop doing this or should write the dates through.
```

- [ ] **Step 6: Commit**

```bash
cd /home/user/WorkLoadHubAiForecasting
git add server/forecast-tools/src/test/resources/fixtures/raw-export.json \
        server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/ExperimentFlowTest.java \
        server/README.md CLAUDE.md docs/backlog.md
git commit -m "test(tools): drive prepare, seed and import end to end, and document the step

A real export in which nobody is active and no team exists goes in, and the
three conditions ForecastRepository counts a member by hold at the other
end. The README gains the four commands in order, and the backlog gains the
deletion this step is waiting for and the late-join behaviour real mode
already had."
```

---

## Closing: review, fix wave, gate

- [ ] **Whole-branch review.** Review every commit of this plan together against the spec, as the standing
  workflow requires. Look in particular for: a `Result` field that stopped matching what the driver prints; a
  name minted twice; `prepare` mutating the input envelope's own row maps rather than copies (it must not —
  `ExportEnvelope.data()` hands out the live map); and any claim in `server/README.md` or `CLAUDE.md` that the
  code does not support.
- [ ] **One fix wave.** Fold every finding into a single commit.
- [ ] **Hand the gate to the owner.** This container has Maven 3.9.11 and Java 21 but **no container engine**,
  so `ExperimentFlowTest`, every Testcontainers test in `forecast-core`, and therefore
  `bash scripts/check.sh` cannot run here. Report which tests were executed and which were not, and ask the
  owner to run the gate in the development container:

  ```bash
  bash scripts/devbox.sh shell
  bash scripts/check.sh
  ```

  Read the result from `forecast-core/target/surefire-reports/TEST-*.xml` and
  `forecast-tools/target/surefire-reports/TEST-*.xml`, never by summing the `.txt` files. The last recorded
  gate was 466 tests, 0 failures, 0 errors, 1 skipped, plus the `ui` step.
- [ ] **Do not push `main`.** `main` is fast-forwarded by `scripts/release.sh`, which the owner runs.

---

## Self-review notes

Spec coverage, section by section:

| Spec section | Task |
|---|---|
| 3 — the command, `--out`, `--joined`, `--force`, exit codes | 5 |
| 4 — `active`, `deactivated_at`, all users | 1 |
| 4.1 — managers to `TEAM_LEADER`, never `SKILL_TEAM_LEADER` | 1 |
| 5 — department teams, manager teams, `Unassigned`, names, heads | 2 |
| 5.1 — dropping stub teams, keeping referenced ones and their ancestors | 3 |
| 6 — `team_members` rows, `joined_at`, the UNIQUE pair | 3 |
| 7 — other tables untouched, no database written | 1 (test), 5 (the verb writes a file only) |
| 8 — the inherited late-join behaviour, recorded not changed | 6 (backlog, README) |
| 9 — `ExportPreparer`, `Result`, `prepare`, the fixed `SEED` | 1 |
| 10 — encoding, nothing new needed | none: `ExportFiles` already does it |
| 11 — the ten example tests, the property, the end-to-end case, the guard test | 1, 2, 3, 4, 5, 6 |
| 12 — README, `CLAUDE.md`, backlog | 6 |
| 13 — risks | recorded in the spec; the README carries the "transitional" wording |
