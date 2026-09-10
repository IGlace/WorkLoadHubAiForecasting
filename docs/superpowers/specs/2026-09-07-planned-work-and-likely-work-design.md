# Planned work in the forecast, and "what is likely to land" in the narrative: design

> Superseded on 2026-09-10: the Python service and desktop app this document describes are archived on branch `archive/python-desktop-v1`. The current design is `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`. The Java module implements its allocation rule and the likely-work narrative section; the rebalancing fit table of section 6.5 is not built.

Date: 2026-09-07. Status: decisions locked by the owner in conversation on 2026-09-07; this document
and its plan are written now and **implemented only once the real WorkloadHub export has arrived**
and passed the data gates in section 8. Inputs: the owner's decisions below, the approved design
`docs/superpowers/specs/2026-09-03-workload-forecast-design.md`, the evaluation design
`docs/superpowers/specs/2026-09-06-forecast-evaluation-and-chronos2-design.md`, and the code as of
commit `74ae2ac` on `dev`.

## 1. Goal

Today a member's two-week demand has two parts: **open hours**, the remaining work of tasks already
assigned to them, placed by the effort model; and **new hours**, the estimated hours of tasks that
will be assigned to them during the window, forecast by the champion arrival model from the member's
weekly history. The second part is forecast blind: the models see counts and dates, not what work
exists in the organisation that has not been handed out yet.

This design adds two things:

1. **Planned work** (decision 1). Tasks that already exist but are not yet assigned, such as a
   project backlog or a sprint plan, are allocated to members by a deterministic, backtestable rule
   and become a third demand component, **planned hours**, beside open and new hours. The arrival
   models then forecast only the work that appears without a backlog stage, so nothing is counted
   twice.
2. **Likely work** (decision 2). The narrative gains a section per member, "what is likely to land
   on this member", written by Copilot from new facts: the planned tasks allocated to them, the
   projects starting or active in the window, and the member's historical role on those projects and
   on projects of the same type. It is qualitative. It contains no number that is not copied from the
   facts.

## 2. Owner decisions and constraints

- **Decision 1 and decision 2 are approved and locked.** They are implemented after the real export
  lands, in that order, gated by the data checks in section 8.
- **Decision 3 is rejected permanently.** The language model will never produce a forecast number,
  neither as a candidate in the tournament nor as an experiment in the evaluation harness. The
  owner's words: "I don't want 3 and I will never do it." This closes the question; do not reopen it.
- The hard rules of the project stay: deterministic code computes every number; demand is never
  capped by capacity; everything runs on Windows in PowerShell; nothing downloads at run time; English
  and French everywhere; test-driven development with property tests for arithmetic invariants.
- The allocation rule must be **backtestable with the existing harness**: at any past origin the
  database must be reconstructible as it looked then, including which tasks were still unassigned, so
  the harness can score demand with and without planned work on the same origins.
- Copilot reads facts through tools and writes narrative, exactly as today. The likely-work section
  is validated by the same number check as the rest of the narrative.

## 3. Vocabulary

- **Planned task**: a task row that exists (has `created_at`) but has no assignee yet. Today the
  schema forbids this (`assignee_id NOT NULL`); section 4 changes it.
- **Assignment lag**: `assigned_at - created_at` in days for a task that has been assigned.
- **Fresh arrival**: a task assigned within one day of its creation (lag 0 or 1). **Backlog
  arrival**: a task assigned two or more days after its creation. The threshold is a constant,
  `BACKLOG_LAG_DAYS = 2`.
- **Planned hours**: the hours of planned tasks allocated to a member and placed into a forecast
  week by section 5. **New hours** keep their name but, once this design is active, mean fresh
  arrivals only.
- **Likely work**: the qualitative statements of section 6.

## 4. Data model, generator and readiness report

### 4.1 Schema

- `tasks.assignee_id` and `tasks.assigned_at` become nullable. A row with both null is a planned
  task; a row with exactly one null is invalid and the loader rejects it. `tasks.team_id` stays
  required: a planned task belongs to a team through its project (section 5.1) or directly.
- `tasks.status` gains no new value: a planned task has status `todo`.
- Migration: SQLite cannot drop `NOT NULL` in place, so `connect()` runs a one-time table rebuild
  guarded by `PRAGMA user_version` (version 1 today, implicitly 0; version 2 after this change):
  create `tasks_new` with the new definition, copy every row, drop, rename, recreate the two indexes,
  then set `user_version = 2`. The rebuild runs inside one transaction and is idempotent. A database
  created by the new schema file starts at version 2.
- `weekly_arrivals`, the patterns, the facts and every query that reads tasks filter on
  `assignee_id IS NOT NULL` where they mean assigned work, so existing behaviour is unchanged when no
  planned task exists.

### 4.2 Generator

