# WorkloadHub forecast module in Java: design

Date: 2026-09-09. Status: sections 1 to 3 approved by the owner on 2026-09-09; sections 4 to 15
consolidate decisions taken in the discussion of 2026-09-08 and 2026-09-09 and await the owner's
review. Supersedes, for the server, the desktop design of `2026-09-03-workload-forecast-design.md`;
builds on `docs/design/2026-09-08-workloadhub-schema-and-feature-matrix.md` (the schema mapping)
and `docs/design/2026-09-07-forecasting-internals.md` (the algorithms).

## 1. Goal and owner decisions

The forecast moves from a Windows desktop application with a Python service into a Java module that
runs inside the WorkloadHub Spring Boot application on a Linux server, reads the application's own
PostgreSQL database, and uses each user's own GitHub Copilot seat for the narrative. The WorkloadHub
front end is built by another team; this module gives them a service interface, an optional REST
surface, and a command-line runner for experiments.

Decisions taken by the owner, all final:

| Topic | Decision |
|---|---|
| Server | Linux. Development on Windows through WSL. |
| Database | PostgreSQL, the schema of `schema.sql` (schema `task_service`, 24 tables). The demo runs the same tables on SQLite. |
| Model | Gradient boosting only, through XGBoost4J. The seasonal-naive floor stays as the safety comparison in the backtest. TSB and Chronos-2 are dropped. |
| Copilot | The user's own seat, through the Copilot SDK for Java, with a per-user OAuth token stored encrypted in `users.github_token`. The owner registers the GitHub OAuth App. |
| Narrative | Copilot writes text from facts and never produces a forecast number (permanent rule). English and French. |
| History | None exists. A seed command generates 52 weeks of realistic history for the 264 real users from the owner's export; that file is the official evaluation data before production. |
| Time logs | Members must log hours. When a finished task has none, actual hours fall back to original estimate minus remaining estimate and the task is flagged. |
| Planned week | The three `planned_week` features are dropped from the matrix. Due-date and project-status features stay. |
| Backlog | Tasks are created unassigned and assigned when they start, so the planned-work allocation of `2026-09-07-planned-work-and-likely-work-design.md` is active, with the lag rule as the only expected-assignment-week rule. |
| Integration | The module may create its own tables. The host calls a Java interface; the front end is someone else's. |
| Old code | The Python service, the Electron app and the installer are frozen and archived on a branch. `dev` and `main` carry only the Java project and the documentation. |

## 2. Architecture and repository layout (approved)

Two Maven modules under `server/`, Java 21, Spring Boot 4.1.x, base package `com.workloadhub.forecast`
(renamable to the host's package once known).

```text
server/
  pom.xml                                   parent: versions, plugins, modules
  forecast-core/                            artifact workloadhub-forecast-core, the host's dependency
    src/main/java/com/workloadhub/forecast/
      ForecastAutoConfiguration.java        registered in META-INF/spring/...AutoConfiguration.imports
      api/        ForecastService, GitHubTokenStore, request and result records, ForecastException
      web/        ForecastController (enabled by whf.web.enabled=true)
      data/       repositories over the WorkloadHub tables (JdbcClient), row records
      calendar/   working days, holidays, weeks
      capacity/   capacity per member and week
      lifecycle/  task dates (assigned, started, finished), families, assignment mode, actual hours
      features/   weekly series, feature matrix
      model/      ArrivalModel, SeasonalNaive, XgboostArrival, EffortModel, ModelUnavailable
      backtest/   rolling backtest, champion selection
      planned/    planned-work allocation
      pipeline/   run orchestration, demand, bands, placement, persistence
      facts/      narrative facts builder
      ai/         Copilot client, session, tools, prompt, contract, verification, usage, progress
      store/      the module's own tables, Flyway migrations, token encryption
    src/main/resources/
      db/forecast/postgresql/V1__forecast_tables.sql
      db/forecast/sqlite/V1__forecast_tables.sql
      skills/whf-*/SKILL.md                 the five product skills, moved from the Python package, embedded in the system message
      META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  forecast-cli/                             artifact workloadhub-forecast-cli, runnable jar for WSL
    src/main/java/com/workloadhub/forecast/cli/
      ForecastCli.java                      Spring Boot main with picocli
      commands: InitDb, Import, Seed, Run, Eval, Narrate, Copilot, Runs
    src/main/resources/
      schema/workloadhub-sqlite.sql         the owner's schema translated to SQLite
      application.yml                       SQLite defaults
```

`forecast-core` depends on `spring-boot-starter-jdbc`, `flyway-core` with the PostgreSQL plugin,
`xgboost4j_2.12` 3.4.0, `copilot-sdk-java` 1.0.13-preview.6 (the latest on Maven Central at
writing; every 1.0.13 build is a preview) plus its runtime artifact `copilot-sdk-java-runtime`
with classifier `linux-x64` (44 MB: the Copilot CLI binary and its Node runtime, unpacked by the SDK
into `COPILOT_HOME` on first use, so nothing is downloaded at run time), Jackson 3 and the PostgreSQL
driver as `runtime` optional. Verified on 2026-09-09 in this environment: the versions resolve
together under Spring Boot 4.1.1 (JUnit 6.0.3, Flyway 12.4.0, Jackson 3.1.5), jqwik 1.10.1 runs on
the JUnit 6 platform, and XGBoost4J 3.4.0 trains a Poisson booster with categorical feature types
on Linux x86-64 deterministically. It has no web dependency; the controller is
compiled against `spring-web` marked optional and only activates when the host has Spring MVC.
`forecast-cli` adds picocli 4.7.7 and `sqlite-jdbc` 3.53.4.0.

The host calls `ForecastService` (section 11). Everything below it is package-private except the
`api` records, so the host cannot depend on internals.

## 3. Database (approved)

### 3.1 The WorkloadHub tables

Read only. The SQL is portable between PostgreSQL and SQLite: no vendor functions, UUIDs and
timestamps bound as strings, dates as ISO text, booleans as `true/false` or `1/0` through one
`Dialect` helper. On SQLite the demo creates the 24 tables from `workloadhub-sqlite.sql`, generated
once from the owner's dump by a documented translation (uuid → TEXT, timestamp → TEXT ISO 8601,
date → TEXT, double precision → REAL, boolean → INTEGER, varchar → TEXT, same names, same primary
and unique keys, foreign keys kept).

