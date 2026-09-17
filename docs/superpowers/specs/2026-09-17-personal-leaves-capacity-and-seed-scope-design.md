# Personal leaves, capacity from the calendar, and the seed's scope

**Date:** 2026-09-17
**Status:** designed; owner review pending
**Supersedes:** section 4 ("Calendar and capacity") and parts of section 5 of
`docs/design/2026-09-08-workloadhub-schema-and-feature-matrix.md`; the capacity paragraphs of
`docs/superpowers/specs/2026-09-13-weekly-hours-forecast-design.md` section 10 where they read
`user_capacity`.

## 1. Why, and the owner's rulings

Three things changed on the application side and one review changed the feature matrix:

1. `absences` is a legacy table. The main developer confirmed on 2026-09-16 that the application will no
   longer write it. Absence now lives in `personal_leaves`, one row per leave with a start date, an end
   date, optional begin and end times, a total of absence hours, a status and a type.
2. `user_capacity` and `team_capacity` will not be used. The forecast computes capacity itself from the
   holidays, the personal leaves and one default: 44 hours per week.
3. The seed no longer produces a whole database. The application's own tables (users, teams, memberships,
   statuses, types, roles, job titles, holidays) are already filled; the seed produces only the work and
   the leaves.
4. The owner walked the feature matrix on 2026-09-16 (`docs/design/2026-09-16-feature-matrix-review.html` is
   the page that was reviewed, kept as a record; its findings are restated in section 5 below) and ruled on
   every invented rule.

The rulings, in the letters the review used:

| | Subject | Ruling |
|---|---|---|
| A | assignment date | Keep reading it from `task_history` (the latest `assignee` row whose `new_value` is the current assignee, else `created_date`). The application will one day carry `tasks.assigned_at`; read it when it exists, not now. Backlog. |
| B | fresh versus backlog arrivals (the two-day rule) | **Keep.** It tells whether work reaches a member fresh or late from the backlog, which the owner wants to see. |
| C | task families | Keep the mapping. All twelve types of the application are named by it. A Sub-task takes its parent's family: a sub-task of a bug is bug work. |
| D | assignment mode | **Replace the guess with the fact.** `task_history.user_id` on the assignee row is who assigned the task. Equal to the new assignee: self-picked. Anyone else: assigned. Two columns replace three (section 6). |
| E | remaining hours | Keep both definitions: the features recompute estimate minus logged hours as of the row's week; the leader-facing facts read `remaining_estimate_hrs`. Documented in section 5. |
| F | sentinel values | **Blank instead of invented.** Every ratio whose denominator is zero, and "weeks since" when there is no event, are left empty; the booster treats them as missing (section 5.2). |
| G | the fields read as of today | Accept and document (section 5.3). Five, not four: `planned_week` joins them, because `Truncation` does not rewind it either. |
| H | project deadline | **Remove `proj_first_due_weeks`.** Projects will have no deadline or start date. |
| I | capacity default | `user_capacity` is not read. Capacity is the module's own formula (section 3). 44 h stays a setting, `whf.default-weekly-hours`, default 44. |
| J | `team_capacity` | Not read, not shown. The facts drop the `team.team_capacity` block. |
| K | `planned_week` | **Read it.** It is filled sometimes, and that is good enough: `planned_hrs_h` returns as a per-horizon column (section 5.4). |
| — | leave semantics | `absence_hours` is the total for the whole leave; `begin_time` and `end_time` mark a leave that starts or ends on a partial day. Only `APPROVED` leaves reduce capacity; `PENDING` ones are listed in the facts for the narrative. |
| — | the seed's target tables | Projects, tasks, task history, time logs and personal leaves; the six work tables are replaced, projects are upserted (section 7). |
| — | "open" | The application's status `Open` means unassigned. The module's "open tasks" means assigned and not finished. The code keeps its names; the product skills define the word so Copilot writes "assigned and not finished" (section 8). |

## 2. Personal leaves replace absences

### 2.1 What is read

