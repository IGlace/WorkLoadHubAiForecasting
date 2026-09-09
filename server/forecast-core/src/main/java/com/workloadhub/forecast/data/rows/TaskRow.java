package com.workloadhub.forecast.data.rows;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** One row of `tasks`, excluding archived tasks, with the task type name and status category resolved. */
public record TaskRow(UUID id, String key, String title, UUID projectId, UUID assigneeId, UUID reporterId,
        UUID parentId, String typeName, String statusCategory, String priority, Double estimate, Double remaining,
        LocalDateTime createdDate, LocalDateTime startedDate, LocalDateTime finishedDate, LocalDate dueDate,
        boolean reopened, boolean archived) {
}
