package com.workloadhub.forecast.data.rows;

import java.time.LocalDateTime;
import java.util.UUID;

/** One row of `task_history`. */
public record TransitionRow(UUID taskId, UUID userId, String field, String oldValue, String newValue, LocalDateTime changedAt) {
}