`ForecastRepository` stops reading `absences` and reads `personal_leaves`:

```sql
SELECT employee_id, start_date, end_date, begin_time, end_time, absence_hours, status, leave_type
FROM personal_leaves
WHERE status IN ('APPROVED', 'PENDING')
```

into a new row type:

```java
/** One row of `personal_leaves`. `absenceHours` is the total over the whole leave, or null. */
public record LeaveRow(UUID employeeId, LocalDate start, LocalDate end, LocalTime beginTime, LocalTime endTime,
        Double absenceHours, String status, String leaveType) {}
```

`ForecastData` gains `leaves()` (the `APPROVED` rows) and `pendingLeaves()` (the `PENDING` rows), both
sorted by employee then start date, and loses `absences()`, `capacity()` and `teamCapacity()`.
`AbsenceRow`, `CapacityRow` and `TeamCapacityRow` are deleted. `Truncation` passes both leave lists through
unchanged: a leave is not history to be rewound.

`REJECTED` and `CANCELLED` rows are never loaded. The `absences` table stays in the two schema files and in
`WorkloadHubSchema.TABLE_ORDER`, because the application still has the table and old exports still carry
it; nothing in the module reads it, and the seed no longer writes it.

### 2.2 How a leave becomes absent days

A leave is expanded into per-day absence hours by one pure function, `capacity/LeaveDays`:

```java
/** Absence hours per member and day, from approved leaves, on working days only. */
static Map<UUID, NavigableMap<LocalDate, Double>> expand(List<LeaveRow> leaves, WorkingCalendar cal, double fullDayHours)
```

For one leave:

1. **The days.** Every calendar day from `start` to `end` inclusive that is a weekday and not a confirmed
   holiday, in date order. A leave over a weekend or a holiday costs nothing on those days. A leave with no
   such day contributes nothing.
2. **The full day.** `fullDayHours` is `whf.default-weekly-hours / 5`, 8.8 by default. The rule never reads
   a per-member figure because there is none any more (section 3).
