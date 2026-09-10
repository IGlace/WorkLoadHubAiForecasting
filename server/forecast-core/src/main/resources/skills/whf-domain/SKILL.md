---
name: whf-domain
description: Vocabulary and data dictionary of the WorkloadHub forecast facts. Use when reading run facts about teams, members, tasks, capacity, demand, overload or planned work.
---

# WorkloadHub domain

## Organisation
- A **department** is a team without a manager; a **team** has a manager (the team leader), who also does technical work and is counted like any member.
- A **member** is a person counted in the workload, identified by a UUID string (`id`) and named by `name`. Weeks start on Monday; working days are Monday to Friday; public holidays are off days.

## Task record (in `open_tasks`, `likely_work.planned`)
`key` (the task's key, cite it as given), `title`, `type` (the application's task type), `family` (delivery, defect, support, analysis, maintenance, other: the type folded into a work family), `priority`, `estimated_hours`, `remaining_hours`, `due_date`, `overdue`, `project_key`, `in_progress`.

## Forecast rows (`forecast`, per member and week)
- **demand**: predicted hours, never capped. It is `open_hours` (remaining hours of tasks already assigned) plus `new_hours` (hours of tasks predicted to arrive) plus `planned_hours` (hours of backlog tasks allocated to this member by the planned-work rule).
- **capacity**: available hours after holidays, absences and the team's capacity plan (default 40 h per week, 8 h per working day); `working_days` and `absence_hours` say why it is lower.
- **overload**: max(0, demand minus capacity). Demand is never cut to fit capacity.
- **low / high**: an interval around demand from the model's backtest residuals; wide bands mean an unstable history.
- **due_hours**: remaining hours of the member's open tasks that fall due that week.

## Member facts
`history_13w` (weekly arrival hours and task counts), `logged_hours_4w` (hours logged per week), `unlogged_tasks` (finished tasks with no time logged; their actual hours were estimated), `reopened_tasks`, `patterns` (see whf-pattern-discovery), `likely_work` (see whf-likely-work).

## Team and run facts
`team.totals` (demand, capacity and planned per week), `team.team_capacity` (the team's own capacity plan: total and allocated), `projects` (key, name, status, open and backlog task counts, first due date), `pending_holidays` (declared but unconfirmed), `data_quality` (unresolved assignments, unlogged tasks, history weeks).

`planned_backlog` (backlog tasks per project with estimated hours and hours in the window) is not part of the team facts of `get_run_overview`, which strips it: `get_planned_work` returns it, next to each member's `likely_work`.

## Model facts
- **champion**: the arrival model that won the backtest, `xgboost` (gradient boosting) or `seasonal_naive` (repeats history). **champion_mase** below 1.0 means it beat the seasonal-naive floor; near 1.0 means little better than repeating history. `forced_model` is set when the caller chose the model.
- **backtest_origins**: the past dates the models were scored on. **planned_basis** says how planned work was allocated, or `disabled`. **limitations**: known blind spots of this run.
