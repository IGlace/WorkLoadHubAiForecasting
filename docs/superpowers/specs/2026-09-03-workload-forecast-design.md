# WorkloadHub AI Forecasting: version 1 design

Date: 2026-09-03. Status: approved in brainstorming, pending owner review of this
document. Inputs: `docs/requirements/2026-09-03-discovery-qa.md`,
`docs/requirements/requirements-v1.md`, `docs/research/2026-09-03-research-notes.md`.

## 1. Goal

A Windows application, installed from one shared installer file, that lets a team
leader forecast the **estimated work hours of each team member for the next two
weeks**, compare them with each member's capacity, and get an AI narrative with
discovered patterns, overload warnings and rebalancing suggestions. The AI is the
user's own **GitHub Copilot Enterprise** seat. Version 1 runs on generated dummy data.

Non-goals for version 1: WorkloadHub integration, scheduled runs, programmatic
accuracy evaluation, sprint or release cycles, distribution beyond a shared file.

## 2. Architecture

Two processes, one installer, one codebase per language.

```
+-----------------------------+        localhost HTTP         +------------------------------+
|  Desktop app (Electron, TS) | <---------------------------> |  Forecast service (Python)    |
|  - UI (React)               |                               |  - FastAPI on 127.0.0.1       |
|  - tray + notifications     |  spawns / supervises          |  - SQLite data store          |
|  - starts the service       | ----------------------------> |  - forecasting engine         |
|  - Copilot sign-in screen   |                               |  - capacity engine            |
+-----------------------------+                               |  - Copilot SDK session        |
                                                              |  - CLI (same functions)       |
                                                              +---------------+--------------+
                                                                              | JSON-RPC (bundled CLI)
                                                                              v
                                                                    GitHub Copilot (user's seat)
```

- **Forecast service** (`service/`, package `whf`): owns everything that is not
  presentation. Exposes a REST API on a random free port bound to 127.0.0.1, protected
  by a per-launch token passed to the app. The same functions are exposed as a
  PowerShell-friendly CLI (`whf`), for advanced users.
- **Desktop app** (`app/`): Electron main process starts the service executable,
  waits for its health endpoint, opens the renderer, keeps a tray icon alive for the
  daily due-check, and shows Windows notifications. The renderer is React and talks
  only to the local API.
- **Copilot** is reached through the Copilot SDK for Python, which bundles the Copilot
  CLI and reuses the user's `copilot` login. No API keys.

Why this split: the forecasting and data ecosystem is Python; the desktop, tray,
notification and installer ecosystem is Electron; and the owner wants room for
machine learning growth in the service.

## 3. Data model

SQLite database at `%LOCALAPPDATA%\WorkloadHubForecast\whf.db`. Tables:

| Table | Key fields |
|-------|-----------|
| departments | id, name, skill_team_leader_id |
| teams | id, department_id, name, team_leader_id |
| members | id, name, team_id, role (`member`, `team_leader`, `skill_team_leader`), counted_in_workload (bool), active_from, active_to |
| projects | id, name, department_id, start_date, deadline, type, status, created_by |
| project_teams | project_id, team_id |
| tasks | id, title, project_id, assignee_id, team_id, type, priority, status, created_at, assigned_at, due_date, completed_at, estimated_hours, actual_hours, created_by, assignment_mode (`manual`, `self_picked`, `project`) |
| capacity_defaults | weekly_hours (40) |
| capacity_overrides | member_id, week_start (nullable for permanent), weekly_hours, reason |
| holidays | date, name, country (`MA`) |
| vacations | member_id, start_date, end_date, type |
| profiles | current user: member_id, role |
| runs | id, team_id, as_of, requested_by, status, champion_model, backtest_mase, started_at, finished_at, ai_status |
| forecasts | run_id, member_id, week_start, demand_hours, demand_low, demand_high, capacity_hours, overload_hours, open_task_hours, new_task_hours |
| run_narratives | run_id, json (validated AI output) |
| run_facts | run_id, json (exact facts given to Copilot, for audit) |

