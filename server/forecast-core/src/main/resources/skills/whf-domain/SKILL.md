---
name: whf-domain
description: Vocabulary and data dictionary of the WorkloadHub forecast facts. Use when reading run facts about teams, members, tasks, capacity, demand or overload.
---

# WorkloadHub domain

## Organisation
- A **department** is a team without a manager; a **team** has a manager (the team leader), who also does technical work and is counted like any member.
- A **member** is a person counted in the workload, identified by a UUID string (`id`) and named by `name`. Weeks start on Monday; working days are Monday to Friday; public holidays are off days. A **forecast window** is five weekdays starting the first weekday after the run day (a run on a Friday or a weekend starts on Monday); a run covers between one and six contiguous windows, and `model.windows` says how many; a holiday inside a window stays inside with zero capacity and no predicted hours. Weeks remain the unit of history.
- **Open**, said of a member's task, means assigned to them and not finished, whatever its status (To Do, In Progress, In Review). It is not the application's `Open` status, which means a task nobody is assigned to yet; those are the team's backlog. Say "assigned to them and not finished" when a leader could read it the other way.

## Task record (in `open_tasks`)
`key` (the task's key, cite it as given), `title`, `type` (the application's task type), `family` (delivery, defect, support or container: the type folded into a work family; a sub-task takes its parent's), `priority`, `estimated_hours`, `remaining_hours`, `due_date`, `overdue`, `project_key`, `in_progress`.

## Forecast rows (`forecast`, per member and window)
- The row identifies its window by `window` (1, 2, ...), `start` and `end` (ISO dates).
- **demand**: the hours a member is predicted to log in the window, never capped by capacity.
- **capacity**: the default week (44 h, 8.8 h per working day) over the window's working days, minus the member's approved leave hours; `working_days` and `absence_hours` say why it is lower. Nothing else reduces it.
- **overload**: max(0, demand minus capacity). Demand is never cut to fit capacity.
- **low / high**: an interval around demand from the model's backtest residuals; wide bands mean an unstable history.
- **due_hours**: remaining hours of the member's open tasks that fall due inside the window.
- **planned_hours**: estimated hours of the member's open tasks whose planned week overlaps the window, as the leader planned them in the application; blank planning means zero.
- **backlog_excess_hrs**: what is left of the member's open queue once the windows so far (this one and every earlier one in the run) are forecast — `open_est_hours` minus the run's own cumulative demand, never below zero. It falls as the run's windows absorb the backlog, never rises, and the last window is the strictest reading: above zero there means the backlog does not fit inside the whole run. It reads the forecast rather than checking it: an optimistic forecast makes the leftover look smaller, so a member whose demand is under-predicted can be pressed without this number showing it. `capacity_hrs` is still on the row for a reader who wants the plain arithmetic remainder instead.
- **due_excess_hrs**: `due_hours` minus this window's own `capacity`, never below zero. A deadline belongs to its window and does not roll forward, so this is a per-window figure, unlike `backlog_excess_hrs`.

## Member facts
`history_13w` (weekly logged hours, arrival hours and task counts), `logged_hours_4w` (hours logged per week), `unlogged_tasks` (finished tasks with no time logged; their actual hours were estimated), `reopened_tasks`, `patterns` (see whf-pattern-discovery, including `overdue_hrs`: remaining hours of the member's open tasks already past their due date at the run day — a member fact, not tied to any one window), `likely_work` (see whf-likely-work). Each member also carries `days`, one entry per weekday of the horizon (`day`, `window`, `demand`, `capacity`, `overload`, `working_day`); a window's figures are the sums of its days, rounded. Each member also carries `pending_leaves`, served by `get_member_capacity`: the leave requests inside the horizon that are not approved yet (`start_date`, `end_date`, `leave_type`, `absence_hours`). They do not reduce capacity, and a window they touch may lose those hours if approved.

## Rebalancing candidates
Beside `overloaded` and `underloaded`, `rebalancing_candidates` carries two more lists: `backlog_pressed` (members with an above-zero `backlog_excess_hrs` in the run's last window; each entry has `member_id`, `name` and `backlog_excess_hrs`) and `deadline_pressed` (members with an above-zero `due_excess_hrs` in any window; each entry has `member_id`, `name` and `due_excess_hrs`, the largest over the windows).

## Team and run facts
`team.totals` (demand and capacity per window), `projects` (key, name, status, open and backlog task counts, first due date), `pending_holidays` (declared but unconfirmed), `data_quality` (unresolved assignments, unlogged tasks, history weeks), `run.windows` (`index`, `start`, `end`, `working_days`).

## Model facts
- **model**: `name` (`xgboost`, gradient boosting on engineered features) and `target` (`logged hours per member-week`) say what was fitted and to what. `mae` next to `mean_actual_hours` say how far off the model was on average, in hours, against the size of what it predicts; read them side by side and never as a verdict.
- **confidence**: `scored` when the backtest has at least one scored origin; `thin_history` otherwise, meaning `mae` and `mean_actual_hours` are absent and nothing should be said about accuracy.
- **backtest_origins**: the past dates the model was scored on. **limitations**: known blind spots of this run.