Every repository test runs on SQLite; the same tests run on PostgreSQL through Testcontainers when
Docker is reachable and are skipped with a message otherwise.

### 3.2 The module's tables

Created by Flyway from `db/forecast/<vendor>/`, with `table = forecast_schema_history` and
`baselineOnMigrate` at version 0 (the WorkloadHub tables already exist, so Flyway must accept a
non-empty schema and still apply V1), so the host's
own Flyway or Liquibase history is untouched. Names carry the `forecast_` prefix.

```sql
CREATE TABLE forecast_runs (
  id              uuid PRIMARY KEY,
  team_id         uuid NOT NULL,
  requested_by    uuid NOT NULL,
  as_of           date NOT NULL,
  status          varchar(16) NOT NULL,      -- QUEUED, RUNNING, DONE, FAILED
  forced_model    varchar(32),
  champion_model  varchar(32),
  champion_mase   double precision,
  backtest_json   text,                      -- (model, origin, horizon, mae, mase) rows and residual bands
  error           text,
  created_at      timestamp NOT NULL,
  finished_at     timestamp
);
CREATE TABLE forecast_member_weeks (
  run_id          uuid NOT NULL REFERENCES forecast_runs(id),
  user_id         uuid NOT NULL,
  week_start      date NOT NULL,
  open_hrs        double precision NOT NULL,
  new_hrs         double precision NOT NULL,
  planned_hrs     double precision NOT NULL,
  low_hrs         double precision NOT NULL,   -- band on new_hrs only
  high_hrs        double precision NOT NULL,
  capacity_hrs    double precision NOT NULL,
  overload_hrs    double precision NOT NULL,
  working_days    integer NOT NULL,
  absence_hrs     double precision NOT NULL,
  PRIMARY KEY (run_id, user_id, week_start)
);
CREATE TABLE forecast_facts (
  run_id          uuid PRIMARY KEY REFERENCES forecast_runs(id),
  facts_json      text NOT NULL,
  created_at      timestamp NOT NULL
);
CREATE TABLE forecast_narratives (
  id              uuid PRIMARY KEY,
  run_id          uuid NOT NULL REFERENCES forecast_runs(id),
  language        varchar(2) NOT NULL,       -- en, fr
  model           varchar(64),
  narrative_json  text NOT NULL,
  verification_json text NOT NULL,
  usage_json      text NOT NULL,
  created_at      timestamp NOT NULL
);
ALTER TABLE users ADD COLUMN github_token varchar(512);
ALTER TABLE users ADD COLUMN github_token_updated_at timestamp;
```

The SQLite variant uses TEXT, REAL and INTEGER with the same names. Demand is never capped:
`overload_hrs = max(0, open + new + planned − capacity)` is stored, and the three components are
stored uncapped.

### 3.3 Tokens

`GitHubTokenStore.save(userId, token)` encrypts with AES-256-GCM, a random 12-byte nonce per value,
key from `whf.token-key` (32 bytes, base64) and stores `v1:` + base64(nonce + ciphertext).
`GitHubTokenStore.load(userId)` decrypts for the requesting user only, and the module calls it in
exactly one place: when it opens the Copilot session for a run that user requested. The token is
never logged, never in facts, never in a result record. Accepted forms are the OAuth `gho_`, the
GitHub App `ghu_` and fine-grained `github_pat_` tokens; a classic `ghp_` token is refused at save.
Refresh belongs to the host's OAuth flow; the module reports `TOKEN_MISSING` or `TOKEN_REJECTED`
in the run's error and the host re-runs the OAuth flow.

## 4. The seed generator (approved)

### 4.1 Inputs and structure