Notes: `assignment_mode` is recorded when known (dummy data always knows it; real
data may leave it null and the pattern layer infers it). Skill team leaders have
`counted_in_workload = false`. Team leaders have `true`.

## 4. Dummy data generator

`whf data generate --seed 42 --months 12` writes a complete, reproducible dataset:

- 3 departments, each with a skill team leader; 2 to 3 teams per department; 4 to 7
  members per team including the team leader.
- 1 year of tasks ending on the generation date, plus open tasks, plus projects that
  start or end inside the following two weeks, plus vacations in the coming weeks.
- Each member has a hidden profile: base weekly arrival rate, arrival dispersion
  (negative binomial), assignment style mix, estimate bias (actual over estimated),
  cycle-time distribution per task type, lateness relative to due date, weekday
  preference. Each project has a phase curve: ramp-up after start, plateau, crunch in
  the last weeks before the deadline. Global effects: summer dip, year-end crunch,
  Morocco public holidays from the `holidays` package, seasonality by week of year.
- Effort is simulated day by day. The generator keeps a hidden per-day effort log and
  writes it, with all profiles, to `answer_key.json`. Task rows expose only the twelve
  agreed fields. The answer key exists so the owner can compare Copilot's discovered
  patterns and the model's weekly hours against the truth.

## 5. Forecasting engine

The engine never asks the language model for a number. It has two learned layers,
a capacity step, and a backtest that selects the champion model per run.

### 5.1 Target definitions

- **Arrival series** (per member, per week): number of tasks assigned in the week,
  and their estimated hours. Unambiguous from `assigned_at` and `assignee_id`.
- **Effort facts** (per completed task): cycle time = `completed_at` minus
  `assigned_at`; estimate ratio = `actual_hours` / `estimated_hours`; lateness =
  `completed_at` minus `due_date`. Unambiguous.
- **Weekly demand** (the forecast output): predicted hours of work for a member in a
  week = hours from already-open tasks placed by the effort model, plus hours from
  predicted arrivals placed by the effort model.

### 5.2 Layer 1: arrival model

Predicts, for each member and each of the next two weeks, the estimated hours (and
count) of tasks that will be assigned. Candidates, all trained on the local database:

| Model | Role | Method |
|-------|------|--------|
| Seasonal naive | Floor that every other model must beat | Same week last year when available, otherwise the mean of the last four weeks |
| TSB (Teunter, Syntetos, Babai) | Baseline for intermittent series | Exponential smoothing of demand probability and demand size, updated every period |
| Global gradient boosting | Main model and machine-learning growth point | scikit-learn `HistGradientBoostingRegressor` with Poisson loss, one model per horizon (week 1, week 2), trained on all member-week rows jointly |

Gradient boosting features per member-week: lags 1, 2, 3, 4, 8, 13; rolling mean
and standard deviation over 4, 8 and 13 weeks; weeks since last arrival; share of
manual, self-picked and project-driven assignments over the last 13 weeks; member,
team and department as categorical features; project phase features aggregated over
the member's active projects (number of active projects, minimum weeks to deadline,
maximum weeks since start, number of projects starting within the horizon, number of
projects ending within the horizon); calendar features (week of year, working days
in the week after holidays, vacation hours of the member in the target week).
Quantile variants (0.1 and 0.9) of the same model provide the prediction interval.

### 5.3 Layer 2: effort model

Places hours in weeks for every open task and every predicted arrival.

- **Cycle time**: `HistGradientBoostingRegressor` on log cycle time with features:
  estimated hours, type, priority, member, project phase at assignment, member's open
  task count at assignment. Fallback for cold members: empirical-Bayes shrinkage of the
  member's median toward the team median by task type.
- **Estimate ratio**: shrunken per-member, per-type mean of actual over estimated,
  bounded to [0.5, 2.5].
- **Lateness**: empirical distribution of `completed_at - due_date` per member, used
  to shift the predicted completion when a due date exists.
