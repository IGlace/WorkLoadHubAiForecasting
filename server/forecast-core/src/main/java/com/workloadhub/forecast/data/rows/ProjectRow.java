package com.workloadhub.forecast.data.rows;

import java.util.UUID;

/** One row of `projects`, excluding archived projects. */
public record ProjectRow(UUID id, String key, String name, String status, UUID teamId) {
}