3. **The hours.** Let `n` be the number of days from step 1 and `total` the leave's `absence_hours`.
   - `total` null or not positive: every day gets `fullDayHours`. The application did not say how long the
     leave is, so the whole period counts.
   - Otherwise `total` is capped at `n × fullDayHours` and dealt out `fullDayHours` per day **from the first
     day forward**, the last day receiving what is left. When `begin_time` is set and `end_time` is not,
     the leave starts on a partial day, so it is dealt **from the last day backward** and the first day
     receives what is left. Only `begin_time` alone moves the partial day to the front: a leave with both
     `begin_time` and `end_time` is dealt forward like one that only ends, so its remainder lands on the last
     day. A day that receives zero hours is not an absent day.

   Examples with an 8.8-hour day:

   | Leave | Days | Hours per day |
   |---|---|---|
   | Mon 7 to Wed 9 Sep, `absence_hours` 26.4 | Mon, Tue, Wed | 8.8, 8.8, 8.8 |
   | Mon 7 to Wed 9 Sep, 22.0, `end_time` 12:00 | Mon, Tue, Wed | 8.8, 8.8, 4.4 |
   | Mon 7 to Wed 9 Sep, 22.0, `begin_time` 13:00 | Mon, Tue, Wed | 4.4, 8.8, 8.8 |
   | Mon 7 to Wed 9 Sep, 22.0, `begin_time` 13:00 and `end_time` 12:00 | Mon, Tue, Wed | 8.8, 8.8, 4.4 |
   | Fri 4 to Mon 7 Sep, 17.6 | Fri, Mon (weekend skipped) | 8.8, 8.8 |
   | Thu 10 to Thu 10 Sep, 4.0 (a half day) | Thu | 4.0 |
   | Thu 10 to Fri 11 Sep, 8.8 (one day's worth over two dates) | Thu, Fri | 8.8, 0 → only Thu is absent |
   | Mon 7 to Fri 11 Sep, `absence_hours` null | five days | 8.8 each |
   | a leave over a public holiday, 8.8 on the holiday's date only | none | nothing |

   The cap keeps a data-entry error (an `absence_hours` larger than the period) from creating negative
   capacity; the deal-out keeps a leave that ends at noon from spreading its missing half day thinly over
   every day, which would leave no day fully absent and let the placement put predicted hours on days the
   member is away.
4. **Two leaves on one day** add up, and the sum is capped at `fullDayHours` where it is used (section 3).

The expansion runs once per data set inside `CapacityRule`'s per-data index, which already builds a
per-member, per-day map; it builds the calendar from `data.holidays()` for that purpose. Callers of
`CapacityRule` do not change their signatures except where noted in section 3.

### 2.3 Pending leaves in the facts

Each member's facts gain `pending_leaves`: the member's `PENDING` rows whose period touches the run's
horizon (first day of window 1 to last day of the last window), as `{start_date, end_date, leave_type,
absence_hours}`. It sits beside `days`, and the domain skill says what it is: a leave requested but not
approved, which does not reduce capacity, which the narrative may mention as a risk to the window. The
number verifier already walks every numeric node of the facts, so a cited `absence_hours` verifies without
any change there.

## 3. Capacity from the calendar and the leaves

`CapacityRule` keeps its public surface and loses its data:

| Method | Before | After |
|---|---|---|
| `capacity(member, monday, data, cal)` | `user_capacity.available_hrs` if a row exists; else latest base or default × working days / 5 − absence | `default × workingDaysInWeek(monday) / 5 − leaveHours(member, monday)`, never below 0 |
| `absenceHours(member, monday, data, cal)` | `user_capacity.absence_hrs` if a row exists; else the absence rows on working days | the sum of the member's expanded leave hours on the week's working days |
| `dayCapacity(member, day, data, cal)` | 0 off the calendar; row / working days; else base / 5 − day absence | 0 off the calendar; else `default / 5 − dayLeaveHours`, never below 0 |
| `dayAbsenceHours(member, day, data)` | the day's absence rows | the day's expanded leave hours, capped at `default / 5` |
| `offDays(member, data, row, cal)` | days whose absence meets the gross day capacity | days whose leave hours meet `default / 5`; the `MemberRow` argument goes |
| `grossDayCapacity` | row base / working days, else base / 5 | `default / 5` on a working day, else 0 |

`WORKING_DAYS_PER_WEEK` stays 5. `whf.default-weekly-hours` stays a property with default 44, unchanged;
`ForecastProperties` and the README's property table do not change.

What goes with it: `ForecastRepository`'s two queries on `user_capacity` and `team_capacity`;
`ForecastData.withTeamCapacity`; the `team_capacity` block of the facts (`FactsBuilder`, the domain skill,
`FactsToolsTest`); the seed's `CapacityWriter` and its test; and every test fixture that built a
`CapacityRow`. The API records keep `absenceHrs` on `MemberWindowForecast`: it now reads leave hours, and
the facts key `absence_hours` keeps its name because the narrative vocabulary is "absence", whatever the
table.

The feature columns `absence_hrs_h` and `available_hrs_h` are computed by the same two methods and change
with them. Nothing else in the matrix reads capacity.

## 4. What the seed does about leaves

`AbsencePlanner` stops producing `absences` rows. Its `Plan` keeps `absentDays` (the seed's own presence
logic, unchanged) and `leaveRows`, which already carry `leave_type` (`PAID_LEAVE` for a vacation block,
`SICK_LEAVE` for a sick day), `status` `APPROVED`, `absence_hours` = 8.8 × days, and null times. Two
additions so that the new paths are exercised on seeded data:

- one vacation block in five ends on a half day: `absence_hours` is 4.4 lower and `end_time` is `12:00`;
  the seed's own presence for that afternoon stays "absent" (the difference is below the seed's daily
  granularity and is accepted);
- one counted member in ten gets a `PENDING` leave of two to five working days starting inside the four
  weeks after the as-of date. It has no effect on the seeded history (it is in the future) and gives the
  narrative something to mention.