- **Placement**: predicted actual hours = estimated hours × estimate ratio; predicted
  active window = `assigned_at` to predicted completion; hours are distributed over
  the working days of that window (after holidays and vacations) and summed per week.
  Overdue open tasks carry their remaining hours forward from the run date.

Predicted arrivals are placed the same way, using the member's typical estimated
hours per task and the member's cycle time for that type.

### 5.4 Capacity

Available hours for a member in a week = weekly capacity (default 40, permanent
override, week override) × working days in the week after Morocco holidays and the
member's vacations ÷ 5. Overload = max(0, demand − capacity). Demand is never capped.
Team and department figures are bottom-up sums, so the hierarchy is always coherent.

### 5.5 Backtesting and champion selection

Every run replays the last six two-week windows of history as if they were forecast
dates (rolling origin), scores each arrival model with MAE and MASE against seasonal
naive, and chooses the model with the best mean MASE for the team. The choice, the
score and the interval calibration are stored on the run and shown in the UI. Interval
width for the chosen model is calibrated from its backtest residuals. If no model
beats seasonal naive, seasonal naive is used and the run says so.

### 5.6 Pattern statistics

Computed deterministically and handed to Copilot as facts: per member, the assignment
style mix; weekday of arrivals; arrival rate trend over 13 weeks; estimate ratio;
median cycle time per type; lateness; correlation between arrivals and project phase;
share of hours by project. A small clustering step (k-means on these features, k
chosen by silhouette) groups members with similar behavior so the narrative can say
"behaves like the project-driven group".

### 5.7 Where future machine learning plugs in

Each model is one module under `service/src/whf/models/` implementing the same
interface (`fit(history)`, `predict(horizon)`, `name`). Adding a model means adding a
file and registering it in the champion list; the backtest decides whether it is
used. When runs are later compared with actuals, those errors become extra training
rows and features for the same pipeline, which is the feedback loop the owner wants.

## 6. Copilot integration

- **Session**: Copilot SDK for Python, model `auto` unless configured, one session per
  run, streaming progress to the UI.
