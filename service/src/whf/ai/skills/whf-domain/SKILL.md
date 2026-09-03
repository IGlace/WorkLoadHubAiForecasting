---
name: whf-domain
description: Vocabulary and data dictionary of WorkloadHub AI Forecasting. Use when reading run facts about departments, teams, members, tasks, capacity, demand or overload.
---

# WorkloadHub domain

## Organisation
- A **department** is led by a **skill team leader**, who manages but holds no tasks and is never in the workload.
- A **team** belongs to one department and is led by a **team leader**, who also does technical work and is counted like any member.
- A **member** is a person counted in the workload. Weeks start on Monday; working days are Monday to Friday; Morocco public holidays are off days.

## Task record
id, title, project_id, assignee_id, team_id, type (feature, bug, support, analysis, maintenance), priority (low, medium, high), status (todo, in_progress, done), created_at, assigned_at, due_date, completed_at, estimated_hours, actual_hours, assignment_mode (manual: assigned by the team leader; self_picked: taken by the member; project: driven by a project's phase).

## Forecast rows (per member, per forecast week)
- **demand**: predicted hours of work, uncapped. It is the sum of **open_hours** (remaining hours of tasks already assigned, placed by the effort model) and **new_hours** (hours of tasks predicted to arrive, placed the same way).
- **capacity**: available hours after holidays, vacations and overrides (default 40 h per week).
- **overload**: max(0, demand minus capacity). Demand is never cut to fit capacity.
- **low / high**: an interval around demand from the model's backtest residuals; wide bands mean an unstable history.

## Model facts
- **champion**: the arrival model that won the backtest (seasonal_naive, tsb or gbm). **champion_mase** below 1.0 means it beat the seasonal-naive floor; near 1.0 means little better than repeating history.
- **backtest_origins**: the past dates the models were scored on. **limitations**: known blind spots of this run.
