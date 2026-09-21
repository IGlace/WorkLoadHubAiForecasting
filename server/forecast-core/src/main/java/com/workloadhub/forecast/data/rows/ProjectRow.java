package com.workloadhub.forecast.data.rows;

import java.util.UUID;

/**
 * One row of `projects`, excluding archived projects. `projects.team_id` is not read: it names a project team
 * (an ad-hoc group working on the project), not a place in the company structure, so a team's projects are
 * found through its members' tasks instead (design 2026-09-21, section 6).
 */
public record ProjectRow(UUID id, String key, String name, String status) {
}
