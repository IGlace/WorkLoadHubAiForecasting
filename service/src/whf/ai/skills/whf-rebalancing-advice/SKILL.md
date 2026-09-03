---
name: whf-rebalancing-advice
description: Rules for proposing task moves between members of the same team when the forecast shows overload, and for warning team leaders and skill team leaders.
---

# Rebalancing rules

1. Source: a member listed under rebalancing_candidates.overloaded. Target: a member listed under underloaded, in the same week, with spare_hours at least the hours moved.
2. Prefer targets whose patterns show the same task types (cycle_days_by_type covers the type) and who are not on vacation that week (capacity is not reduced).
3. Move whole open tasks where possible: pick from the source's open_tasks, prefer tasks not yet started (status todo) and not overdue, and name them.
4. Hours moved must be at most the source's overload in that week. Do not propose moves that would push the target above its capacity.
5. When no target has spare hours, do not invent one: raise a team risk instead and recommend that the skill team leader be told.
6. Confidence: high when the move fits rules 1 to 4 fully; medium when the target's type match is weak; low when the source overload is inside the forecast interval's noise.
7. Warnings: every member with overload above 0 gets a warning naming the week and the hours; a member with overdue_open above 0 gets a backlog warning.
