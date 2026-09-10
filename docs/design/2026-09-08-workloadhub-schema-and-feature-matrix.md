# WorkloadHub schema mapping and the feature matrix for the server-side forecast

Date: 2026-09-08. Status: design, written from the owner's export of the `avl_workloadhub`
database (`task_service` schema, exported 2026-09-08T16:45 +01:00, 23 tables). Supersedes the
generated-data assumptions of `docs/design/2026-09-07-forecasting-internals.md` part 1 for the
server-side, Java implementation inside the Spring Boot application. Decisions still open are listed
in section 7 and must be answered before the implementation plan is written.

## 1. What the export contains, and what it does not

| Table | Rows | Use in the forecast |
|---|---|---|
| `tasks` | 10 | the work: estimates, remaining estimates, planned week, due date, status, type, priority, assignee, reporter, parent |
| `task_history` | 11 | status and assignee transitions with timestamps: the source of assignment dates and of start and finish dates |
| `time_logs` | 1 | hours actually spent per user, task and day: the truth for demand |
| `task_statuses` | 9 | status → category `TO_DO`, `IN_PROGRESS`, `DONE` |
| `task_types` | 12 | Story, Bug, Task, Epic, Improvement, New Feature, Change Request, Incident, Risk, Spike, Test, Sub-task |
| `users` | 264 (6 active) | members, roles (`MEMBER`, `TEAM_LEADER`, `SKILL_TEAM_LEADER`, `CENTER_MANAGER`, `ADMIN`, `VIEWER`), job title, department, manager |
| `teams`, `team_members` | 3, 3 | teams with a manager and a parent team; membership with a join date |
| `projects` | 7 | key, name, status (`ACTIVE`, `PLANNING`), owning team; **no start or end date** |
| `user_capacity` | 2 | per user and week: base hours, absence hours, available hours |
| `team_capacity` | 2 | per team and week: total and allocated hours |
| `absences` | 4 | per user and day: hours and type |
| `holidays` | 16 | date ranges with a status (`CONFIRMED`, `PENDING`) and a country code |
| `personal_leaves` | 0 | (empty) |
| `job_titles`, `user_roles`, `notifications`, `task_comments`, `sync_metadata`, others | | not used |

Three facts matter for the design:

1. **There is no history yet.** Every task was created on 2026-09-03 and the export is five days
   old. The arrival models need at least 13 complete weeks of assignments per member to run a backtest
   (the `MIN_WEEKS_BEFORE_ORIGIN` rule) and the more the better. Until either the application has
   accumulated that history or an older system's history is backfilled, the forecast cannot be
   validated on real data. The design below is written to be built now and validated then.
2. **Several signals the generated data lacked exist here** and improve the forecast: the remaining
   estimate per task, a planned week per task, logged hours per day, status transitions with times,
   weekly capacity records with absence hours, and a reporter on each task.
3. **Two signals the generated data had are missing:** project start and end dates, and an explicit
   assignment date. Section 3 says how each is derived.

The export file itself is Windows-1252 encoded (byte `0x92` in "New Year’s Day"); the exporter
should write UTF-8. The Java implementation reads the database, not this file, so this only affects
the evaluation import.

## 2. Identity and roles

- A **member** is a `users` row with `active = true` and role `MEMBER` or `TEAM_LEADER`, linked to a
  team through `team_members`. Team leaders do technical work and are counted, as in the current
  design. `SKILL_TEAM_LEADER`, `CENTER_MANAGER`, `ADMIN` and `VIEWER` are never counted.
- A **team** is a `teams` row; `manager_id` is its leader; `parent_team_id` gives the department
  level (here `Engineering` above `Backend Team` and `Frontend Team`). The forecast runs per team;
  the department view sums its child teams.
- Ids are UUID strings, not integers. The Java code keys everything by UUID; the facts sent to
  Copilot use the task `key` (`WH-1`) and the user's `full_name`, never the UUID.
- The 258 deactivated users come from the directory sync; a member who leaves keeps their history
  and drops out of capacity from `deactivated_at`.