`seed` reads the owner's JSON export (envelope `{database, schema, exported_at, excluded_tables,
data: {table: [rows]}}`) and keeps, unchanged: `users` (identities, job titles, departments,
managers), `job_titles`, `user_roles`, `task_types`, `task_statuses`, `holidays`, the 7 `projects`
and 3 `teams`. Everything else is regenerated.

Structure derived from the directory data:

- **Teams.** One team per distinct `manager_id` among users (18 in the export), named after the
  manager's department code and name ("CT2 · Ben Salah"), `manager_id` set, the manager's role set to
  `TEAM_LEADER`. A department head's direct reports (the managers) form a team too, led by the head. A `team_members` row per report with `joined_at` = start of the history or a later
  random Monday for a 10 % minority (tenure ramps). The 3 existing teams stay for their 6 users.
- **Departments.** One parent team per distinct department (spelling variants merged by their code:
  `CT2`, `SD1`, `DAI`, ...), `parent_team_id` on each manager team; its `manager_id` is the user in
  the department whose job title contains "Skill Team Leader", else the manager with most reports;
  that user's role becomes `SKILL_TEAM_LEADER`. The `Engineering Center Manager` keeps
  `CENTER_MANAGER`; the `ADMIN` stays.
- **Users without a manager** (44) join their department's team directly as members. Users with
  neither manager nor department (31) join one generated "Unassigned" department team.
- **Activation.** Every user becomes `active = true`, `deactivated_at = null`, except a random 3 %
  who leave during the year: `deactivated_at` is set and their history stops there.

### 4.2 Work families

Job titles map to families by whole-word, case-insensitive keyword match, in this order of
precedence (the first family with a matching word wins; `data` and `support` come early so that
"Data Analyst & SW Developer" is data, not electronics, and "IT System Administrator" is support,
not systems):

| Family | Title keywords | Type mix (delivery / defect / support / container) | Median estimate h | Self-picked share | Weekly assigned h (mean) |
|---|---|---|---|---|---|
| calibration | calibration | 0.55 / 0.20 / 0.20 / 0.05 | 12 | 0.35 | 32 |
| data | data, ai, dai | 0.55 / 0.20 / 0.20 / 0.05 | 8 | 0.55 | 28 |
| support | hr, admin, administration, administrator, finance, purchasing, it, facility, specialist, generalist, officer | 0.50 / 0.20 / 0.30 / 0.00 | 4 | 0.70 | 20 |
| systems | system, systems | 0.60 / 0.15 / 0.20 / 0.05 | 16 | 0.30 | 30 |
| electronics | electric, electronics, ee, software, sw, functions | 0.50 / 0.30 / 0.15 / 0.05 | 10 | 0.40 | 30 |
| validation | validation, verification, homologation, fleet, test | 0.45 / 0.35 / 0.15 / 0.05 | 12 | 0.30 | 30 |
| design | design, simulation, cfd, dmu | 0.60 / 0.10 / 0.25 / 0.05 | 20 | 0.35 | 30 |
| coordination | project, coordination, leader, manager, workshop, center | 0.40 / 0.10 / 0.45 / 0.05 | 6 | 0.60 | 16 |
| unknown | no title or no match | the systems parameters | 16 | 0.30 | 30 |

A `TEAM_LEADER`'s weekly target is halved: leaders spend half their week leading. The container
share of a member's mix is folded into delivery; Epics are created per project by its owner
(section 4.5) rather than drawn per member.

Estimates are log-normal around the median with sigma 0.6, rounded to half hours, minimum 1 h.
Priorities: HIGHEST 5 %, HIGH 20 %, MEDIUM 50 %, LOW 20 %, LOWEST 5 %; defects skew one step higher.

### 4.3 Projects

Each department team receives 2 to 4 generated projects with keys from the department code
(`CT2-CAL`, `CT2-VAL`), names from family templates ("CT2 calibration campaign Q1", "SD1 diagnostics
validation wave 2"), status `ACTIVE`, `owner_id` the department head (for a department without a
head, such as "Unassigned", a deterministic fallback: the first center manager, else admin, else
person by id), `team_id` the department team.
Each project has an internal active window (a start and end week inside the history) that shapes
when its tasks are created; the window is not stored, because the schema has no project dates, and
the forecast must not need it. About one project in six is `PLANNING` at the end of the history with
a window that starts after it. The 7 existing projects keep their owners and receive tasks from the
3 existing teams only.

### 4.4 Weekly rhythm

For member `m` and week `w` the target assigned hours are

```
target(m, w) = base(m) × season(w) × ramp(m, w) × event(team(m), w) × availability(m, w)
base(m)      = family mean × N(1.0, 0.15) clipped to [0.5, 1.3], × 0.5 for a TEAM_LEADER
season(w)    = 0.55 for ISO weeks 31..34, 0.50 for weeks 52 and 1, 0.85 for the week of a confirmed holiday, else 1.0
ramp(m, w)   = min(1, weeks since joined / 6) for newcomers, else 1
event(t, w)  = 1.4 during three two-week team events per year, else 1.0
availability = working days minus absence days, over 5
```

Assigned hours are the estimates of tasks whose assignment date falls in the week. Tasks arrive as
a Poisson count with mean `target / mean estimate` (the log-normal's mean is the median times
`exp(0.6² / 2)`, about 1.2 × the median), sizes from the family distribution, so the expected sum of
estimates equals the target and the realised sum scatters around it. Zero weeks are real when the draw is zero.

### 4.5 Task lifecycle written as the application would

For each generated task:

1. **Creation.** 60 % are created by the team leader into the backlog 1 to 3 weeks before assignment,
   `assignee_id = null`, `reporter_id` = leader; 25 % are created and assigned in one step by the
   leader; 15 % are created by the member (`reporter_id = assignee_id`, self-picked). `created_date`
   is a working-day timestamp. 15 % of delivery tasks are sub-tasks of an Epic created earlier in
   the same project (`parent_task_id`). Keys follow `project.key-next_task_number`.
2. **Assignment.** A `task_history` row `field_name = 'assignee'`, `old_value = null`,
   `new_value = <the assignee's full_name>` (what the application writes), `changed_at` =
   assignment timestamp; `planned_week` = the Monday of the
   assignment week (what the application does today); `due_date` = assignment + cycle × N(1.1, 0.2)
   working days, so due dates are sometimes missed.
