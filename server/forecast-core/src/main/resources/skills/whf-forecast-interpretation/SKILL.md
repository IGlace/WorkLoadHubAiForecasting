---
name: whf-forecast-interpretation
description: How to explain a member's forecast over the two windows, capacity, overload, interval and the model quality facts without adding or changing any number.
---

# Interpreting the forecast

- Lead with the decision-relevant figure: overload hours per window, then demand versus capacity.
- Say where the demand comes from: `open_hours` (already assigned work), `new_hours` (expected arrivals) and `planned_hours` (backlog work allocated to this member). A high open share means the backlog, not new work, is the problem; planned hours are work that exists but is not assigned yet.
- Capacity below 40 h in a window means holidays, an absence or the team's capacity plan; `working_days` and `absence_hours` on the row say which. Name the cause.
- An interval (low, high) that spans more than half of capacity means the history is noisy; say the forecast is uncertain rather than quoting the band as fact.
- Overdue open tasks are placed forward from the first forecast day; they inflate window one by design. Mention `overdue_open` (in patterns) or the overdue tasks when they drive the overload.
- `due_hours` above capacity in a window means deadlines, not arrivals, are the pressure; say so.
- A single day in `days` whose demand is well above its capacity is worth naming when the window total hides it: say the day (ISO date) and the two figures as given.
- Projects with backlog tasks (`team.planned_backlog`) raise expected work; cite the project key.
- Model quality: `champion_mase` well below 1.0 is reliable; near or above 1.0 means the numbers are close to a naive repeat of history and the narrative should say so. `xgboost` is gradient boosting on engineered features; `seasonal_naive` repeats history. When `model.unavailable` names a model, say the forecast used the other one and give the reason in plain words.
- Never round, sum, subtract or convert numbers yourself. If a derived figure is not in the facts, describe the relationship in words.
- Risk levels: high when overload is greater than 0 in either window or `overdue_open` is at least 3; medium when demand is above 85 percent of capacity or the interval's high crosses capacity; low otherwise.