`user_capacity` and `team_capacity` are no longer written in either mode; their keys stay in the envelope,
empty, so `SeedGeneratorTest`'s "every table present" check holds and a synthetic import still creates
nothing the module would read.

## 5. The feature matrix after the review

### 5.1 Columns that change

| Column | Change | Ruling |
|---|---|---|
| `proj_first_due_weeks` | **deleted** | H |
| `share_self_picked_13w`, `share_manual_13w`, `share_project_13w` | **replaced** by `share_self_picked_13w` and `share_assigned_13w` (section 6) | D |
| `absence_hrs_h`, `available_hrs_h` | computed from leaves and the default (section 3) | I |
| `planned_hrs_h` | **added** per horizon (section 5.4) | K |
| `weeks_since_last_arrival`, every `share_*_13w`, `reopen_rate_13w`, `estimate_ratio_13w` | blank when there is nothing to measure (section 5.2) | F |

Shared columns: 42 − 1 − 3 + 2 = **40**. Per-horizon columns: 4 + 1 = **5**, `due_hrs_h`, `planned_hrs_h`,
`working_days_h`, `absence_hrs_h`, `available_hrs_h`. `Features.featureColumns(h)` therefore has 45 entries.
`Features.HISTORY_WEEKS` stays 65, the lags and rolling windows do not change, and the target does not
change.

### 5.2 Missing values, stated once

The matrix stores `Double.NaN` where a cell has no value; `FeatureMatrix.flatten` hands XGBoost a dense
array with `Float.NaN` as its declared missing marker (`new DMatrix(values, rows, cols, Float.NaN)`), and
XGBoost's histogram trees learn, at every split, a default direction for a missing value from the training
rows that lack it. A blank is therefore not "zero" and not "average": it is its own branch, learnt from the
rows where the same thing was unknown. `FeatureMatrix.nonEmptyColumns` already drops a column that is blank
on every training row, and `rowsWithKnown` already keeps a training row only when its target is known; no
new mechanism is added.

The cells left blank, and what "nothing to measure" means for each:

| Column | Blank when | Was |
|---|---|---|
| `weeks_since_last_arrival` | the member received no fresh task in the loaded history | 52 |
| `share_defect_13w`, `share_delivery_13w`, `share_support_13w`, `share_high_priority_13w` | no task was assigned to the member in the 13-week window (`arrivals_13w` = 0) | 0.0 |
| `share_self_picked_13w`, `share_assigned_13w` | no task in the window has a known mode (section 6) | 1/3 |
| `reopen_rate_13w` | the member finished no task in the window | 0.0 |
| `estimate_ratio_13w` | no finished task in the window has both a positive estimate and positive hours | 1.0 |
| `cycle_days_13w` | the member finished no task in the window | already NaN |
| `lag{k}`, `arrival_hrs_lag{k}` | the week is before the member's first row | already NaN |
| `target_h` | the target week is after the origin | already NaN |

Not blank, on purpose: `arrivals_13w`, `open_tasks`, `open_remaining_hrs`, `overdue_open`,
`in_progress_tasks`, `team_backlog_unassigned_hrs`, `proj_active`, `proj_planning`, `due_hrs_h`,
`planned_hrs_h`, `absence_hrs_h` are counts and sums, and zero is their true value when there is nothing.
`roll_std_*` is 0.0 for a single-week window because one observation has no spread, which is a statement,
not an absence of one.

This table is copied into the schema document's feature section and into `Features.java`'s class comment,
so that the next reader finds the rule beside the columns. `FeatureBuilderTest` pins each blank with a member
who has nothing in the window and asserts `Double.isNaN` on the cell, and the matching non-blank case one
row later.

### 5.3 Read as of today, documented