3. **Start.** Status transition `To Do → In Progress` (`field_name = 'status'`, old and new status
   names) on the first present day the task reaches the member's active set, never before the
   assignment timestamp, and at most 3 present working days after it: a task still unstarted after
   3 present days takes precedence in that day's active set (the bound holds while the overdue tasks
   fit the day's quarter-hour slices, which arrival rates keep far from the limit); `started_date`
   set.
4. **Work.** Daily `time_logs` rows spread the actual hours over the cycle, on working days the member
   is present, in quarter-hour steps, at most 8 h a day across a member's tasks, actual = estimate ×
   ratio(m) where `ratio(m)` is log-normal(0, 0.25) fixed per member and clamped to [0.6, 1.6], times a
   per-task noise log-normal(0, 0.2). `remaining_estimate_hrs` is updated after each log to
   `max(0, estimate − logged)`, and the final row keeps the last value. Each member works on at most
   three tasks at once (plus any overdue starts of step 3), the day's present hours split evenly over
   them, so the cycle length emerges from the queue. Status changes are written only on days the
   member is present; a review or blocked period counts present days.
5. **Finish.** Transition into `Done` at the last log, `finished_date` set, `remaining = 0`. 4 % of
   finished tasks are reopened later (`reopened_from_done = true`, `last_reopened_at`, a transition
   back to `In Progress` and a second finish with 20 to 40 % of the estimate logged again). 5 % of
   finished tasks have **no** `time_logs` rows and `remaining_estimate_hrs = 0`: the fallback case.
6. **Open at the end.** Tasks whose cycle crosses the end date stay `In Progress` with partial logs
   and a positive remaining estimate. Backlog tasks are planned three weeks beyond the end as well,
   and those created before the end but assigned after it are written unassigned (`assignee_id`
   null, no history row, `planned_week` null). This gives the forecast a real open-work state and a
   real backlog at the as-of date.

Statuses use the 9 existing rows: `To Do`, `In Progress`, `In Review` (10 % of delivery tasks pass
through it for 1 to 2 days), `Blocked` (3 % of tasks, drawn once per task, for 2 to 5 days), `Done`.
Notifications, comments and attachments are not generated.

### 4.6 Absences, leaves, holidays and capacity

- Each member takes 2 vacation blocks a year of 5 to 10 working days, more often in weeks 30 to 34
  and 51 to 2, plus 0 to 4 single sick days. Each is a `personal_leaves` row (`status = 'APPROVED'`,
  `leave_type` `PAID_LEAVE` or `SICK_LEAVE`) and one `absences` row per day (8 h, matching `type`).
- Confirmed holidays from the export apply to everyone in the country; pending ones do not.
- `user_capacity` gets one row per member and week: `base_capacity_hrs = 40`,
  `absence_hrs = 8 × absence days`, `available_hrs = 40 × working days / 5 − absence_hrs`.
- `team_capacity` gets one row per team and week: `total_capacity_hrs` = the sum of the members'
  available hours, `allocated_hrs` = the sum of the estimates assigned in that week.

### 4.7 Output, modes and parameters

```
seed --export workloadhub_export.json --weeks 52 --end 2026-09-06 --seed 42 --out seeded.json
seed --synthetic --users 40 --weeks 26 --seed 7 --out src/test/resources/fixtures/synthetic-40.json
seed ... --format sql --out seeded.sql
```

- `--weeks` (default 52) Monday weeks, the last one being the week that contains `--end` (default:
  today), which is the as-of date and the last generated day; `--seed` makes the output
  reproducible byte for byte.
- JSON output uses the export envelope so `import` and the owner's tooling read it; `--format sql`
  writes PostgreSQL `INSERT` statements in dependency order inside one transaction with
  `SET search_path TO task_service`, for `psql -f`. Within the five self-referencing tables (`users`,
  `teams`, `tasks`, `task_comments`, `task_types`) rows are written parents first, because
  PostgreSQL checks the non-deferrable foreign keys at the end of each statement; the JDBC importer
  applies the same order before its batches.
- `--synthetic` replaces names, usernames, emails and passwords with generated ones, drops
  `object_id` and `manager_object_id`, and can shrink the population; without `--export` it invents
  a directory (nine departments, one head each, one manager per ten people) and the reference rows
  (statuses, types, roles, Moroccan national holidays), so tests and CI need no export at all. An
  export missing some statuses or types is replaced by the same reference rows. Team and project
  names carried over from a real export are scrubbed of every input identity (full name, email,
  username, account name, longest first) and `sync_metadata` is dropped. `--users` is a synthetic-mode
  option; real mode refuses it. Only synthetic output is ever committed. The real-mode file holds
  password hashes and emails and stays out of git; the CLI refuses to write real-mode output under
  the repository unless `--force`.
- The generator is deterministic given `--seed`, single-threaded, and writes the 264 × 52 dataset in
  well under a minute.

### 4.8 Invariants (property tests)

1. Logged hours track assigned work: per member, logged hours over the estimates of their finished
   tasks lie in [0.4, 2.5] and the mean over members in [0.75, 1.3]; no member logs more than 8 h
   on a day; no log falls on one of the member's absence days.
2. Every task's transitions are ordered: created ≤ assigned ≤ started ≤ every log ≤ finished, all on
   working days the assignee is present; a reopened task has a second finish after the first.
3. No `time_logs` row falls on a confirmed holiday, a weekend or an absence day of its user.
4. `remaining_estimate_hrs = max(0, estimate − logged)` for every task with logs, 0 for finished tasks.
5. Every task has exactly one assignment transition per assignee change and its `assignee_id`
   equals the latest `new_value`.
