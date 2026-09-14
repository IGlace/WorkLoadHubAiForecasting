---
name: whf-domain
description: Vocabulary and data dictionary of the WorkloadHub forecast facts. Use when reading run facts about teams, members, tasks, capacity, demand or overload.
---

# WorkloadHub domain

## Organisation
- A **department** is a team without a manager; a **team** has a manager (the team leader), who also does technical work and is counted like any member.
- A **member** is a person counted in the workload, identified by a UUID string (`id`) and named by `name`. Weeks start on Monday; working days are Monday to Friday; public holidays are off days. A **forecast window** is five weekdays starting the first weekday after the run day (a run on a Friday or a weekend starts on Monday); a run covers between one and six contiguous windows, and `model.windows` says how many; a holiday inside a window stays inside with zero capacity and no predicted hours. Weeks remain the unit of history.

## Task record (in `open_tasks`)
`key` (the task's key, cite it as given), `title`, `type` (the application's task type), `family` (delivery, defect, support, analysis, maintenance, other: the type folded into a work family), `priority`, `estimated_hours`, `remaining_hours`, `due_date`, `overdue`, `project_key`, `in_progress`.

## Forecast rows (`forecast`, per member and window)
- The row identifies its window by `window` (1, 2, ...), `start` and `end` (ISO dates).
- **demand**: the hours a member is predicted to log in the window, never capped by capacity.
- **capacity**: available hours after holidays, absences and the team's capacity plan (default 40 h per week, 8 h per working day, summed over the window's working days); `working_days` and `absence_hours` say why it is lower.
- **overload**: max(0, demand minus capacity). Demand is never cut to fit capacity.
- **low / high**: an interval around demand from the model's backtest residuals; wide bands mean an unstable history.
- **due_hours**: remaining hours of the member's open tasks that fall due inside the window.

## Member facts
`history_13w` (weekly logged hours, arrival hours and task counts), `logged_hours_4w` (hours logged per week), `unlogged_tasks` (finished tasks with no time logged; their actual hours were estimated), `reopened_tasks`, `patterns` (see whf-pattern-discovery), `likely_work` (see whf-likely-work). Each member also carries `days`, one entry per weekday of the horizon (`day`, `window`, `demand`, `capacity`, `overload`, `working_day`); a window's figures are the sums of its days, rounded.

## Team and run facts
`team.totals` (demand and capacity per window), `team.team_capacity` (the team's own capacity plan: total and allocated), `projects` (key, name, status, open and backlog task counts, first due date), `pending_holidays` (declared but unconfirmed), `data_quality` (unresolved assignments, unlogged tasks, history weeks), `run.windows` (`index`, `start`, `end`, `working_days`).

## Model facts
- **model**: `name` (`xgboost`, gradient boosting on engineered features) and `target` (`logged hours per member-week`) say what was fitted and to what. `mae` next to `mean_actual_hours` say how far off the model was on average, in hours, against the size of what it predicts; read them side by side and never as a verdict.
- **confidence**: `scored` when the backtest has at least one scored origin; `thin_history` otherwise, meaning `mae` and `mean_actual_hours` are absent and nothing should be said about accuracy.
- **backtest_origins**: the past dates the model was scored on. **limitations**: known blind spots of this run.