The history replay (`Truncation`) rewinds assignments, statuses and logs to the row's week. Five fields have
no history and are read as they stand on the run day: `tasks.reopened_from_done`, `projects.status`,
`tasks.due_date`, `tasks.original_estimate_hrs` and `tasks.planned_week`. `reopen_rate_13w`, `proj_active`,
`proj_planning`, `overdue_open`, `due_hrs_h`, `planned_hrs_h` and every estimate-based sum carry that
hindsight. `planned_week` belongs on the list because `Truncation` passes it through unrewound and
`MemberContext.plannedHours` reads it, so `planned_hrs_h{h}` carries the hindsight too: a leader usually sets
the planned week shortly before the week it names, so a training row can see a plan made after its own week
ended. Accepted on 2026-09-16 on the same grounds as the other four: a task's current value is the best
available stand-in for the value it had, and the alternative is to drop the columns. The schema document's
feature section states it under each column.

The same section states ruling E: `open_remaining_hrs` and `due_hrs_h` recompute remaining hours as
`original_estimate_hrs` minus the hours logged on the task by the row's week end, because a training row
must see what was known then and `remaining_estimate_hrs` has no history; the facts the leader reads
(`open_est_hours`, `due_hours`, `backlog_excess_hrs`, `due_excess_hrs`) keep reading `remaining_estimate_hrs`,
the application's own number. The two can differ, and the difference is by design.

### 5.4 `planned_hrs_h`

For the row's week `w` and horizon `h`: the sum of `original_estimate_hrs` over the member's tasks that are
assigned to them by the end of `w`, not finished by the end of `w`, and whose `planned_week` equals the Monday
of `w + h`. `planned_week` is a date string, the Monday of the week, as the application writes it
(`2026-08-31`); a value that is not a Monday is normalised to its Monday; null means unplanned. Zero when
nothing is planned. It reads the field as of today (section 5.3).

`TaskRow` gains `plannedWeek` (`LocalDate`, nullable); `ForecastRepository` selects the column; the seed
already writes it (`Rows.task`). `MemberContext.plannedHours(w, target)` computes it beside `dueHours`.

The facts gain, per member and window, `planned_hours`: the same sum for the tasks open at the run day and
planned for a week overlapping the window, so a narrative can say "12 h planned for this window" and the
verifier can check it. The domain and interpretation skills name it.

## 6. Assignment mode from the history

`Lifecycle.assignment` already finds the `task_history` row that gives the assignment date. That row's
`user_id` is who made the assignment:

```java
public enum Mode { SELF_PICKED, ASSIGNED, UNKNOWN }
```

- the row's `user_id` equals the task's current assignee: `SELF_PICKED`;
- the row's `user_id` is anyone else: `ASSIGNED`;
- no assignee row was found (the creation-date fallback) or the row has no `user_id`: `UNKNOWN`.

`PROJECT` and `MANUAL` are deleted, and so is the reporter-equals-assignee and has-a-parent heuristic. The
feature columns `share_self_picked_13w` and `share_assigned_13w` are the two modes' shares of the tasks in
the window whose mode is known; blank when none is. `Patterns` (facts) replaces `share_manual`,
`share_self_picked` and `share_project` with `share_self_picked` and `share_assigned` over the same rule, and
the pattern-discovery skill's table row follows ("one share is at least 0.7: dominant style"). The seed's
`WorkQueue` already writes an assignee history row per assignment but today puts the leader in `user_id`
every time (`WorkQueue.java:368`); it must write the assigner instead (the member for a self-picked task,
the leader otherwise) so that seeded data exercises both values. The
assignee row's `new_value` is the member's full name, as the application writes it (section 1 A); the
resolver handles it.

## 7. The seed's scope and how its output lands

### 7.1 Real mode writes five tables

With `--export`, `SeedGenerator.generate` returns an envelope whose `data` holds only `projects`, `tasks`,
`task_history`, `time_logs` and `personal_leaves`, in `TABLE_ORDER` order, and whose `excludedTables` lists
every other table of `TABLE_ORDER`. Users, teams, memberships, statuses, types, roles, job titles and
holidays are read from the export as inputs and never re-emitted. `Experiment.seed`'s summary prints the five
tables. Real-mode output still refuses to land inside a git repository without `--force`.

