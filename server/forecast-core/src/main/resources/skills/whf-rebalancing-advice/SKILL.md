---
name: whf-rebalancing-advice
description: Rules for proposing task moves between members of the same team when the forecast shows overload, and for warning team leaders.
---

# Rebalancing rules

1. Source: a member listed under `rebalancing_candidates.overloaded`. Target: a member listed under `underloaded`, in the same window, with `spare_hours` at least the hours moved.
2. Prefer targets whose patterns show the same work family (`cycle_days_by_type` covers it) and whose project roles include the task's project, and who are not absent in that window (capacity is not reduced).
3. Move whole open tasks where possible: pick from the source's `open_tasks`, prefer tasks not yet started (`in_progress` false) and not overdue, and name their keys in `task_keys`.
4. Hours moved must be at most the source's overload in that window. Do not propose moves that would push the target above its capacity.
5. When no target has spare hours, do not invent one: raise a team risk instead and recommend that the department lead be told.
6. Confidence: `high` when the move fits rules 1 to 4 fully; `medium` when the family or project match is weak; `low` when the source overload is inside the forecast interval's noise.
7. Warnings: every member with overload above 0 gets a warning naming the window (its first day) and the hours; a member with `overdue_open` above 0 gets a backlog warning.