6. `user_capacity.available_hrs = base × working days / 5 − absence_hrs` for every row;
   `team_capacity.total_capacity_hrs` equals the sum over its members.
7. Every foreign key in the output resolves; keys and `next_task_number` are consistent per project.
8. `import` of the output into SQLite and `export` back yields the same rows (round-trip).
9. Synthetic mode leaves no value from the input's `full_name`, `email`, `username`, `password`,
   `object_id` columns anywhere in the output.

## 5. Data access and domain rules

Repositories read the WorkloadHub tables for one team and a time window into plain records:
`MemberRow`, `TaskRow`, `TransitionRow`, `TimeLogRow`, `CapacityRow`, `AbsenceRow`, `HolidayRow`,
`ProjectRow`, `TeamRow`. The lifecycle rules are exactly those of the schema mapping document,
section 3, restated here where the module decides:

> Amended on 2026-09-10: capacity is computed per day and summed per forecast window; see
> `docs/superpowers/specs/2026-09-10-rolling-forecast-windows-design.md`, section 5.

- **Counted members**: active users with role `MEMBER` or `TEAM_LEADER` in `team_members` of the
  team, from `joined_at` to `deactivated_at`.
- **Assigned date**: `changed_at` of the latest `task_history` row with `field_name = 'assignee'`
  whose `new_value` resolves to the current assignee; else `created_date`. The application writes
  the assignee's display name or email into `old_value` and `new_value` (the export shows
  `"Developer Two"` and `"test1@workloadhub.com"`), so a value is resolved in this order: a UUID of a
  user, else a user's `email`, else a user's `full_name`, matched among the team's members first and
  all users second; `None`, empty and `null` mean unassigned. An ambiguous name (two users with the
  same `full_name`) or an unresolvable value falls back to `created_date` and is counted in
  `facts.data_quality.unresolved_assignments`.
- **Started / finished**: the columns, else the first transition into an `IN_PROGRESS` / `DONE`
  category, by `task_statuses.category`.
- **Actual hours**: sum of `time_logs.hours` for the task and user. When the task is finished and
  has no logs: `original_estimate_hrs − remaining_estimate_hrs` (which is the estimate when remaining
  is 0), and the task key goes into `facts.data_quality.unlogged_tasks`.
- **Family**: delivery (Story, New Feature, Task, Improvement, Change Request), defect (Bug,
  Incident), container (Epic), support (Spike, Test, Risk); Sub-task takes its parent's family.
- **Assignment mode**: self-picked when `reporter_id = assignee_id`; project when `parent_task_id`
  is set; else manual.
- **Calendar**: weeks start Monday; working days are Monday to Friday minus confirmed, active
  holidays (`start_date..end_date`); pending holidays are listed in the facts as uncertain.
- **Capacity**: `user_capacity.available_hrs` for the week when the row exists; else the member's
  latest `base_capacity_hrs`, else `whf.default-weekly-hours` (40), times working days over 5,
  minus the member's `absences.hours` in the week. `team_capacity` is reported, not used.

## 6. The feature matrix

One row per counted member per Monday week from the member's first assignment or join date to the
origin; horizons 1, 2, 3. The columns are those of the schema mapping document, section 5, minus
`planned_hrs_h{h}`, `planned_remaining_h{h}` and `team_planned_hrs_h{h}`: **46 columns per horizon**.

| Group | Columns |
|---|---|
| target | `target_h{h}` |
| own history | `lag1..lag4`, `lag8`, `lag13`, `roll_mean_{4,8,13}`, `roll_std_{4,8,13}`, `weeks_since_last_arrival`, `arrivals_13w`, `share_{defect,delivery,support}_13w`, `share_high_priority_13w`, `share_{self_picked,manual,project}_13w`, `reopen_rate_13w` |
| throughput and open work | `logged_hours_lag1..4`, `open_tasks`, `open_remaining_hrs`, `overdue_open`, `in_progress_tasks`, `estimate_ratio_13w`, `cycle_days_13w` |
| known about the target week | `due_hrs_h{h}`, `team_backlog_unassigned_hrs`, `proj_active`, `proj_planning`, `proj_first_due_weeks` |
| availability | `working_days_h{h}`, `absence_hrs_h{h}`, `available_hrs_h{h}` |
| identity | `member_id`, `team_id`, `role`, `job_title`, `tenure_weeks`, `week_of_year` |

The matrix is a `FeatureMatrix` value: column names in a fixed order, a `double[][]` body with
`NaN` for unknown, and the four categorical columns encoded as integer codes with a code book kept
on the matrix. `featureColumns(h)` returns the ordered names for one horizon. Every column is
computable from the database alone at prediction time; a test asserts it by building the matrix for
the as-of week and checking no column is entirely NaN except `lag13` on short histories.

## 7. Models

`ArrivalModel` has `fit(FeatureMatrix, horizons)` and `predict(FeatureMatrix, h)` returning hours
per row, clipped at 0, plus `name()`. Two implementations:

- **SeasonalNaive** (the floor): the member's `fresh_hours` of the same week one year earlier (52 weeks)
  when present in the training rows, else the row's `roll_mean_4`, floored at 0. Deterministic, no
  training.