`GeneratorConfig` gains `backlog_share: float = 0.35` and `backlog_lag_days: tuple[int, int] = (3, 21)`.
For each project-driven task the simulator draws whether it goes through a backlog stage with
probability `backlog_share`; if so, `created_at` is set `lag` days before `assigned_at`, `lag` drawn
uniformly from the range. At `as_of` the simulator also leaves, per active project, a backlog of
planned tasks whose creation dates fall in the last `backlog_lag_days[1]` days and whose assignment
would fall after `as_of`: these are written with null assignee and null `assigned_at`. The answer key
records, for each planned task, the member and date the simulator would have assigned it to, so the
harness truth includes them. `backlog_share = 0` reproduces today's data exactly, which is how the
existing tests keep passing.

### 4.3 Readiness report

`whf data profile [--db]` prints, and writes as JSON next to the database, the facts that section 8
gates on: task count; share of assigned tasks with lag at least `BACKLOG_LAG_DAYS`; median and 90th
percentile lag; count of planned tasks at the latest date in the data and how many carry a project;
share of tasks with a project; share of tasks with `actual_hours`; date span. It has no side effect on
the data.

## 5. Planned-work allocation (decision 1)

Module `whf/planned.py`, pure functions over DataFrames, no database access, so the property tests
can drive it directly.

### 5.1 Candidate tasks

Planned tasks whose `team_id` is the run's team, with status not `done`. `team_id` is required on
every task row, so a planned task always belongs to exactly one team; its project, when set, matters
for the weights of section 5.2 and the lag of section 5.3, not for which team receives it.

### 5.2 Who gets it: share weights with shrinkage

For a candidate task with project `P` and type `T`, each counted member `m` of the team gets a weight
from the most specific level that has history, computed on assigned tasks of the last 26 weeks:

1. `share(m | P, T)`: the member's share of the project's tasks of that type;
2. `share(m | P)`: the member's share of the project's tasks of any type;
3. `share(m | team, T)`: the member's share of the team's tasks of that type;
4. equal split across counted members.

Each level is shrunk towards the next with `k = 3` observations of prior weight, the same formula the
effort model uses for ratios: `(n_m + k * prior_m) / (n_total + k)`, where `n_m` is the member's
count at that level, `n_total` the level's total, and `prior_m` the member's weight at the next level.
Weights sum to 1 across the team by construction. A member absent from the team for the whole window
(active_to before the window, or every working day on vacation) gets weight 0 and the rest is
renormalised. The task's `estimated_hours` times the weight is the member's allocated estimate.

### 5.3 When it lands: assignment lag and placement

The expected assignment date of a planned task is `created_at + lag`, where `lag` is the median
assignment lag of the project's assigned tasks when at least five exist, else the team's, else the
whole database's, else `BACKLOG_LAG_DAYS`. An expected date before the first forecast week moves to
the first forecast week; the task is overdue for assignment, not lost.

From the expected date the allocated estimate is turned into hours the same way a new arrival is:
multiplied by the member's estimate ratio and spread evenly over the working days of the member's
cycle for that task type, skipping holidays and the member's vacations (`place_new_arrivals` already
does this; it is reused with the expected date as the start). Hours that fall after the second
forecast week are not part of the two-week demand; the facts report them as `planned_after_window`
so the narrative can say more is coming.

### 5.4 No double counting: the arrival split

`weekly_arrivals` gains a column `fresh_hours` beside `est_hours`: the estimated hours of arrivals
with lag below `BACKLOG_LAG_DAYS`. The feature matrix, the arrival models, the backtest and the
evaluation harness use `fresh_hours` as the series and the targets wherever they use `est_hours` today.
`est_hours` stays in the arrivals frame for the facts (history shown to Copilot) and the readiness
report. When no task has a lag, `fresh_hours == est_hours` and nothing changes.

The two components are therefore disjoint by construction: planned hours cover work that exists in a
backlog today; new hours cover work that appears and is assigned at once. Backlog work that does not
exist yet at run time and will be created and assigned inside the window is missed by both; the
readiness report's lag distribution says how large that blind spot is (a median lag well above
fourteen days means it is small), and the facts state the limitation.

### 5.5 Demand, bands and facts

- `forecasts` gains `planned_task_hours`; `demand_hours = open_task_hours + new_task_hours + planned_task_hours`.
- The low and high bands keep applying to new hours only. Planned hours enter demand, low and high
  alike, like open hours: they are an allocation of known work, not a forecast of its existence.
  Whether this keeps the band's coverage honest is measured, not assumed (section 5.6).
