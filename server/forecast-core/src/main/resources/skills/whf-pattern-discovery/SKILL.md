---
name: whf-pattern-discovery
description: How to read the per-member pattern statistics of a forecast run and turn them into evidenced findings about assignment style, rhythm, trend, estimate bias, cycle time and lateness.
---

# Reading pattern statistics

Each member's `patterns` object is computed deterministically over the last 13 weeks (arrivals) and the whole history (completions). Report a pattern only when the statistic supports it, and quote the statistic as evidence.

| Statistic | Meaning | Say something when |
|-----------|---------|--------------------|
| share_self_picked, share_assigned | share of the recent tasks the member picked themselves versus received from someone else, over the tasks whose assigner is recorded | one share is at least 0.7 (dominant style) |
| top_weekday, weekday_shares | weekday on which tasks most often arrive, by task count | the top share is at least 0.35 |
| logged_weekday_shares | weekday on which the member actually works, by logged hours — a different thing from weekday_shares, which counts when work arrives rather than when it gets done | one day carries most of the shape and it is worth naming |
| hours_per_week_13w, trend_hours_per_week | average weekly arrival hours and its slope per week | the slope is at least 5 percent of the average in either direction |
| estimate_ratio_median | actual hours over estimated hours on completed tasks | below 0.85 (over-estimates) or above 1.15 (under-estimates) |
| cycle_days_median, cycle_days_by_type | assignment-to-completion days, overall and per work family | notably longer than the team's other members for the same family |
| lateness_days_median, share_late | completion relative to due date | share_late above 0.4 or median lateness above 2 days |
| hours_by_project, share_with_project | how work is spread over projects | one project takes more than 0.6 of the hours |
| cluster | members with similar behaviour share a cluster number | when explaining that a member behaves like others |
| open_tasks, open_est_hours, overdue_open | the member's queue: tasks assigned to them and not finished | overdue_open is greater than 0 |

Use the kinds `assignment_style`, `weekday_rhythm`, `trend`, `estimate_bias`, `cycle_time`, `lateness`, `project_phase`, `cluster` or `other`. A `null` statistic means there is not enough history: say so rather than guessing.