- **XgboostArrival**: one booster per horizon trained on rows with a known `target_h{h}` over
  `featureColumns(h)` minus all-NaN columns. Parameters, chosen to mirror the Python
  `HistGradientBoostingRegressor(loss="poisson", max_iter=300, learning_rate=0.05)`:

  ```
  objective = count:poisson   tree_method = hist     max_bin = 255
  eta = 0.05                  num_round = 300        max_leaves = 31   grow_policy = lossguide
  max_depth = 0               min_child_weight = 1   lambda = 1        max_delta_step = 0.7
  seed = 0                    nthread = 1            missing = NaN
  ```

  The four identity columns are declared categorical on the `DMatrix` (`setFeatureTypes`, `"c"`)
  with `max_cat_to_onehot = 4`. A startup check probes the XGBoost4J build for categorical support;
  when it is absent the integer codes are fed as numeric columns, and a test covers both paths. The
  member's own level is already carried by the rolling means, so the fallback loses little. Fit and predict run single-threaded
  so two concurrent runs never contend, and the booster is discarded after the run (no model files).
- **EffortModel**: as in the internals document, section 3.5, with the schema's own remaining
  estimate: `estimate_ratio_13w` shrunk toward the team median with k = 5 and clipped to
  [0.5, 2.5]; `cycle_days_13w` median with the team median as fallback; open hours are
  `remaining_estimate_hrs`, so no remaining-fraction heuristic.

Bands come from backtest residuals (10th and 90th percentiles per horizon) on new hours only.

## 8. Backtest and champion

Unchanged from the internals document, sections 3.1 to 3.4: origins `as_of − 2k` weeks for
`k = 1..6`, kept when at least 13 weeks precede them; train rows `week_start ≤ origin − 3` weeks,
test rows at the origin; MAE and MASE against the seasonal naive; residuals pooled per model and
horizon; a model that raises `ModelUnavailable` at any origin loses all its scores. The champion is
XGBoost when its mean MASE is below 1.0, else the floor, and the run records both scores. A run may
force a model (`forcedModel`), which restricts the tournament to that model and the floor and uses
the forced one regardless of its score.

## 9. The run pipeline

> Amended on 2026-09-10: the horizon is two windows of five weekdays from the first weekday after the
> run day, computed per day; steps 2 to 7 are as in
> `docs/superpowers/specs/2026-09-10-rolling-forecast-windows-design.md`, sections 3 to 7.

`startRun(RunRequest)` inserts a `forecast_runs` row `QUEUED`, hands the run to a bounded executor
(two threads, `whf.run-threads`) and returns the id. The worker sets `RUNNING`, then:

1. Load the team's members, tasks, transitions, logs, capacity, absences, holidays and projects for
   the window `[as_of − (52 + 13) weeks, as_of + 3 weeks]`; the weeks after `as_of` bring the
   capacity rows, absences and due dates of the forecast weeks.
2. Build weekly series and the feature matrix; run the backtest; pick the champion; fit it on all
   rows; predict `h1`, `h2` for the two forecast weeks.
3. **Open hours**: remaining estimate of each open task placed from the first forecast week to
   `max(due_date + median lateness, first week)`, evenly over working days minus absences.
4. **New hours**: prediction × estimate ratio, spread over cycle days from the target week.
5. **Planned hours**: the planned-work allocation of the 2026-09-07 design, section 5: unassigned
   tasks of the team older than `BACKLOG_LAG_DAYS = 2`, share weights over 26 weeks with shrinkage
   `SHRINK_K = 3`, expected assignment week from the member's lag distribution, arrivals split so
   that the model's series counts only work that never went through the backlog (fresh hours), and
   the option `whf.planned-work.enabled` (default true).
6. **Capacity, overload, bands** per member and week; demand never capped.
7. Persist `forecast_member_weeks`, build the facts (section 10.1) and persist `forecast_facts`,
   set `DONE` with `finished_at`. Any exception sets `FAILED` with a one-line `error` and the stack
   trace in the log, never in the row.

A run for a team with fewer than 13 weeks of history still completes: the tournament is empty, the
floor is champion, and `facts.data_quality.history_weeks` says so, so Copilot can explain it.

Progress is kept in memory per run (phase, step, percent, and during narration the streamed
thinking and answer text) and read through `ForecastService.progress(runId)`. It lives in the JVM
that runs the run; a host with several instances routes progress reads to the same instance or
accepts that only the persisted state is visible.

## 10. Copilot narration

### 10.1 Facts

> Amended on 2026-09-10: `run.windows`, per-window forecast rows with a `days` list, and
> `expected_window`; see the rolling-windows design, section 9.

`FactsBuilder` produces the same JSON shape the Python service sends today (run, weeks, members
with history, forecast, capacity, open tasks, patterns, project timelines, rebalancing candidates,
rebalancing fit) plus the schema-specific additions: per member the due hours per week, reopened
tasks, logged hours of the last four weeks, unlogged finished tasks, the team's own `team_capacity`
plan, pending holidays, and the planned-work section (candidates, allocation, likely work) of the
2026-09-07 design, section 6.1. Members are identified by UUID inside the facts and by `full_name`
in text; tasks by key. The exact bytes sent are stored in `forecast_facts`.

### 10.2 Session

`narrate(runId, language, model)` runs after `DONE`, on demand, once per language, and may be
repeated (each narration is a new `forecast_narratives` row). It:

1. Loads the requesting user's token through `GitHubTokenStore`; without one, returns
   `TOKEN_MISSING` and does nothing else.