Synthetic mode is unchanged in shape: it has no existing database to lean on, so it keeps writing the
directory tables; `absences`, `user_capacity` and `team_capacity` are present and empty.

### 7.2 Projects are upserted, the rest replaced

`projects` is the one table that mixes rows the application already has (re-emitted with `next_task_number`
refreshed) and rows the seed mints. The other four tables are the seed's alone.

`SqlExportWriter` writes, for a real-mode envelope:

```sql
BEGIN;
SET search_path TO task_service;
DELETE FROM time_logs;
DELETE FROM task_history;
DELETE FROM personal_leaves;
DELETE FROM tasks;
INSERT INTO projects (...) VALUES (...)
  ON CONFLICT (id) DO UPDATE SET <every column> = EXCLUDED.<column>;
INSERT INTO tasks ...; INSERT INTO task_history ...; INSERT INTO time_logs ...; INSERT INTO personal_leaves ...;
COMMIT;
```

The deletes are emitted for exactly the envelope's tables other than `projects`, children first (the reverse
of `TABLE_ORDER`), and only when the envelope carries that table. A database that still holds comments,
attachments or notifications on old tasks makes `DELETE FROM tasks` fail inside the transaction, and nothing
is applied: the safe outcome, reported by `psql`. A synthetic envelope (every table present) is written as
today, plain inserts and no deletes, because it targets an empty database.

`ExportImporter.importAll(envelope, replace = true)` deletes only the tables the envelope carries, children
first, then inserts; it no longer wipes every table. A five-table file can therefore be loaded into an
experiment database that already has its directory. On SQLite the delete-then-insert of `projects` is the
upsert; foreign keys are not enforced there.

### 7.3 What the README says

The seed section lists the five tables, the delete-and-upsert rule, and the two-step recipe for the real
database: `seed --export <export> --format sql --out <file>` then `psql -f <file>`. The "What the seed
writes" paragraph drops capacity rows and names the leaves.

## 8. Vocabulary: "open" in the product skills

The application's task status `Open` means a task nobody is assigned to. The module's `open_tasks`,
`open_est_hours` and the `open_*` feature columns mean tasks assigned to the member and not finished, whatever
their status (`To Do`, `In Progress`, `In Review`). The code keeps its names. `whf-domain` defines the term
in its first lines ("open, of a member's task: assigned to them and not finished; not the application's
`Open` status, which means unassigned") and every skill that says "open tasks" says "assigned and not
finished" where a leader reads it. `SkillTextsTest` pins the definition. Renaming the facts (`queued_tasks`,
`queued_hours`) is recorded in the backlog as the follow-up if narratives still confuse the two.

## 9. Documentation

- `docs/design/2026-09-08-workloadhub-schema-and-feature-matrix.md`: section 1's table marks `absences`,
  `user_capacity` and `team_capacity` as not read; section 4 is replaced by a pointer to section 3 here;
  section 5's tables are updated to the 40 + 5 columns with the missing-value rule (5.2), the as-of-today
  note (5.3) and ruling E beside the columns they concern.
- `server/README.md`: the seed section (7.3), the property table unchanged, "Narrating with Copilot" gains
  `pending_leaves` and `planned_hours` in its example output where it lists facts.
- `CLAUDE.md`: the opening paragraph says capacity is 44 h over the working days minus approved leaves;
  "Where the project stands" gets this plan's paragraph when it lands; the hard rule on capacity is unchanged.
- `docs/backlog.md`: three entries under "Java migration": read `tasks.assigned_at` when it exists (A); the
  assignee history stores full names, so a duplicate or changed name falls back to the creation date and is
  reported as an unresolved assignment, and the application could store the id instead; rename the `open_*`
  facts if narratives confuse the word (section 8).