- The facts gain, per member per week, `planned_hours`; per member, `planned` (the allocated tasks:
  id, title, project id and name, type, estimated hours, the member's share rounded to two decimals,
  expected assignment date, expected week or `after_window`); per team, `planned_backlog` (per
  project: task count, estimated hours, hours allocated inside the window, hours after the window);
  and under `model`, `planned_basis` = `"share weights, 26-week window, shrink k=3"` plus the
  `limitations` sentence extended with the blind spot above.
- The component is active whenever `PLANNED_WORK_ENABLED` is true (a constant in `whf/planned.py`,
  true until the decision of section 5.6 says otherwise) and the database holds at least one planned
  task or one backlog arrival; otherwise every new column is zero and every new fact list is empty,
  and the run is identical to today's. There is no user setting.

### 5.6 Evaluation

- `truncated_copy(conn, as_of)` reconstructs the backlog: tasks created after `as_of` are dropped;
  tasks created on or before `as_of` and assigned after it are kept with `assignee_id` and
  `assigned_at` set to null and status `todo`; the rest as today.
- `whf eval` gains `--no-planned`, which runs the demand replay with the allocation switched off,
  and the summary gains a section "Planned work" with: share of backlog arrivals in the data, median
  lag, and for each of demand MAE, band coverage, overload precision and recall, the value with and
  without planned work on the same origins. The arrival-level table states that the series is
  `fresh_hours`.
- The decision rule, recorded in the summary and the backlog once run on the real data: planned
  work stays on by default if demand MAE improves and coverage does not drop by more than five
  points; otherwise the component is left inactive (a constant, `PLANNED_WORK_ENABLED`) and the
  reason is written down.

### 5.7 Approaches considered

- **Share weights with shrinkage** (chosen): transparent, needs little history, degrades to an equal
  split, explainable in one sentence in the narrative.
- **A learned assignment classifier** (gradient boosting from project, type, priority, creator to
  assignee): more accurate with years of data, but opaque and heavier to backtest. Left as a later
  candidate only if the harness shows the share rule leaving accuracy on the table.
- **Subtracting planned hours from the arrival forecast** instead of splitting the series: simpler,
  but a heuristic that can go negative and cannot be scored cleanly. Rejected.

## 6. Likely work in the narrative (decision 2)

### 6.1 Facts

Per member, a `likely_work` object:

- `planned`: the list from section 5.5 (empty when the component is inactive);
- `project_roles`: for each project active or starting in the window and linked to the team, the
  member's share of that project's assigned tasks over the last 26 weeks, their dominant task types
  there (up to three, with counts), and the project's phase (`starting`, `active`, `ending`);
- `similar_projects`: for each project starting in the window, the member's share and dominant types
  on past projects of the same `type`, over the whole history;
- `recent_mix`: the member's task-type counts over the last 13 weeks.

Per team, `planned_backlog` from section 5.5.

### 6.2 Narrative contract

`MemberNarrative` gains `likely_work: list[LikelyWork]`, at most four items, default empty, where
`LikelyWork` has `statement` (1 to 300 characters), `evidence` (1 to 300 characters, naming the fact
it rests on) and `confidence` in `low | medium | high`. The existing number verification applies to
both strings: any number written must exist in the facts. Older stored narratives without the field
still load.

### 6.3 Tools, prompt and product skill

- Two tools: `get_member_likely_work(member_id)` and `get_planned_backlog()`.
- The user prompt's procedure adds `get_member_likely_work` to the per-member steps and
  `get_planned_backlog` after the project timelines.
- A new product skill `whf-likely-work` states the rules: reason only from the planned list, the
  project phases and the member's historical role; say "likely" or "probably", never "will"; write
  `high` confidence only when a planned task is allocated to the member, `medium` when a starting
  project matches a role they held before, `low` otherwise; when the planned list is empty, say that
  no planned work is recorded and the expectation rests on history alone; never write an hours figure
  that is not in the facts.
- `whf-forecast-interpretation` gains one line explaining `planned_hours` as allocated backlog work,
  and `whf-domain` adds the vocabulary of section 3.

### 6.4 Desktop app

The team page's member card and the member detail page show "Likely to land" under the patterns, one
line per item with its confidence badge, in English and French. The week table gains a "Planned"
column beside "Open" and "New" when any row has planned hours. Older runs without the field show
nothing new.

### 6.5 Rebalancing fit (added 2026-09-07 at the owner's request)

Today a rebalancing move is bounded only by hours: the source must have overload, the target spare
capacity, in the same week. The product skill asks Copilot to prefer a target who has done the task
type before, but the facts give it nothing better than "has a cycle-time statistic for that type",
and nothing at all about projects. This section gives the move a deterministic **fit** it must
respect.