2. Creates a `CopilotClient` whose `CopilotClientOptions` carry the user's token
   (`setGitHubToken`), `COPILOT_HOME` under the module's work directory, and the CLI from the
   runtime artifact (override with `whf.copilot.cli-path` or `COPILOT_CLI_PATH`). No `copilot auth
   login` ever runs on the server; the token on the options is the only credential. The
   `SessionConfig` sets the model (`whf.copilot.model`, request override, blank = account default),
   the nine tools, a system message that embeds the five product skills (this SDK version has no
   skill-directory setting, so the skill texts are classpath resources concatenated into the system
   message, which also makes the prompt deterministic), and a permission handler that approves the
   module's own tools only.
3. Sends the user prompt (facts summary plus the contract, in the requested language), streams
   events into the run's progress (assistant deltas, reasoning deltas when the SDK emits them, tool
   calls and results), and waits for the final assistant message.
4. Parses the JSON narrative against the contract (section 10.4); on failure sends one retry prompt
   with the validation problems; on a second failure stores `FAILED` with the problems.
5. Verifies every number in the narrative against the facts (section 10.5), stores the narrative,
   the verification report and the usage, and closes the session and client.

Skills are classpath resources read once at startup. One CLI process per narration; sessions are
never resumed. Streaming uses the SDK's `AssistantMessageDeltaEvent`, `AssistantReasoningDeltaEvent`,
`ToolExecutionStartEvent`, `ToolExecutionCompleteEvent`, `AssistantUsageEvent` and
`SessionIdleEvent`; usage metrics come from the session usage RPC and the account quota from the
account quota RPC on `client.getRpc()`.

### 10.3 Tools

The eight tools of the Python narrator, same names and same JSON results, plus one new tool, read
from the run's stored facts and never from the database: `get_run_overview`, `get_member_history`, `get_member_forecast`,
`get_member_patterns`, `get_member_open_tasks`, `get_member_capacity`, `get_project_timelines`,
`get_rebalancing_candidates`; plus `get_planned_work` from the 2026-09-07 design. Declared with
`@CopilotTool` on a `FactsTools` object bound to one run.

### 10.4 Contract

The narrative is a JSON object with `run_summary`, `members[]` (id, name, risk level, summary,
patterns with kind, statement and evidence, warnings), `team_risks[]`, `rebalancing[]` (from, to,
week, hours, reason, confidence, task keys), `suggested_adjustments[]`, `likely_work[]` and
`model_notes`, with the same length limits as the Python `schema.py`. Member ids are UUID strings.
Unknown fields are rejected. Validation is a hand-written validator over Jackson's tree, not
annotations, so error messages name the path.

### 10.5 Verification

Every number in the narrative text must appear in the facts (shared numbers, or the member's own
numbers for member text), with the rounding rules of the Python `verify.py`. Rebalancing moves must
reference members of the run and weeks of the forecast, and a move to a target with zero fit while
another member has spare hours and a positive fit is rejected. Numbers invented by Copilot are the one
failure the module must never let through; a failed verification is stored with the narrative and
surfaced as `verified = false` with the list of problems.

### 10.6 Usage, cost and quota

From the session usage metrics RPC, else the `AssistantUsageEvent` stream: input, output and
cached tokens, and premium-request or credit counters when present, converted as in the Python
`usage.py` (`ai_credits = nano_aiu / 1e9`, `usd = credits / 100`), with `source` `metrics`, `events`
or `none`. Account quota comes from the account quota RPC with a 10-second timeout; on timeout or
error the quota fields are null and the status says why.
`copilotStatus(userId)` reports: token present, CLI runtime path and version, and quota.

## 11. Public API