- The product skills: `whf-domain` (capacity, absence, `pending_leaves`, `planned_hours`, the definition of
  open, no `team_capacity`), `whf-forecast-interpretation` (capacity below 44 h means holidays or approved
  leave; a pending leave is a risk to name), `whf-pattern-discovery` (the two mode shares),
  `whf-rebalancing-advice` (rule 2's "not absent" reads the leave days).

## 10. Testing

Test-driven throughout; every arithmetic rule gets a jqwik property.

- `LeaveDaysTest` and `LeaveDaysPropertyTest`: the seven examples of section 2.2; for random leaves, every
  absent day is a weekday inside the period and not a holiday; the hours sum to
  `min(absence_hours, n × fullDay)` when `absence_hours` is positive and to `n × fullDay` otherwise; no day
  exceeds `fullDay`; with `end_time` the last day is the partial one, with `begin_time` only the first.
- `CapacityRuleTest` rewritten on leaves: weekly capacity, day capacity, absence hours, off days; a member
  with no leave gets 44 × working days / 5; a full-day leave zeroes the day and lands in `offDays`; a
  half-day leave halves the day and does not.
- `ForecastRepositoryTest`: approved and pending leaves are loaded, rejected ones are not; `user_capacity`
  and `team_capacity` are not queried (the test drops the tables from its SQLite fixture and the load still
  succeeds).
- `FeatureBuilderTest`: the column list (40 + 5), `planned_hrs_h` on a planned task and zero otherwise, the
  two mode shares from an assignee row with a `user_id`, each blank of section 5.2 with its non-blank
  neighbour, `proj_first_due_weeks` absent.
- `LifecycleTest`: mode from the history row's `user_id`; `UNKNOWN` on the fallback.
- `FactsBuilderTest` and `FactsToolsTest`: `pending_leaves` inside and outside the horizon, `planned_hours`,
  no `team_capacity`, the two mode shares in patterns.
- `SkillTextsTest`: the definition of open, `pending_leaves`, `planned_hours`, no `team_capacity`, no
  `share_manual`.
- `SeedGeneratorTest`: no `absences`, `user_capacity` or `team_capacity` rows; at least one half-day and one
  pending leave in a 40-user seed; every assignee history row has a `user_id`; real mode's envelope holds
  exactly the five tables and lists the others as excluded.
- `SqlExportWriterTest` and a new `ExportImporterTest`: the delete list for a five-table envelope, the `ON
  CONFLICT` clause on `projects` only, no deletes for a synthetic envelope; the importer's replace scope.
- The gate green by hand; `docs/eval` is not touched.

## 11. Out of scope

- Reading `tasks.assigned_at` (backlog).
- Renaming the `open_*` facts (backlog).
- Any change to the model, the backtest, the lags or the rolling windows.
- Per-member weekly hours other than the default: the application has no source for them now.
- Partial-day leaves finer than what `absence_hours` and the two times express; the seed's presence stays
  whole-day.

## 12. Order of work

One plan, in this order, each task with its tests first:

1. `LeaveRow`, the repository query, `ForecastData` (leaves in, absences and capacity out), `Truncation`.
2. `LeaveDays` and `CapacityRule` on the default and the leaves; `CapacityRow`, `TeamCapacityRow`,
   `AbsenceRow` deleted.
3. The seed: `AbsencePlanner` (leaves only, the half day, the pending leave), `CapacityWriter` deleted, the
   assigner on history rows, real-mode envelope of five tables, the SQL writer's deletes and upsert, the
   importer's replace scope, the driver's summary.
4. `Mode` from the history; `Lifecycle`, `TaskFacts`, `Patterns`, the two share columns.
5. The feature matrix: `proj_first_due_weeks` out, `planned_hrs_h` in (`TaskRow.plannedWeek`), the blanks of
   5.2, `Features.java`'s comment.
6. The facts: `pending_leaves`, `planned_hours`, no `team_capacity`; the skills; `SkillTextsTest`.
7. The documents of section 9, the backlog entries, the README.
8. A whole-branch review, one fix wave, the gate, then `main`.
