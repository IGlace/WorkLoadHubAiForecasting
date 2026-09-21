# Teams come from the hierarchy, not the `teams` table

Date: 2026-09-21
Status: **implemented, landed on `dev` on 2026-09-21**, including the fix wave of the branch review
and the **second revision of the same day (section 16)**, which moves the role from `users.role` to the
job title. Read section 16 with section 2: it amends rulings 1, 2, 3 and 9, and every other ruling stands.

Supersedes `2026-09-19-real-export-preparation-design.md` in part: that document's sections 5 and 6,
the derivation of department teams and manager teams, are withdrawn. Its sections 4 and 4.1, the
activation and the role promotion, survive with one change (section 9 below). Amends
`2026-09-17-personal-leaves-capacity-and-seed-scope-design.md` section 7.1 and
`2026-09-11-host-integration-design.md` section 3.2. Everything about the model, the target, the
windows, capacity and the narration contract is untouched.

## 1. Why

The forecast was built on a misreading of the WorkloadHub schema. It treated `teams` and
`team_members` as the company's organisational structure. They are not.

`teams` holds **project teams**: an ad-hoc group of people working together on one project. Four
people on a project are a team in that sense and in no other. It says nothing about who reports to
whom, and a person can be in several such teams or none.

The company's structure lives in one column, `users.manager_id`, a self-reference to `users.id`
(`server/forecast-tools/src/main/resources/schema/workloadhub-postgresql.sql:398-417`). That column
alone says who manages whom, and every team this forecast serves is derived from it.

The misreading is load-bearing in five places.

**A member is forecastable only if they appear in `team_members`.**
`ForecastRepository.loadAll` (`server/forecast-core/src/main/java/com/workloadhub/forecast/data/ForecastRepository.java:62-75`)
requires three things at once — `active`, a counted role, and at least one team membership:

```java
if (!rs.getBoolean("active") || !COUNTED_ROLES.contains(rs.getString("role")) || teamIds.isEmpty()) {
    return null;
}
```

On the owner's real export (their own measurement, recorded 2026-09-19) `team_members` holds
**3 rows against 264 users**. The third condition fails for 261 of them, so a real run forecasts
almost nobody.

**A whole preparation step exists to paper over this.** `ExportPreparer`
(`server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/prepare/ExportPreparer.java`)
was designed on 2026-09-19 to invent department teams and manager teams from `department` and
`manager_id` and write them into the export before the seed runs. That invention is exactly what
reading the hierarchy directly makes unnecessary. `docs/backlog.md` already carried an entry to
delete it once real teams arrived; the truth is that the teams it was waiting for are not the
teams it needed.

**The primary-team rule is an org-chart assumption.** `ForecastRepository.java:68-71` picks a
member's primary team as the first of their teams that has a parent, on the theory that a
parentless team is a department and a child team is a manager's team. Both halves are false of
project teams.

**Projects are attributed through teams.** `ForecastData.projectIdsOfTeamAndParent`
(`server/forecast-core/src/main/java/com/workloadhub/forecast/data/ForecastData.java:189-208`)
collects the projects owned by a team and its parent, on the same false theory.

**The vocabulary is wrong where Copilot reads it.**
`server/forecast-core/src/main/resources/skills/whf-domain/SKILL.md:9` states it outright: "A
**department** is a team without a manager; a **team** has a manager (the team leader)". That
sentence is in the narrator's system message on every run.

## 2. The rulings

Settled with the owner on 2026-09-21.

1. **A team is a team leader plus their direct reports.** Not a subtree. A user whose *effective*
   role (section 16) is
   `TEAM_LEADER` and who has at least one counted direct report has a team; its members are that leader and
   the users whose `manager_id` names them. A manager of any other role — a skill team leader, an admin —
   keys no team, which section 4 explains and enforces.
2. **The leader is counted**, because team leaders do technical work and log hours. **A skill team
   leader is not counted**, because they do not.
3. **A skill team leader is the manager of a team leader.** To run a forecast they choose one team
   leader beneath them and run that leader's team. They never run everything below them at once.
   In practice they run rarely, standing in for an absent leader.