- **Custom agent**: a system prompt that states the rules (numbers come only from
  tools, cite them exactly, answer in the required JSON, work in the user's language)
  and a restricted tool set. Tools are read-only functions over the run's facts:
  `get_run_overview`, `get_member_history(member_id)`, `get_member_forecast(member_id)`,
  `get_member_patterns(member_id)`, `get_project_timelines()`, `get_capacity(member_id)`,
  `get_team_rebalancing_candidates()`.
- **Skills**: the product ships SKILL.md skills that Copilot loads in the session:
  `whf-domain` (glossary, data dictionary, roles), `whf-pattern-discovery` (how to
  read the pattern statistics and what counts as evidence), `whf-forecast-interpretation`
  (how to explain demand, capacity, overload, intervals, champion model, backtest
  score), `whf-rebalancing-advice` (rules for suggesting moves: same team first,
  matching task types, respect capacity, prefer under-loaded members), `whf-report-style`
  (tone, length, language, structure). They live in `.claude/skills/whf-*` in the
  repository, so Claude Code and Copilot CLI read them during development, and the
  service build copies them into the bundle.
- **Output**: strict JSON validated with Pydantic: run summary; per member the
  discovered patterns with evidence, a short narrative, a risk level and warnings;
  team-level risks; rebalancing suggestions (from member, to member, hours, week,
  reason, confidence); suggested adjustments (member, week, delta hours, reason). Every
  number in the narrative is checked against the facts; unmatched numbers mark the
  narrative as `unverified` in the UI. Suggested adjustments are displayed next to the
  numeric forecast, never merged into it, and stored as a revision trace.
- **Failure modes**: Copilot not signed in, quota exhausted, network down, invalid
  JSON after one retry. In every case the numeric forecast completes and the run is
  stored with `ai_status` explaining the gap.
- **Sign-in**: the app detects a missing login and opens a sign-in screen that runs
  the bundled CLI's device login and shows the code and URL. Fallback instruction:
  run `copilot login` in PowerShell once.

## 7. Desktop application

Screens: **Dashboard** (department, teams, weeks, demand vs capacity, overload
badges); **Run** (pick one team, see progress steps, open the result); **Team result**
(members × two weeks table, interval bars, champion model and backtest score, AI
summary, warnings); **Member detail** (history chart of arrivals and hours, forecast
with interval, capacity, patterns, narrative); **Rebalancing** (overloaded and
under-loaded members side by side, suggested moves with reasons); **Projects** (create
and edit with start date, deadline and assigned teams); **Capacity** (default, member
overrides, week overrides); **Time off** (holidays, vacations); **Runs** (history and
comparison-ready storage); **Settings** (profile, Copilot sign-in, model, language).

Roles: a team leader sees and runs their team. A skill team leader sees all teams of
their department and can run any single team on behalf of its leader. The profile is
chosen in Settings in version 1 (dummy data has no real identity).

Notifications: at app start and every 24 hours the tray process asks the service
whether the user's team has a run in the last 14 days; if not, a Windows notification
says a forecast is due. After a run, a notification lists members with overload.

## 8. CLI

`whf run --team <id>`, `whf runs list`, `whf runs show <id> --json`, `whf export
<id> --format csv|json`, `whf data generate`, `whf capacity set`, `whf vacations add`,
`whf projects add`, `whf copilot status`, `whf serve`. Built with Typer, works in
PowerShell, returns non-zero exit codes on failure. It calls the same service
functions as the API.

## 9. Packaging and operations

- Service frozen with PyInstaller (one-folder mode for fast start) including the
  Copilot CLI binary provided by the SDK package, scikit-learn, pandas, holidays.
- App built with electron-builder into a per-user NSIS installer (no administrator
  rights), which embeds the service folder. One file to share.
- Data and logs under `%LOCALAPPDATA%\WorkloadHubForecast\`. Logs rotate; the run
  facts and AI output are kept for audit.
- Auto-start at login is optional and off by default.
- Development happens on Windows (PowerShell scripts) and in Linux CI; only
  notifications, installer and the frozen service are Windows-specific.

## 10. Testing

- Test-driven development throughout.
- Service: pytest with Hypothesis property tests for capacity arithmetic, placement
  invariants (hours conserved, never negative, never outside the window), TSB and
  seasonal naive correctness, and the generator (answer key consistent with task
  rows). A backtest test on the seeded dummy data asserts the champion beats seasonal
  naive and that weekly hours track the hidden effort log within a tolerance.
- Copilot layer: unit tests with a fake session; one opt-in integration test that
  needs a real login.
- App: Vitest for the renderer and main process logic; Playwright end-to-end smoke
  later.
- CI: GitHub Actions on Linux for tests and lint (ruff, ty, eslint, tsc) and on
  Windows for packaging.

## 11. Repository layout

```
service/        Python service, CLI, models, generator, tests
app/            Electron + React desktop app
installer/      electron-builder and PyInstaller configuration
.claude/        development skills, agents, settings (Claude Code); product skills whf-*
docs/           research, requirements, specs, plans
scripts/        PowerShell and shell helpers for dev, build, package
```

## 12. Risks and mitigations

| Risk | Mitigation |
|------|------------|
| The Python SDK package may not ship a Windows CLI binary | First implementation task is a spike on a Windows machine; fallback is bundling the CLI from the npm package |
| Copilot narrative cites numbers not in facts | Numeric cross-check marks narratives `unverified`; skills instruct exact citation |
| One year of weekly data is short for seasonality | Seasonal naive falls back to recent mean; the gradient boosting model uses calendar features; backtest decides |
| Weekly hours cannot be validated on real data without time logs | Dummy data has a hidden effort log; on real data, evaluation later compares arrivals and completions, which are observable |
| Frozen service size and start time | One-folder PyInstaller mode, lazy imports, health check with a splash screen |