## 3. Task lifecycle: the dates the forecast needs

| Forecast concept | Source in the schema | Rule |
|---|---|---|
| created | `tasks.created_date` | as is |
| **assigned** | `task_history` rows with `field_name = 'assignee'` | the `changed_at` of the latest transition whose `new_value` is the current assignee; when no such row exists, the task was assigned at creation: `created_date` |
| started | `tasks.started_date`, else the first `task_history` status transition into an `IN_PROGRESS` category | |
| finished | `tasks.finished_date`, else the first transition into a `DONE` category; `reopened_from_done` and `last_reopened_at` reopen it | |
| status | `task_statuses.category` of `task_status_id` | `TO_DO` (Open, To Do, On Hold), `IN_PROGRESS` (In Progress, In Review, Testing, Blocked), `DONE` (Done, Closed) |
| estimate | `original_estimate_hrs` | the arrival series target, in hours |
| remaining | `remaining_estimate_hrs` | replaces the "remaining fraction of the cycle" heuristic of the current effort model |
| actual | sum of `time_logs.hours` for the task, per user | replaces `actual_hours`; also gives hours per day, which the current design had to assume |
| due | `due_date` | as is |
| planned week | `planned_week` | a Monday; the week the team leader intends the work for |
| project | `project_id` → `projects.team_id`, `status` | no dates: the project phase features of section 5 use task-derived dates |
| type | `task_types.name` | grouped into four families for the features: **delivery** (Story, New Feature, Task, Improvement, Change Request), **defect** (Bug, Incident), **container** (Epic, Sub-task's parent), **support** (Spike, Test, Risk) |
| priority | `priority` | `HIGHEST`, `HIGH`, `MEDIUM`, `LOW`, ordinal 4..1 |
| assignment mode | derived | **self-picked** when `reporter_id = assignee_id`; **manual** when the reporter is a team leader or manager; **project** when `parent_task_id` is set (a sub-task of an Epic) |
| archived | `archived` | excluded everywhere |

An unassigned task is a row with `assignee_id` null; the history shows the application produces them
(INF-2 went to "None" and back). They are the backlog of the planned-work design, and the schema
already supports them, so no migration is needed on the WorkloadHub side.

## 4. Calendar and capacity

- Weeks start on Monday, as the application's own `week_start` columns do.
- **Working days** exclude holidays with `status = 'CONFIRMED'` and `active = true`, over
  `start_date..end_date`. `PENDING` holidays are ignored for the forecast and named in the facts as
  uncertain, so Copilot can mention them.
- **Capacity** for a member and week, in this order: `user_capacity.available_hrs` for that week
  when a row exists; otherwise `base_capacity_hrs` of the member's latest row, else 40, minus the
  member's `absences.hours` in that week, scaled by working days over 5. The current 44-hour default
  is replaced by what the application records; the base is 40 in the export.
- `team_capacity.total_capacity_hrs` and `allocated_hrs` are shown in the facts as the team's own
  plan for comparison; they are not used as inputs.

## 5. The feature matrix

One row per counted member per Monday-starting week, from the member's first assignment (or team
join date) to the origin. All features are computed from data dated on or before the row's week,
except the `_h{h}` columns, which describe the **target** week `w + h` using only facts known at
`w` (plans, due dates, capacity records, holidays). Horizons `h = 1, 2, 3`.

### 5.1 Target

| Column | Meaning |
|---|---|
| `target_h{h}` | estimated hours (`original_estimate_hrs`) of tasks assigned to the member in week `w + h`, using the assignment date of section 3. This is what the model predicts. Zero weeks are real zeros. |

### 5.2 The member's own arrival history

