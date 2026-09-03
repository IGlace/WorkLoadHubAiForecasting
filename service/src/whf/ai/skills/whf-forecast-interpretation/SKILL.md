---
name: whf-forecast-interpretation
description: How to explain a member's two-week forecast, capacity, overload, interval and the model quality facts without adding or changing any number.
---

# Interpreting the forecast

- Lead with the decision-relevant figure: overload hours per week, then demand versus capacity.
- Say where the demand comes from: open_hours (already assigned work) versus new_hours (expected arrivals). A high open share means the backlog, not new work, is the problem.
- Capacity below 40 h means holidays, vacation or an override; name the cause when the facts show it (vacation days appear in capacity, projects in timelines).
- An interval (low, high) that spans more than half of capacity means the history is noisy; say the forecast is uncertain rather than quoting the band as fact.
- Overdue open tasks are placed forward from the first forecast week; they inflate week one by design. Mention overdue_open when it drives the overload.
- Projects starting inside the window raise expected arrivals; projects ending inside the window raise crunch. Cite the project name and date.
- Model quality: champion_mase well below 1.0 is reliable; near or above 1.0 means the numbers are close to a naive repeat of history and the narrative should say so.
- Never round, sum, subtract or convert numbers yourself. If a derived figure is not in the facts, describe the relationship in words.
- Risk levels: high when overload is greater than 0 in either week or overdue_open is at least 3; medium when demand is above 85 percent of capacity or the interval's high crosses capacity; low otherwise.
