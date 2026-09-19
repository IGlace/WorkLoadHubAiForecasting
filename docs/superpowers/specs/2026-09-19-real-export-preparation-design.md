# Preparing a real WorkloadHub export for the seed

Date: 2026-09-19
Status: design, not yet implemented
Supersedes nothing. Extends `2026-09-17-personal-leaves-capacity-and-seed-scope-design.md` (real mode) and
`2026-09-18-seed-owns-the-work-tables-design.md` (the seed's landing path) without changing either.

## 1. Why

The owner has a real WorkloadHub JSON export and wants a forecast over the real people in it. Run through the
driver as it stands, that export produces a forecast over **nobody**: `ForecastRepository` counts a member only
when three conditions hold at once, and the export fails two of them.

`forecast-core/src/main/java/com/workloadhub/forecast/data/ForecastRepository.java:62-66`:

```java
jdbc.sql("SELECT id, full_name, email, username, role, job_title, active, deactivated_at FROM users").query((rs, i) -> {
    UUID id = rs.getObject("id", UUID.class);
    users.add(new UserRef(id, rs.getString("full_name"), rs.getString("email"), rs.getString("username")));
    List<UUID> teamIds = teamsOfUser.getOrDefault(id, List.of());
    if (!rs.getBoolean("active") || !COUNTED_ROLES.contains(rs.getString("role")) || teamIds.isEmpty()) {
        return null;
    }
```

with `COUNTED_ROLES = Set.of("MEMBER", "TEAM_LEADER")`.

Measured on the owner's export (read as Windows-1252; `ExportFiles.read` already falls back, see section 10):

| fact | value |
|---|---|
| `users` rows | 264 |
| roles | `MEMBER` 260, `TEAM_LEADER` 2, `ADMIN` 1, `CENTER_MANAGER` 1 |
| `users.active = true` | **6** (258 false) |
| distinct `department` | 34, plus 31 users with none |
| distinct `manager_id` | 19 |
| `teams` rows | **3** — `Engineering`, `Backend Team`, `Frontend Team`, all created `2026-09-03T13:59:58` |
| `team_members` rows | **3** |

The role filter passes for 262 of the 264. `active` and the team membership both fail. WorkloadHub is still in
its testing phase, so `active = false` carries no meaning here (owner, 2026-09-19), and its `teams` screen has
not been used, so the three team rows are test stubs, not the company's structure.

## 2. The ruling: a step in front of the seed, not a change inside it

The obvious move — teach `SeedGenerator`'s real mode to invent teams — is the wrong one, and the owner's own
answer during brainstorming is why: **teams will be populated for real.** Derivation baked into real mode becomes
actively harmful the day WorkloadHub fills `team_members`, because the seed would then overwrite the company's
real structure with a guess, every run, silently.

So the correction happens **before** the seed, on the export file, and nothing downstream changes:

```
real export ──prepare──> prepared export ──import──> database (directory, teams, memberships)
                              │
                              └──seed --export──> work tables ──import──> database
```

This works today with no change to the seed because `Directory.derive` in real mode returns the export's own
directory verbatim (`Directory.java:140-144`):

```java
// Real mode: the application's own teams are the structure, and the seed writes none (design
// 2026-09-17, section 7.1), so nothing may be invented here.
if (!cfg.synthetic()) {
    return new Result(new ArrayList<>(people.values()), applicationTeams(teamRows, memberRows, people), userRows, teamRows, memberRows);
}
```

`applicationTeams` reads `teams` and `team_members` straight out of the envelope. Hand it a populated export and
real mode consumes it unchanged.

It also works with no change to the importer, because `ExportImporter` deletes and re-inserts **only the tables
the envelope carries** (`ExportImporter.java:60-79`). The prepared export carries `users`, `teams` and
`team_members`, so those three are replaced; the work tables arrive later from the seed's own export.

When WorkloadHub does populate teams, the remedy is to stop running one command. Nothing else moves.

## 3. The command

A sixth verb on the driver, `prepare`, in the same shape as the five already there:

```
prepare  <export.json> --out FILE [--joined ISO_DATE] [--force]
         Rewrite a real WorkloadHub export so the forecast can count its people: every user
         active, and teams and memberships derived from department and manager_id. Refuses to
         write inside a git repository without --force: its output holds personal data.
```

`--out` and the git-repository refusal mirror `seed` exactly (`Experiment.java:171-187`), for the same reason and
under the same hard rule: *the real export and any real-mode seed output stay outside the repository.*

`--joined` sets the `team_members.joined_at` timestamp (section 6). It defaults to **five years before the day the
command runs**, printed in the summary.

Exit codes and message style follow the driver: 0 done, 2 a bad request, 1 anything else; English only.

It is documented in `USAGE` and in `server/README.md` as **transitional** — the one command to delete when
WorkloadHub's own teams arrive.

## 4. What it writes: `users`

For every row of `users`, without exception:

- `active` → `true`
- `deactivated_at` → `null`

Both, not just the first. `ForecastRepository.java:73` reads `deactivated_at` into the member's *left* date:

```java
LocalDateTime left = rs.getObject("deactivated_at", LocalDateTime.class);
```

A user flipped active while still carrying a `deactivated_at` timestamp is counted and then treated as having
left partway through the history — their forecast silently goes to zero from that date. Clearing the timestamp is
not tidiness; it is the other half of the same fix.

All 264 are flipped rather than the 262 counted ones: the role filter still excludes the `ADMIN` and the
`CENTER_MANAGER`, so selecting is extra logic that buys nothing.

### 4.1 Roles: managers become `TEAM_LEADER`, and nobody becomes `SKILL_TEAM_LEADER`

Every user named as another user's `manager_id`, whose own role is currently `MEMBER`, is promoted to
`TEAM_LEADER`. On this export that is at most 19 people.

Two reasons. A team whose `manager_id` names a plain `MEMBER` is internally inconsistent data. And `Rhythm.base`
(`Rhythm.java:62-64`) halves a `TEAM_LEADER`'s seeded weekly hours, which is the realistic shape — a leader logs
less task work than their reports — and is unreachable while every manager reads as `MEMBER`.

The synthetic path also promotes department heads to `SKILL_TEAM_LEADER` (`Directory.java:117-132`). **This step
does not.** `SKILL_TEAM_LEADER` is not in `COUNTED_ROLES`, so copying that would drop roughly 34 people out of a
262-person forecast for no gain. The synthetic path can afford it because it invents its people; here they are
real and the owner wants them forecast.

No other role changes. The `ADMIN` and the `CENTER_MANAGER` keep theirs — `ProjectPlanner.fallbackOwner`
(`ProjectPlanner.java:66-76`) looks for exactly those two to own a headless department's projects.

## 5. What it writes: `teams`

Two levels, because the two-level shape is not cosmetic — three separate pieces of existing code read it:

- `ForecastRepository.java:68-70` picks a member's **primary team** as the first of their teams that *has a
  parent*. With a flat set of teams every member's primary team is whichever id sorts first.
- `ProjectPlanner.plan` (`ProjectPlanner.java:99-101`) creates projects only for teams where `department()` is
  true, and `applicationTeams` sets `department()` from `parent == null`. With no parentless team there are no
  projects, and therefore no work.
- `Rhythm`'s constructor (`Rhythm.java:48-53`) gives a member's *manager* team precedence over their
  *department* team when assigning their work rhythm.

So the derived structure mirrors what `Directory` already builds on the synthetic path (`Directory.java:146-194`):

**Department teams** — one per distinct department code, parentless.

- The code comes from `Directory.deptCode`, which is already `public static`: `"PTE / CT2 Calibration & Testing 2"`
  → `"CT2"` (first token after the slash, trailing punctuation dropped, upper-cased). Distinct codes may therefore
  be fewer than the 34 distinct department strings.
- Users with no department fall into code `""`, whose team is named `Unassigned`.
- `name` is the longest department string seen for that code, passed through `Directory.uniqueName` (truncate to
  100, then append ` 2`, ` 3`… on collision) because `teams.name` is `UNIQUE`
  (`workloadhub-postgresql.sql:620`).
- `manager_id` is the department's head: the first member whose `job_title` contains *skill team leader*, else
  the member with the most direct reports, else `null`. A `null` is legal and handled — see `fallbackOwner`
  above.
- `parent_team_id` is `null`, `active` is `true`.
- Members: everyone in the department who has no manager inside the export, plus the head. Everyone else reaches
  the department through their manager team.

**Manager teams** — one per user who has at least one direct report inside the export, child of that manager's
department team.

- `name` is `"<CODE> · <manager's full name>"`, again through `uniqueName`. Real names in local data are
  allowed (owner ruling, recorded in `CLAUDE.md`).
- `manager_id` is the manager; members are the manager plus their direct reports.
- A manager with no department of their own takes the majority department code of their reports.

On the measured export that is at most 34 department teams (one of them `Unassigned`) and at most 19 manager
teams over 262 counted people.

### 5.1 The three existing teams

The three stub teams are dropped, along with their three memberships — with one guard. Three other columns carry
a foreign key to `teams.id`: `projects.team_id`, `team_capacity.team_id` and `teams.parent_team_id`
(`workloadhub-postgresql.sql:833-981`). A team id referenced by a `projects` or `team_capacity` row in the same
export is **kept**, together with its memberships and, transitively, its ancestors through `parent_team_id`; a
kept parentless team simply behaves as another department team. Dropping a referenced team would make the import
fail on a foreign key, and the seed's real mode carries the export's own projects forward
(`ProjectPlanner.java:79-85`), so the reference would survive to the database.

The command prints how many pre-existing teams it kept and how many it dropped. On this export the expected
result is 0 kept, 3 dropped.

Derived team ids are minted with `SeedRandom` from a fixed seed so two runs over the same export produce the
same file.

## 6. What it writes: `team_members`

One row per (team, member) pair, with `id` minted from the same `SeedRandom`, and
`joined_at = created_at = updated_at = --joined` at 08:00.

`joined_at` is not filler. `ForecastRepository.java:54-57` folds it into the member's joined date, earliest row
winning:

```java
joinedOfUser.merge(user, rs.getObject("joined_at", LocalDateTime.class).toLocalDate(), (a, b) -> a.isBefore(b) ? a : b);
```

Stamped at generation time, every member looks like they arrived the day the command ran, and the entire seeded
history before that date is orphaned. `seed` defaults to 52 weeks ending today (`Experiment.java:191-192`), so
the default of five years back clears any plausible window; `--joined` covers the rest.

`(team_id, user_id)` is `UNIQUE` (`workloadhub-postgresql.sql:613`), so a member appearing in both their
department team and their manager team gets one row in each and no duplicate within either.

## 7. What it does not do

- It does not touch any table other than `users`, `teams` and `team_members`. Everything else is copied through
  byte for byte, including `excluded_tables` and the envelope metadata.
- It does not touch `absences` — legacy, confirmed by the owner; capacity comes from `personal_leaves`.
- It does not write to a database. It reads a file and writes a file.
- It does not change `SeedGenerator`, `Directory`, `ExportImporter` or anything in `forecast-core`.
- It is never run against the real WorkloadHub database, which is never seeded (owner, 2026-09-18). Its output
  feeds the local PostgreSQL of `scripts/postgres.sh` only.

## 8. Behaviour it inherits and deliberately leaves alone

Real mode gives 10% of people a late join date and 3% a leave date part-way through the window
(`Directory.java:24-25`, `LATE_JOIN_SHARE`, `LEAVE_SHARE`), and uses them to shape the generated work — while
leaving `users.active` and `deactivated_at` untouched on the real path, because `Directory.derive` returns
before the row-rewriting step. So after a real seed a handful of people will show work that starts late or stops
early, with the database still calling them active.

That is existing real-mode behaviour, it predates this design, and it is not this step's to change. It is
recorded here so that the first real run does not read it as a defect, and added to `docs/backlog.md`.

## 9. Structure

New, in `forecast-tools` (never shipped):

```
src/main/java/com/workloadhub/forecast/tools/prepare/ExportPreparer.java
```

One public entry point, pure in and pure out, so it is testable without a database or a file:

```java
public static Result prepare(ExportEnvelope input, LocalDate joined, long seed)
public record Result(ExportEnvelope envelope, int usersActivated, int managersPromoted,
                     int departmentTeams, int managerTeams, int teamsKept, int teamsDropped)
```

The `seed` parameter is the `SeedRandom` seed used to mint team and membership ids; the driver always passes
the same fixed constant, so the command has no `--seed` flag and two runs over one export give byte-identical
output. It is a parameter only so the tests can vary it.

`Experiment.prepare` reads the file, calls it, writes the file and prints the counts. Derivation logic reuses
`Directory.deptCode` and `Directory.uniqueName` rather than restating them; `uniqueName` is package-private today
and moves to public alongside `deptCode`.

The class stays under about 250 lines. If it grows past that, the team derivation splits out.

## 10. Encoding

None of this needs new encoding handling. `ExportFiles.read` decodes UTF-8 strictly and falls back to
Windows-1252 when the bytes are not valid UTF-8, which the owner's export is not. `ExportFiles.write` writes
UTF-8, so the prepared export is UTF-8 and every later step reads it on the fast path.

## 11. Testing

Test-driven, in `forecast-tools/src/test/java/com/workloadhub/forecast/tools/prepare/ExportPreparerTest.java`,
over small hand-built envelopes — no real export, which never enters the repository.

1. Every user comes out `active = true` with `deactivated_at = null`, including one that arrived active and one
   that arrived deactivated.
2. A user who is someone's `manager_id` and was `MEMBER` comes out `TEAM_LEADER`; the `ADMIN` and the
   `CENTER_MANAGER` are unchanged; nobody is `SKILL_TEAM_LEADER`.
3. Two departments and one manager produce two parentless teams and one child team, with the child's
   `parent_team_id` naming its department.
4. A user with no department lands in `Unassigned`.
5. Every user appears in at least one team — the property that makes `ForecastRepository`'s third condition pass.
6. Two departments whose strings collapse to the same `deptCode` share one team; two that collide on name get
   ` 2` appended.
7. A stub team referenced by a `projects` row survives; an unreferenced one does not, and neither do its
   memberships.
8. `joined_at` equals the requested date; the default is five years back.
9. Every table other than the three is identical, by value, to the input.
10. Two runs over the same input produce identical JSON.

A jqwik property over randomly generated directories: **every user of the output is in at least one team, every
team's `parent_team_id` resolves to a team in the output, and no `(team_id, user_id)` pair repeats** — the three
invariants that, broken, produce either a silent empty forecast or a foreign-key failure at import.

`ExperimentFlowTest` gains a case driving `Experiment.run` end to end: prepare a small envelope, seed from the
prepared file, import, and assert `ForecastRepository` counts the members — the whole chain this design exists to
unblock, in one test.

The git-repository refusal gets its own test, as `seed`'s does.

## 12. Documentation

- `server/README.md`: `prepare` in the driver's command list, and a short *Running the forecast over a real
  export* section giving the four commands in order. Marked transitional.
- `CLAUDE.md`: the "Where the project stands" paragraph, and `prepare` in the `forecast-tools` line of the
  layout.
- `docs/backlog.md`, under "Java migration": delete `prepare` once WorkloadHub populates `teams` and
  `team_members`; and the inherited late-join/early-leave behaviour of section 8.

## 13. Risks

- **The derived structure is a guess.** Department strings and `manager_id` are real, but the teams built from
  them are this step's invention. They shape which projects exist and who is pressed by them. This is acceptable
  only because the alternative is no forecast at all, and because the step is deleted the moment real teams
  arrive. It must never run against anything but the local database.
- **Role promotion changes real data.** Section 4.1 rewrites up to 19 people's roles in the prepared file. The
  file is local and disposable; the real database is untouched. If the owner would rather not, the promotion is
  one method and can be dropped without affecting anything else in this design.
- **The export is not in this repository or this container.** Every figure in section 1 is from the owner's own
  reading of the file, not re-measured here. The implementation is written against the schema and the code, and
  the owner runs it against the file.