| Column | Meaning |
|---|---|
| `lag1` .. `lag4`, `lag8`, `lag13` | estimated hours assigned in the row's week and 1, 2, 3, 7, 12 weeks before it. The recent level and the same week of the last quarter. |
| `roll_mean_4`, `roll_mean_8`, `roll_mean_13` | average weekly assigned hours over the last 4, 8, 13 weeks: the member's normal load at three time scales. |
| `roll_std_4`, `roll_std_8`, `roll_std_13` | how much that load varies: a member with a steady 20 h and one alternating 0 and 40 h have the same mean and very different risk. |
| `weeks_since_last_arrival` | weeks since the member last received anything; long gaps mean an intermittent pattern. 52 when never. |
| `arrivals_13w` | number of tasks assigned in the last 13 weeks; with the hours, gives the typical task size. |
| `share_defect_13w`, `share_delivery_13w`, `share_support_13w` | share of the last 13 weeks' tasks by type family. Defect-heavy members receive work that is unplanned and urgent. |
| `share_high_priority_13w` | share of `HIGHEST` and `HIGH` tasks in the last 13 weeks. |
| `share_self_picked_13w`, `share_manual_13w`, `share_project_13w` | assignment style from section 3. A self-picking member's arrivals follow their own throughput; a manually assigned member's follow the leader's planning. |
| `reopen_rate_13w` | share of the member's finished tasks reopened from done: rework generates arrivals that no plan announces. |

### 5.3 Throughput and open work at the origin

These did not exist on the generated data. They describe how much the member is carrying and how
fast they clear it, which the arrival forecast alone does not see.

| Column | Meaning |
|---|---|
| `logged_hours_lag1` .. `logged_hours_lag4` | hours from `time_logs` in the row's week and the three before: what the member actually worked, week by week. |
| `open_tasks` | tasks of the member not in a `DONE` category at the end of the week. |
| `open_remaining_hrs` | sum of their `remaining_estimate_hrs`: the backlog in hours, as the application tracks it. |
| `overdue_open` | of those, how many have a `due_date` before the end of the week. |
| `in_progress_tasks` | open tasks in an `IN_PROGRESS` category: work started, not queued. |
| `estimate_ratio_13w` | logged hours over original estimate for tasks finished in the last 13 weeks, the member's estimation bias; 1.0 when unknown. |
| `cycle_days_13w` | median days from assignment to finish over the same tasks; the member's typical turnaround. |

### 5.4 What is already known about the target week

The strongest new signals. At the origin the application already holds tasks planned or due for the
weeks ahead; a model that sees them predicts arrivals far better than history alone.

| Column | Meaning |
|---|---|
| `planned_hrs_h{h}` | estimated hours of the member's tasks (any status but `DONE`) whose `planned_week` is the target week. The leader's stated intention for that week. |
| `planned_remaining_h{h}` | the `remaining_estimate_hrs` of the same tasks: what is left to do of the plan. |
| `due_hrs_h{h}` | remaining hours of the member's open tasks with a `due_date` inside the target week: deadline pressure. |
| `team_planned_hrs_h{h}` | the whole team's planned hours for the target week; a member's share of a heavy team week tends to rise. |
| `team_backlog_unassigned_hrs` | estimated hours of the team's tasks with no assignee at the origin: the pool the planned-work allocation draws from. |
| `proj_active` | number of the team's projects with status `ACTIVE` that have at least one open task at the origin. |
| `proj_planning` | number with status `PLANNING`: the pipeline that will start producing tasks. |
| `proj_first_due_weeks` | weeks from the row's week to the nearest `due_date` among the team's open tasks, per project, minimum over projects. Replaces the missing project deadline. |

### 5.5 Availability of the target week

| Column | Meaning |
|---|---|
| `working_days_h{h}` | weekdays in the target week minus confirmed holidays. |
| `absence_hrs_h{h}` | the member's recorded absence hours in the target week, from `user_capacity.absence_hrs` when a row exists, else the sum of `absences.hours`. |
| `available_hrs_h{h}` | capacity of section 4 for the target week. A leader assigns less to a member who is away. |

### 5.6 Identity