4. **Counted roles stay `MEMBER` and `TEAM_LEADER`.** No change to `COUNTED_ROLES`.
5. **The name `team_id` is kept everywhere.** API parameters, REST paths, `forecast_runs.team_id`,
   `forecast_current_days.team_id`, the facts contract. Only the meaning changes: it holds the
   leader's user id.
6. **The module stops reading `teams` and `team_members` altogether.**
7. **A team's projects are the projects of its members' tasks**, not of any team row.
8. **The joined date comes from the member's first activity**, since `team_members.joined_at` is
   no longer read.
9. **`prepare` keeps activating users and promoting managers and loses all team derivation.** A
   manager whose own reports include another manager becomes `SKILL_TEAM_LEADER`; every other
   manager becomes `TEAM_LEADER`. *(Superseded by section 16: the role comes from the job title, and
   `prepare` decides nothing of its own — it writes the module's own answer back into the file.)*
10. **The synthetic seed still writes `teams` and `team_members`, as genuine project teams**: one
    per project, holding the people who work on it, with `projects.team_id` naming it.

## 3. No migration

`forecast_runs.team_id` and `forecast_current_days.team_id` are already `uuid` and carry no foreign
key (`server/forecast-core/src/main/resources/db/forecast/postgresql/V1__forecast_tables.sql:3,47`).
They keep their names, their types, their indexes and `forecast_current_days`' primary key
`(team_id, user_id, day)`. Only the value changes, from a team row's id to a leader's user id.

This is the whole reason ruling 5 is worth having. A rename would have cost a migration, a change
to every host that has stored a run id, and a rewrite of the REST surface, for a word.

One consequence to accept knowingly: **runs made before this change and runs made after it cannot
be told apart by the column**. A `forecast_runs` row whose `team_id` names a deleted team row and
one whose `team_id` names a user look identical. The forecast databases in play are the local
PostgreSQL of `scripts/postgres.sh` and the showcase's, both of which are rebuilt from the seed, so
nothing of value is being reinterpreted. Nothing in production has ever run.

A team leader who is themselves someone's direct report now appears in two teams: their own, under
their own id, and their manager's, under their manager's id. `forecast_current_days`' primary key
keys them separately, which is correct — the two runs are two different questions.

## 4. What the module reads

`ForecastRepository.loadAll` loses both team queries
and gains `manager_id` on the users query:

```java
jdbc.sql("SELECT id, full_name, email, username, role, job_title, manager_id, active, deactivated_at FROM users")
```

The counted-member test becomes `active` and a counted role. The primary-team selection goes.

`MemberRow` (`server/forecast-core/src/main/java/com/workloadhub/forecast/data/rows/MemberRow.java`)
drops `teamIds` and `primaryTeamId` and gains `managerId`:

```java
public record MemberRow(UUID id, String fullName, String email, String role, String jobTitle,
        UUID managerId, LocalDate joined, LocalDate left)
```

`department` was carried here at first and is not: nothing in the module reads it, and the one place that
would have (the `team` fact's name) does not either — see section 8.

`MemberRow.employedOn` goes with them. It is dead code: the only `employedOn` anyone calls is the
seed's own `Person.employedOn`.

`TeamRow` is deleted. `ProjectRow` drops `teamId`, which nothing reads once section 6 lands, and
`ForecastData` drops `teams` and `teamById`.

`ForecastData.membersOfTeam(UUID teamId)` keeps its name and becomes the leader plus their direct
reports:

```java
public List<MemberRow> membersOfTeam(UUID teamId) {
    return members.stream()
            .filter(m -> m.id().equals(teamId) || teamId.equals(m.managerId()))
            .toList();
}
```

`members` is already filtered to counted, active users, so a `SKILL_TEAM_LEADER` report is absent
by construction and needs no second test. A leader whose own role is not counted is absent from
their own team's member list while still being its key, which is exactly ruling 2.

`DefaultForecastService.requireTeam` stops asking `SELECT id FROM teams WHERE id = ?` and asks instead
whether that user is a `TEAM_LEADER` with at least one counted, active direct report:

```sql
SELECT 1 FROM users lead JOIN users r ON r.manager_id = lead.id
WHERE lead.id = ? AND lead.role = 'TEAM_LEADER'
  AND r.active = TRUE AND r.role IN ('MEMBER', 'TEAM_LEADER')
```

**The role of the key is part of the test, not decoration.** A `SKILL_TEAM_LEADER` has direct reports too,
and a run keyed by one would forecast every leader beneath them at once, which ruling 3 forbids; on the seed
fixture 9 of 17 users with counted reports are a skill team leader or an admin. Without the role clause that
run is reachable — an `ADMIN` short-circuits the host's own check, and a host that forgets to check at all
falls back on this one. Every list of teams applies the same clause: `Directory.teams()` in the showcase, the
two `ForecastAccess.leadsSomeone`, `HostExample`, and the test helpers.

One consequence to accept: a counted user whose manager is an `ADMIN` or a `CENTER_MANAGER` is in no team and
appears in no run, because `prepare` must leave those two roles alone. It is asserted in
`ExportPreparerPropertyTest` so that it stays a known shape rather than a surprise.

The code `TEAM_NOT_FOUND` and the shape of its message stay, so hosts and the showcase keep the
error they already handle. `ForecastRunner.forTeam` keeps its own `TEAM_NOT_FOUND` for the case
where the team empties after the leaving-date filter (`ForecastRunner.java:166-168`).

## 5. The joined date

`team_members.joined_at` was the only source of a member's joined date
(`ForecastRepository.java:54-57`). It is read twice in `forecast-core`: `tenure_weeks`
(`FeatureBuilder.java:93`) and `startIndex` (`FeatureBuilder.java:116-125`).

It becomes the member's **first activity**: the earlier of their earliest `time_logs.log_date` and
the earliest `task_history` row they are the actor of (`task_history.user_id`). Both are already
loaded by `loadAll`, so this costs no query — but it costs an **ordering change**. Members can only
be finished after the logs and the transitions are read. The load order becomes: reference data,
users into a provisional list, projects, tasks, transitions, time logs, then fold the first activity
date into each member with the existing `MemberRow.withJoined`.

The second signal is "this person acted on a task", not "a task was assigned to them". That is a
deliberate narrowing of what this design first proposed, for two reasons. Resolving who an
`assignee` transition points at needs `Lifecycle.Resolver`, which reads names, emails and usernames
out of `ForecastData` — the object the repository is building — so using it here would invert the
dependency. And being assigned something is not evidence that the person had started: acting on a
task is. `task_history.user_id` is a plain uuid and needs no resolution.

Using real activity rather than a row's timestamp is the owner's choice and the better one here:
`users.created_at` on a real export records when the row was imported, not when the person started.

**A member with no activity at all** has no measurable joined date. Following the owner's
2026-09-16 ruling — a blank where there is nothing to measure, never an invented sentinel — `joined`
stays null, `tenure_weeks` is left blank for that member, and `startIndex` falls back to the first
loaded week. Both sites dereference the date today and must be taught the null; this is a real edit,
not a defensive one.

Such a member has no history to learn from either way. They are still counted, still get capacity
and still appear in the run, which is what a leader needs to see about a new joiner.

## 6. A team's projects

`projectIdsOfTeamAndParent(UUID)` becomes `projectIdsOfTeam(UUID)`: the distinct `project_id` of the
tasks assigned to that team's members, over the whole loaded history. The
`ConcurrentHashMap` memoisation at `ForecastData.java:52` stays; only the computation changes.

This is strictly better than what it replaces, and not only because the old version was built on a
false idea. It is the only definition that works on the real export at all: with three team rows and
no `projects.team_id` pointing anywhere useful, the old one returns nothing, so
`team_backlog_unassigned_hrs`, `proj_active` and `proj_planning` would all be empty. Tasks, by
contrast, are the one thing a real WorkloadHub is full of.

It also changes what the number means, slightly and for the better. "The projects this team works
on" is now observed rather than declared, so a team that has drifted onto another department's
project is counted where it actually works.

`team_backlog_unassigned_hrs` keeps its definition over that set: estimated hours of tasks in those
projects, created by the week's end, neither assigned nor finished.

## 7. Features

`Features.CATEGORICAL` keeps all four columns, `team_id` among them. Its source becomes
`FeatureBuilder.teamOf`: **the team the member does their work in** — the one they lead if they lead one,
otherwise their manager's.

That distinction is not pedantry. A run's members are a leader and their reports, and the leader is in two
teams. Keying their row on `managerId` like everyone else would give the leader team columns describing the
*group of leaders above them* — a different and much larger `team_backlog_unassigned_hrs`, `proj_active` and
`proj_planning` than every other row of the same run carries, for no reason a model can learn.

A member who neither leads a team nor reports to anyone has no `team_id`. Per the 2026-09-16 ruling it is
left blank rather than given a sentinel, exactly as the other blank-able columns are; such a member is in no
team and appears in no run.

The column is still worth having. It was always a proxy for "who this person works alongside", and
under the hierarchy it is a truer one: the manager id groups the people who actually share a
backlog and a leader, where the primary project team grouped whoever happened to share a project.

`TeamContext` (`FeatureBuilder.java:333-386`) is unchanged in shape and recomputed over the project
set of section 6.

## 8. Facts and narration

The `team` node (`facts/FactsBuilder.java:129-145`) is built from the leader instead of a `TeamRow`:

| field | now |
|---|---|
| `id` | the leader's user id, unchanged in type and position |
| `name` | the leader's full name |
| `manager_id` | the leader's user id |
| `parent_team_id` | the leader's own `manager_id` |
| `totals` | unchanged |

`parent_team_id` becomes genuinely meaningful for the first time: it names the skill team leader
above this team, which is the person a rebalancing suggestion escalates to.

The field names do not change, so `contract.schema.json`, `NarrativeContract` and `NumberVerifier`
need no edit. Real names in facts are already allowed (owner ruling, `CLAUDE.md`).

Four skill files carry the old vocabulary and are rewritten, shortest first:

- `whf-domain/SKILL.md:9` — the definition itself. It becomes: a **team** is a team leader and the
  people who report to them directly; the leader is counted like any member because they do
  technical work; a **skill team leader** manages team leaders, is not counted, and is who a team's
  own risks are escalated to.
- `whf-domain/SKILL.md:11` — "those are the team's backlog" stays true and stays.
- `whf-rebalancing-advice/SKILL.md:12` — "recommend that the department lead be told" becomes the
  skill team leader named by `team.parent_team_id`.
- `whf-report-style/SKILL.md:3,8,15` and `whf-pattern-discovery/SKILL.md:17` — "team leader" and
  "the team's other members" are both still correct and stay; they are listed here only so the
  review does not have to rediscover that they were checked.

`SkillTextsTest` pins this vocabulary to the Java facts and moves with it.

## 9. `prepare`

The verb survives, reduced. WorkloadHub is still in its testing phase, only 6 of 264 users are
`active`, and the owner has confirmed that flag carries no meaning there, so the export still needs
one pass before it can be forecast.

**Kept**, from the 2026-09-19 design sections 4 and 4.1: every user `active = true` with
`deactivated_at` cleared, both halves, for the reason that document gives — a user flipped active
while still carrying a leaving date is counted by the repository and then dropped by the run.

**Changed.** Role promotion becomes two-tier, because the hierarchy now has two tiers that matter:

- a manager whose direct reports include at least one other manager becomes `SKILL_TEAM_LEADER`;
- any other manager becomes `TEAM_LEADER`;
- `ADMIN` and `CENTER_MANAGER` keep their roles, as before, because `ProjectPlanner.fallbackOwner`
  looks for exactly those two;
- a user who manages nobody is untouched.

This reverses that design's section 4.1, which deliberately promoted nobody to `SKILL_TEAM_LEADER`
because it would drop people out of the forecast. Dropping them is now the intent: ruling 2 says a
skill team leader does no technical work and is not a forecast subject. The people affected are the
managers of managers, and they remain visible in their own right as the runners and escalation
points of the teams beneath them.

**Removed**: `deriveTeams`, `referencedTeams`, `teamRow`, the `departmentTeams`, `managerTeams`,
`teamsKept` and `teamsDropped` counters, and the `--joined` flag, which existed only to stamp
`team_members.joined_at` (2026-09-19 section 6) and now has nothing to stamp.
`Result` shrinks to `(envelope, usersActivated, teamLeaders, skillTeamLeaders)`.

The class should fall well under half its current 319 lines, at which point the argument in that
design's section 9 for keeping it whole holds more easily than before.

`Directory.uniqueName` was made public for this step alone and goes back to package-private.
`Directory.deptCode` stays public: the seed still uses it.

The git-repository refusal and `--out` stay: the output still holds personal data and still must
live outside the repository.

## 10. The seed

The seed's derivation of the hierarchy from `manager_id`, `department` and `job_title`
(`Directory.derive` steps 1 to 3, `Directory.java:70-137`) was always right and becomes the whole
structure. What goes is the step that expressed it as team rows.

**`seed/Team.java` splits into two records.** A `Department` (code, label, head, its people) and a
`LeaderTeam` (the manager, their direct reports). Neither is a `teams` row.

**`Directory`** loses steps 4, 5 and 6's team synthesis (`Directory.java:147-231`) and the helpers
`applicationTeams` and `existingTeams`. `Result` returns people, departments and leader teams. The
real-mode early return at `Directory.java:140-145`, which existed to leave the export's own teams
alone, goes with them: real mode and synthetic mode now derive the same structure from the same
columns, and the difference between them is only which tables get written.

Role assignment in step 3 follows section 9's two-tier rule, so the seed and `prepare` agree. Today
they do not: `Directory.java:119-137` makes a department's head a `SKILL_TEAM_LEADER` by job title,
which is a different question from managing a manager. Job title keeps a say — a head whose title
says "skill team leader" is one — but managing a manager is sufficient on its own.

**`Rhythm`** (`Rhythm.java:40-56`) loses the "a manager team beats a department team" tie-break,
which existed only because a person was in two team rows. A person is in exactly one leader team,
so `teamOf` is a plain lookup and event weeks are dealt per leader team. The `TEAM_LEADER` halving
at `Rhythm.java:62-64` stays, and now fires for every leader rather than only for those the export
already called one.

**`ProjectPlanner`** creates projects per department instead of per department team, owned by the
department head with `fallbackOwner` unchanged. It then mints each project's **project team**: a
`teams` row with `parent_team_id` null and `manager_id` the project's owner, and `team_members` rows
for the people who actually take that project's work, with `projects.team_id` naming it. This is now
the only place team rows are written, and what they mean is what the application means by them.

Drawing a project team from the department's own people, plus an occasional person from another
department, keeps the tables honest without inventing a second hierarchy.

**`SeedGenerator`** threads the new types through `ProjectPlanner.plan`, `new Rhythm(...)` and
`WorkQueue.run` (`SeedGenerator.java:236-239`); `WorkQueue` uses a team only to choose who takes a
project's work, so it takes the leader team. The team rows written at `SeedGenerator.java:265-266`
come from `ProjectPlanner` rather than `Directory`.

**Real mode is unchanged in what it writes**: `REAL_MODE_TABLES` (`SeedGenerator.java:22`), the five work
tables. It does invent department projects, as it always did, but mints **no project team** for them —
`projects.team_id` is nullable, and a team id minted there would be a dangling foreign key at import, since
no `teams` row is written. `teams` and `team_members` in a real database stay exactly as WorkloadHub left
them.

A project's `deptCode` is normalised so that a blank and a null are one thing. `Directory.deptCode` answers
null for a blank department while a `Department`'s own code spells the same thing as `""`; compared raw,
`"".equals(null)` is false and every person with no department at all matches no project, so they get no
task, no log and no history. A project that belongs to no department of ours — one a real export carried
whose owner is outside the directory — says so with `openToAll` instead of with a null code.

`HostExample` (`examples/HostExample.java:160-183`) stops querying `teams` and `team_members` and
lists teams as the users who have counted direct reports, with the leader themselves as the
requester.

## 11. `forecast-web`

`host/ForecastAccess` keeps its shape, its `Decision` record and its reason strings; the three
predicates are rewritten against `users` alone:

- `manages(userId, teamId)` — `teamId` equals `userId`, and that user has at least one counted
  direct report.
- `managesParentOf(userId, teamId)` — the user identified by `teamId` has `manager_id = userId`.
  This is ruling 3 expressed in one query: a skill team leader may run the team of a leader who
  reports to them, one at a time, and no other.
- `memberOf(userId, teamId)` — `userId` equals `teamId`, or that user's `manager_id` is `teamId`.

The role matrix of `2026-09-11-host-integration-design.md` section 3.2 keeps its shape (ruling 7):
`ADMIN` runs and views any team; `TEAM_LEADER` runs and views their own; `SKILL_TEAM_LEADER` runs
and views a team whose leader reports to them; `MEMBER` views their own; `VIEWER` and
`CENTER_MANAGER` view any and run none. Only what a team is has changed beneath it.

`host/Directory.teams()`, `team(id)` and `membersOf(id)` read `users`: a team's id is its leader's
user id, its name the leader's name, its parent the leader's own manager, its member count the
leader plus their counted reports.

`api/DirectoryController.users()` recomputes the `manages`, `manages-parent` and `member` relations
from the hierarchy instead of from team rows and memberships. `api/Views` keeps every record name
and field name, so the front end's types need no structural change.

**UI.** `pages/TeamsPage.tsx`, `pages/TeamPage.tsx` and `pages/PermissionsPage.tsx` keep their
layout; what changes is the wording and the fact that a team is named by its leader.
`lib/permissions.ts`'s `ROLE_MATRIX` strings are rewritten to describe the hierarchy. `types.ts`,
`lib/endpoints.ts` and `pages/DocsPage.tsx`'s explanatory text follow. The acting-user flow,
`state/acting.tsx` and `components/ActingUserPicker.tsx` are untouched.

## 12. What does not change

The model, its target and its features apart from `team_id`'s source. The windows, the capacity
rule, `personal_leaves`, the holidays. The backtest, the intervals, `mae`. `accuracy(teamId, from,
to)` and its three scopes. The narration contract, the tool definitions, the verifier, the two
languages. Every public signature in `api/ForecastService`. `absences`, `user_capacity` and
`team_capacity` stay unread, as ruled on 2026-09-17.

## 13. Testing

Test-driven, with jqwik properties for the structural invariants, per the standing rule.

Properties worth holding:

- Every counted member of a run is the team's leader or a direct report of them, and no one else.
- Every team key resolves to a user with at least one counted, active direct report.
- No `SKILL_TEAM_LEADER` is ever a forecast subject, in any team.
- A leader who reports to someone appears in exactly two teams: their own and their manager's.
- Over a generated directory, `prepare` leaves every user active, no manager as `MEMBER`, and no
  manager of a manager as `TEAM_LEADER`.
- A member's `joined` is never later than their first logged hour or first assignment.

Suites that move with the change: in `forecast-core`, `data/ForecastRepositoryTest`,
`run/ForecastRunnerTest`, `features/FeatureBuilderTest`, `features/FeaturesTest`,
`facts/FactsBuilderTest`, `service/DefaultForecastServiceTest`, `web/ForecastControllerTest`,
`store/JdbcRunStoreTest`, `capacity/CapacityRuleTest`, the two sample-host tests, and the shared
builders under `testing/` (`TestData`, `SyntheticMatrix`, `SeededFacts`) that all of them lean on.
In `forecast-tools`, `seed/DirectoryTest`, `seed/ProjectPlannerTest`, `seed/SeedGeneratorTest`,
`seed/WorkFamilyPropertyTest`, `prepare/ExportPreparerTest`, `prepare/ExportPreparerPropertyTest`
and `ExperimentFlowTest`. In `forecast-web`, `host/ForecastAccessTest`, `host/HostForecastFacadeTest`,
`ForecastWebIntegrationTest` and `SeededUsers`. In the UI, `lib/permissions.test.ts` and
`lib/endpoints.test.ts`.

The committed fixtures under `forecast-core/src/test/resources/fixtures/` and
`forecast-tools/src/test/resources/fixtures/` are regenerated; `FixtureFreshnessTest` fails until
they are.

`ExperimentFlowTest.prepareThenSeedThenImportProducesCountableMembers` keeps its name and its point.
It now proves something stronger: that an export whose `teams` table is empty still produces counted
members, which is the whole claim of this design.

## 14. Documentation

`CLAUDE.md`: the domain vocabulary, the "Where the project stands" paragraph, and the `prepare`
mention in the layout. `docs/backlog.md`: the 2026-09-19 entry about deleting `prepare` is replaced
by what actually became of it. `server/README.md` and `server/forecast-web/README.md`.
`docs/design/2026-09-17-workloadhub-schema-diagram.html` and
`docs/design/2026-09-17-feature-matrix-now.html` both state the old model in so many words and are
corrected. `docs/superpowers/specs/2026-09-19-real-export-preparation-design.md` gets a header note
saying which of its sections this document withdraws; it is otherwise left as the record of what was
decided then.

## 15. Risks

- **The blast radius is wide.** Seventy-two files reference a team id across the three modules and
  the UI. Two groups must land whole or nothing compiles: the repository with its direct consumers,
  and the seed with `ProjectPlanner`, `Rhythm`, `WorkQueue` and `SeedGenerator`. The plan sequences
  around that; there is no way to make either smaller.
- **Keeping the name `team_id` is a deliberate trade.** It buys no migration and no host change, and
  it costs a column whose name no longer names the table it came from. Every place it is read now
  carries a comment saying what it holds. If it later reads as a mistake, renaming it is a
  mechanical change plus one migration, and nothing in this design makes that harder.
- **The joined date changes what `tenure_weeks` measures**, from "in a team since" to "active since".
  On seeded data the two nearly coincide. On the real export the new one is the only one with
  meaning. Model accuracy over the real export has never been measured, so there is no baseline to
  regress against and none is claimed.
- **The first activity date depends on data volume.** A member whose history predates the loaded
  window gets a joined date at the window's start and an understated tenure. The window is 52 weeks
  by default, so this understates long tenures rather than inventing short ones, and `startIndex`
  already clamps to the first loaded week.
- **Promoting managers of managers removes people from the forecast.** That is the intent, but it
  is the one change here that makes a run cover fewer people than before. The count of affected
  users on the real export should be read off `prepare`'s own summary before the first real run.
- **The real export is not in this repository or this container**, per the standing hard rule. The
  figures in section 1 are the owner's measurements, not re-measured here, and the implementation is
  written against the schema and the code.

## 16. Second revision: the role comes from the job title

Settled with the owner on 2026-09-21, after the first revision landed, when the owner's real export was
measured for the first time. It amends rulings 1, 2, 3 and 9. Everything else in this document stands.

### 16.1 What the measurement showed

The owner's directory, 264 users:

| `users.role` | count |
|---|---|
| MEMBER | 260 |
| TEAM_LEADER | 2 |
| ADMIN | 1 |
| CENTER_MANAGER | 1 |
| SKILL_TEAM_LEADER | 0 |

The two accounts marked `TEAM_LEADER` have no job title, no department, no manager and **no direct
reports**: they are application accounts, not people in the org chart. Nobody at all carries
`SKILL_TEAM_LEADER`. So the role column is not evidence of leadership, and a production run keyed on
it would find no team anywhere and forecast nobody — the same failure this document was written to fix,
one level further in.

`job_title` does carry the structure, and the owner confirmed it is synchronised automatically from the
organisation system:

- 12 titles say "Team Leader", with 143 people directly beneath them;
- 3 say "Skill Team Leader", each sitting above real team leaders (4, 3 and 2 of them);
- 1 says "Engineering Center Manager", with 13 reports;
- one more, "Lead Engineer DAI & AI", has 24 reports under a title that says neither.

`job_title_role_mappings` exists in WorkloadHub's schema, with `job_title_id`, `role_id` and an optional
per-user `user_id` — exactly the right table for this — and holds **0 rows**. The owner ruled it out for
now; the patterns below are the whole rule.

### 16.2 The rule

`EffectiveRole` (`server/forecast-core/src/main/java/com/workloadhub/forecast/data/EffectiveRole.java`)
is the one place that decides a role, in two stages.

**Stage 1, the title.** Matching is case-insensitive over whitespace-collapsed text, and the order is
load-bearing, because "Skill Team Leader" contains "team lead".

1. `users.role` is `ADMIN`, `CENTER_MANAGER` or `VIEWER` → that role. No job title implies these, and
   the application assigns them deliberately.
2. the title contains `skill team leader` → `SKILL_TEAM_LEADER`
3. the title contains `center manager` → `CENTER_MANAGER`
4. the title contains `team lead` or `lead engineer` → `TEAM_LEADER`
5. otherwise → `MEMBER`

A `TEAM_LEADER` or `SKILL_TEAM_LEADER` **declared in the column is not believed**, for the reason in
16.1.

**Stage 2, the demotion.** A stage-1 `TEAM_LEADER` with no counted, active direct report becomes
`MEMBER` (owner's ruling: "if a team leader or lead engineer with no direct members that he manage below
him then he should be treated as just a member"). They are forecast as an ordinary member of their own
manager's team and run nothing.

An **actor is never demoted**. A `SKILL_TEAM_LEADER` or `CENTER_MANAGER` with no reports has nothing to
act on, but making them a member would make them a forecast subject, and ruling 2 says neither does
technical work.

**Why the two stages cannot contradict each other.** Demotion only ever turns `TEAM_LEADER` into
`MEMBER`, and both are counted. So whether a user is counted is fixed by their title alone, never by
anyone else's demotion, the rule terminates in one pass, and the answer does not depend on the order of
the input. `EffectiveRoleTest` holds all three.

### 16.3 Consequences for ruling 1

Every effective `TEAM_LEADER` keys exactly one team, because a leader nobody reports to is no longer a
leader. The "and has at least one counted direct report" half of ruling 1 is therefore carried by the
role itself, and `FeatureBuilder.teamKeys`, `DefaultForecastService.requireTeam` and
`forecast-web`'s `Directory.teams()` each test the role alone.

### 16.4 Who may run

Amending ruling 3 and `2026-09-11-host-integration-design.md` section 3.2:

| effective role | runs | views |
|---|---|---|
| `ADMIN` | any team | any team |
| `CENTER_MANAGER` | **any team** (was: none) | any team |
| `SKILL_TEAM_LEADER` | the team of a leader who reports to them, one at a time | those, and their own |
| `TEAM_LEADER` | their own team | their own, and the one they belong to |
| `MEMBER` | none | the team they belong to |
| `VIEWER` | none | any team |

The owner's words: a skill team leader, an admin and a centre manager "are unique positions that can run
forecast on behalf of teams that belong to the hierarchy under them; they don't have their own direct
teams to forecast". A centre manager sits above the whole organisation, so "beneath them" is every team;
a skill team leader's reach stays what ruling 3 already said — the leaders who report to them directly —
which in the owner's directory is the same set either way.

### 16.5 What this costs

**Who is left out.** The owner ruled that people who belong to no team stay out rather than being
gathered under an actor. In the real directory that is 85 of 264: 43 with no `manager_id` at all, 31
plain members reporting straight to a Skill Team Leader or the Engineering Center Manager, 8 lead
engineers with no reports (7 of whom report to a Skill Team Leader), and the 3 skill team leaders and
1 centre manager, who are correctly never counted. **12 teams, 179 people forecastable.**

**The rename risk.** A job title edited to something the patterns do not match silently removes a team.
The owner accepts it: the titles are written by the organisation system, not by hand. `prepare` prints
what it classified, so a real export can be checked before it is seeded. `job_title_role_mappings` is
the permanent fix if it is ever populated.

### 16.6 What changed in the code

- **new** `forecast-core` `data/EffectiveRole.java`, and `MemberRow.withRole`.
- `ForecastRepository` reads every user, classifies once, then keeps the counted ones: who counts
  depends on the whole directory, so nobody can be judged row by row any more.
- `FeatureBuilder.teamKeys` tests the role alone (16.3).
- `DefaultForecastService.requireTeam` reads the key and their direct reports instead of asking SQL
  about `users.role`.
- `ExportPreparer` fell to activation plus writing the effective role back; its `Result` gained
  `countedMembers`, which is the number the owner checks, and `teamLeaders` is now also the team count.
- The seed's `Directory` step 3 calls `EffectiveRole` instead of its own manager-of-managers rule, so
  the seeded `users.role` says what the module will derive from the same rows. The synthetic directory
  also gained one `VIEWER`, so all six roles have a subject.
- `forecast-web`'s `Directory` and `ForecastAccess`, and the sample host's `ForecastAccess`, classify in
  Java rather than testing the role in SQL; `permissions.ts` carries the new matrix.
