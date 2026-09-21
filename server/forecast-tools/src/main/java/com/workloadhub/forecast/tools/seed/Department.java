package com.workloadhub.forecast.tools.seed;

import java.util.List;
import java.util.UUID;

/**
 * One department of the company, identified by {@link Directory#deptCode}: the people in it and the head who
 * owns its projects. Departments are where work comes from; who reports to whom is a separate question, and a
 * team is answered by {@link Directory#teamKey} (design 2026-09-21).
 *
 * <p>This is not a row of the application's `teams` table. That table holds project teams, which the seed
 * writes from the work it generates, once it knows who actually worked on each project.
 */
public record Department(String code, String label, UUID headId, List<UUID> memberIds) {
}