| Column | Meaning |
|---|---|
| `member_id`, `team_id` | categorical. The booster learns per-member and per-team levels from them. |
| `role` | `MEMBER` or `TEAM_LEADER`, categorical: leaders receive a different mix. |
| `job_title` | categorical when populated in the directory; absent rows are one category. |
| `tenure_weeks` | weeks since `team_members.joined_at`: newcomers ramp up. |
| `week_of_year` | seasonality. |

That is 46 columns per horizon after dropping the three planned-week columns, against 25 today. The
booster handles the width; what matters is that every column is available at prediction time from the
database alone, which section 8 states as a test.

Truncation (section 3) replays the task and transition history as of the origin week, which is what
keeps the own-history and throughput columns leak-free. It does not rewind everything: a task's
present-day `reopened_from_done` flag, its project's current `status`, its current `due_date`, and its
`original_estimate_hrs` are all read as they stand today, not as they stood at the origin (only
`remaining_estimate_hrs` is recomputed from the hours logged by the cutoff). The leakage guarantee
(section 8) holds only up to that list — a column built from one of those four fields carries a small
amount of hindsight the replay does not remove.

## 6. Demand, bands and the narrative facts on this schema

- **Open hours** are `remaining_estimate_hrs` of the member's open tasks, placed from the first
  forecast week to `max(planned_week, due_date + member's median lateness)`, evenly over working
  days minus absences. No remaining-fraction heuristic: the application knows the remaining hours.
- **New hours** are the model's `target` prediction for the target week, scaled by the member's
  `estimate_ratio_13w` and spread over their `cycle_days_13w` from the target week, as today.
- **Planned hours** for unassigned tasks follow the planned-work design's allocation, with
  `planned_week` as the expected assignment week when set, else the lag rule.
- **Demand** is their sum; **overload** is demand above the capacity of section 4, never capped.
- **Band**: 10th and 90th percentiles of the model's backtest residuals per horizon, applied to new
  hours only, as today.
- **Truth for evaluation** is `time_logs` summed per member and week: the first dataset where the
  demand-level score is against real hours, not an even spread.
- **Facts for Copilot**: as today, plus per member the planned and due hours of each week, the
  reopened tasks, the logged hours of the last four weeks, the team's own `team_capacity` plan, and
  the pending holidays. Task keys and names, never UUIDs.

## 7. Open decisions (answers needed before the plan)

1. **History.** Does an older system hold past tasks, assignments and time logs that can be
   backfilled into these tables? If yes, the forecast can be validated at once; if no, the code is
   built now and the first backtest is possible about 13 weeks after go-live, with the model gaining
   accuracy for a year after that.
2. **Time logs.** Will members log hours consistently? They are the truth for evaluation and the
   source of `estimate_ratio` and throughput. If logging is sparse, the fallback is
   `original_estimate − remaining_estimate` at finish, which the schema also supports.
3. **Planned week.** Is `planned_week` set by the leader when the task is created, and is it moved
   when the plan slips? The section 5.4 features assume it reflects intention at the origin.
4. **Unassigned tasks.** Are tasks created in a backlog before assignment as a normal practice, or
   is INF-2's null assignee an exception? This decides whether the planned-work allocation is active.
5. **Java build.** Maven or Gradle, Java version, Spring Boot version, database engine (the UUID and
   timestamp shapes suggest PostgreSQL) and whether the forecast module may hold its own tables for
   runs, forecast rows, facts and narratives, or must map onto tables the WorkloadHub team defines.
6. **Copilot access.** Who registers the GitHub OAuth App for the organisation, and where does
   WorkloadHub store each user's GitHub token?

## 8. Tests the implementation must carry

- Every feature column is computable from the database as of the origin, with no value dated after
  it: a test builds the matrix at an origin from a snapshot and again from the full data truncated to
  that origin, and the two are equal.
- `target_h{h}` at week `w` equals `lag1` at week `w + h` for every member (property test).
- Capacity never exceeds base hours and never goes below zero; absence hours are subtracted once.
- The assignment date of a task with several assignee transitions is the latest transition to the
  current assignee, and a task with none uses its creation date.
- Type families partition the twelve task types with none left out.