> Amended on 2026-09-10: `RunRequest` has no `asOf` (the run day is the server's clock), `RunResult`
> carries member windows and days, and `currentForecast(teamId, from, to)` reads the per-day current
> forecast; see the rolling-windows design, section 8.

> Amended on 2026-09-11: `RunProgress(runId, phase, percent, message, label)`; runs left QUEUED or RUNNING
> are failed at start-up; the host's role rules and integration sequence are in
> `docs/superpowers/specs/2026-09-11-host-integration-design.md`.

```java
public interface ForecastService {
    UUID startRun(RunRequest request);                     // teamId, requestedBy, forcedModel, plannedWork (the run day is the server's today)
    RunResult getRun(UUID runId);                          // status, champion, scores, member weeks, error
    List<RunSummary> listRuns(UUID teamId, int limit);
    RunProgress progress(UUID runId);                      // phase, percent, live thinking/answer while narrating
    NarrativeResult narrate(NarrativeRequest request);     // runId, requestedBy, language, model
    Optional<NarrativeResult> narrative(UUID runId, String language);
    CopilotStatus copilotStatus(UUID userId);
}
public interface GitHubTokenStore {
    void save(UUID userId, String token);
    boolean has(UUID userId);
    void clear(UUID userId);
}
```

All request and result types are Java records in `api`, serialisable with Jackson without
configuration. Errors are `ForecastException` with a `code` from a fixed set (`TEAM_NOT_FOUND`,
`RUN_NOT_FOUND`, `RUN_NOT_DONE`, `TOKEN_MISSING`, `TOKEN_REJECTED`, `COPILOT_UNAVAILABLE`,
`NARRATIVE_INVALID`, `INVALID_REQUEST`). Authorisation is the host's: the module trusts
`requestedBy` and only checks that the user exists and, for narration, has a token. The optional
controller maps the interface one to one under `whf.web.base-path` (default `/api/forecast`):
`POST /runs`, `GET /runs/{id}`, `GET /teams/{teamId}/runs`, `GET /runs/{id}/progress`,
`POST /runs/{id}/narratives`, `GET /runs/{id}/narratives/{lang}`, `GET /copilot/status`,
`PUT /users/{id}/github-token`, and returns errors as `{code, message}` with 404, 409 or 400.

Configuration properties, all under `whf.`:

| Property | Default | Meaning |
|---|---|---|
| `token-key` | none, required for narration | base64 AES-256 key |
| `default-weekly-hours` | 40 | capacity fallback |
| `run-threads` | 2 | concurrent runs |
| `planned-work.enabled` | true | allocation on or off |
| `copilot.model` | blank | account default |
| `copilot.cli-path` | blank | bundled runtime |
| `copilot.timeout-seconds` | 300 | one narration |
| `web.enabled` | false | REST controller |
| `web.base-path` | `/api/forecast` | |
| `flyway.enabled` | true | module migrations |

## 12. The command line (`forecast-cli`)

Runs in WSL with `java -jar workloadhub-forecast-cli.jar <command>`; the SQLite file defaults to
`./workloadhub.db` and is set with `--db`. Commands:

| Command | Does |
|---|---|
| `init-db` | creates the 24 WorkloadHub tables and the module's tables in SQLite |
| `import <export.json>` | loads an export (real or seeded) into the database, replacing existing rows |
| `seed ...` | section 4 |
| `run --team <name or id> --as-of <date> [--model xgboost|naive] [--user <name>]` | runs synchronously, prints the champion, the scores and the member week table |
| `eval [--teams ...] [--out dir]` | the tournament over every team and origin; writes `scores.csv`, `demand.csv`, `summary.md` in the same columns as the Python harness, for the parity check |
| `narrate --run <id> --lang en|fr --token-env GITHUB_TOKEN` | stores the token for the user, narrates, prints the streamed thinking and answer, then the verified narrative |
| `copilot status` | token presence, runtime, quota |
| `runs --team <name>` | lists runs |

Output tables are plain text in English; the CLI is a developer tool and is not localised. The CLI
never touches PostgreSQL: production access goes through the host.

## 13. Testing

- **Unit and property tests**: JUnit 5 and jqwik. Properties are stated in section 4.8 for the seed
  and, for the forecast: demand equals open + new + planned per member and week; overload is
  `max(0, demand − capacity)` and never reduces demand; bands contain the point; capacity is zero on
  a week with no working days; the assignment-date rule picks the latest transition; the feature
  matrix uses no fact dated after the row's week (a leakage test that shifts future rows and asserts
  unchanged features); MASE of the floor against itself is 1; the champion is the floor whenever the
  booster's MASE is at or above 1.
- **Repository tests**: SQLite always; PostgreSQL through Testcontainers when Docker is available,
  otherwise skipped with a visible message, so the CI job on GitHub runs both.
- **Model tests**: XGBoost learns a planted signal (a member whose arrivals double every 13th week)
  better than the floor; determinism (two fits, identical predictions); categorical or numeric path
  decided by one test.
- **Narrator tests**: a fake `CopilotClient` replays recorded events; the contract validator and the
  verifier get table-driven tests; a token-less user gets `TOKEN_MISSING`; a live test against the
  real SDK is manual and documented in the CLI README.
- **Parity**: `eval` on the synthetic fixture and on the owner's seeded file is compared with the
  Python harness run from the archive branch on the same import. The gate is: per team, the mean
  MASE of the Java booster is within 0.10 of the Python booster and the champion is the same; a
  larger gap is investigated, not accepted.
- **Gate**: `mvn -B verify` in `server/` runs everything under three minutes without Docker;
  `scripts/check.sh` wraps it; `.github/workflows/ci.yml` runs it on `ubuntu-latest` for pushes to
  `dev` and `main` with Docker present.

## 14. Migration and archival

In this order, each step a reviewed commit on `dev`:

Status: steps 1 to 5 done on 2026-09-10 (`archive/python-desktop-v1` at `5c69bf6`; the removal in
`docs/superpowers/plans/2026-09-10-python-desktop-archival.md`).

1. Branch `archive/python-desktop-v1` from the current `main` and push it. It is the frozen
   reference and the parity oracle; no new features.
2. Create `server/` with the Maven skeleton, CI for Java, and this spec's tables, alongside the old
   code, so the parity check can run both in one checkout.
3. Implement the module (the plan's tasks), with the parity check as its last task.
4. Remove `service/`, `app/`, `installer/`, the PowerShell build and hook scripts, the Python parts
   of `.claude/skills` that only served the desktop (keep `copilot-sdk`, `property-based-testing`),
   `docs/notebooks` (kept on the archive branch), and rewrite `CLAUDE.md`, `README.md`, `scripts/`
   and the CI for Java only. The design and spec documents stay as history, with a note at the top
   of each superseded one pointing here.
5. Fast-forward `main`.

## 15. Non-goals

No front end; no scheduler (the host decides when to run); no LLM-produced numbers, ever; no TSB
or Chronos-2; no multi-tenant or cross-database support; no token refresh (the host's OAuth flow);
no model persistence between runs; no Windows-native support (WSL is the Windows path).

## 16. Order of work

Phase A: skeleton, database, SQLite schema, import, token store. Phase B: seed generator. Phase C:
lifecycle rules, calendar, capacity, series, feature matrix. Phase D: models, backtest, effort,
planned work, pipeline, persistence, CLI `run` and `eval`. Phase E: Copilot narration, tools,
contract, verification, usage, CLI `narrate`. Phase F: REST controller, auto-configuration test in a
sample host, parity check, archival and CLAUDE.md. The plan breaks these into tasks.