- **Fit table.** For every open task of every overloaded member (status not `done`) and every
  underloaded member of the same team, the service computes one row:
  `task_id`, `from_member_id`, `to_member_id`, `project_share` (the target's share of the task's
  project's assigned tasks over the last 26 weeks, 0 when the task has no project or the target none
  of its tasks), `type_tasks` (the target's completed tasks of that type over the whole history),
  `type_ratio` (the target's estimate ratio for that type from the effort model, shrunk like every
  ratio), `vacation_days` (the target's vacation days in the move's week, per forecast week), and
  `score = 0.5 * min(1, type_tasks / 5) + 0.5 * project_share`, rounded to two decimals. The table
  lives in the facts under `rebalancing_candidates.fit` and reaches Copilot through the existing
  `get_rebalancing_candidates` tool.
- **Contract.** `RebalancingMove` gains `task_ids: list[int]` (default empty). When given, every id
  must be an open task of the source member; the move's hours may not exceed the sum of those tasks'
  estimated hours times the source's estimate ratio, rounded up to the nearest half hour, so a move
  is a set of named tasks rather than a loose number.
- **Validation.** For each named task, if the target's fit score is 0 and another underloaded member
  in the same week has a score above 0 for that task and spare hours at least the move, the answer
  is rejected with a message naming the better-fitting member. A zero-fit target is allowed only when
  nobody fits better. Moves without `task_ids` are validated as today.
- **Skill.** Rule 2 of `whf-rebalancing-advice` becomes: rank targets by `score`, cite the score's
  components in the reason (project share, tasks of the type), and set confidence `high` only when
  the chosen target has the best score among those with enough spare hours, `medium` when the score
  is above 0 but not the best, `low` when it is 0.
- **App.** The rebalancing page lists each move's task titles under the move, looked up from the
  facts. No new page.
- **Scope.** Moves stay inside one team. Seniority and skills that are not visible in the task
  history are still not modelled; `type_tasks` and `project_share` are the observable proxies.

## 7. Non-goals

- Any use of the language model to produce hours, counts or dates (decision 3, closed).
- Recommending who should be assigned a planned task; the allocation is an expectation for the
  forecast, not an instruction.
- Importing the WorkloadHub export into the service database. That is prerequisite work with its own
  design once the export's format is known; this document assumes the export has landed in the
  `tasks`, `projects`, `project_teams` and `members` tables with the columns already defined.
- Changes to capacity, holidays, vacations or overrides.

## 8. Data gates and order of work

1. **Gate A, import.** The real export is loaded into the service database and `whf data profile`
   runs on it.
2. **Gate B, decision 1.** Decision 1 is implemented when the profile shows either at least one
   planned task with a project at the latest date, or at least 10% of assigned tasks with a lag of
   `BACKLOG_LAG_DAYS` or more. Below both thresholds the organisation assigns work at creation, the
   component would always be inactive, and only the schema change, the generator option, the arrival
   split and the harness reconstruction are still worth building (they cost little and keep the door
   open). The plan marks which tasks fall in that reduced set.
3. **Decision 2 is implemented in every case.** Without planned tasks its facts hold the project
   roles, similar projects and recent mix, and the skill tells Copilot to say so.
4. The harness is run on the real data with and without planned work; the summary is committed under
   `docs/eval/` and the decision of section 5.6 is recorded in `docs/backlog.md`.

## 9. Testing

- Unit tests for the migration (a version-1 database with rows becomes version 2 with the same rows,
  twice in a row without change), the loader's rejection of half-null rows, the generator's
  `backlog_share = 0` equivalence and its planned tasks, the readiness report's numbers on a known
  fixture.
- Property tests (hypothesis): allocation weights sum to 1 per task and are non-negative for any
  history; planned hours are conserved: the sum over members and weeks plus `planned_after_window`
  equals estimate times ratio for any task, calendar and vacation set; `fresh_hours + backlog hours ==
  est_hours` for any task set; the truncated copy at an origin contains exactly the tasks created on or
  before it, with those assigned later shown as planned.
- Pipeline tests: a database with planned tasks yields `planned_task_hours > 0` for the weighted
  member, demand equals the three components, and the facts carry the new lists; a database without
  any yields the same forecasts as before the change, row for row.
- Narrative tests: the schema accepts and rejects `likely_work` as specified; the verifier catches an
  invented hours figure in a statement; the fake session exercises the two new tools.
- Fit tests: the fit table has one row per (open task of an overloaded member, underloaded member);
  `score` is in [0, 1] and is 1 only for a target with at least five tasks of the type and the whole
  project share (property test); a move naming a task that is not the source's is rejected; a move to
  a zero-fit target is rejected when a better-fitting target with spare hours exists and accepted
  when none does; a move whose hours exceed the named tasks' corrected estimate is rejected.
- App tests: the member card renders the items with badges in both languages; the week table shows
  the planned column only when needed.
- The harness A/B on generated data with `backlog_share = 0.35` as a smoke test of the whole chain
  before the real data.
