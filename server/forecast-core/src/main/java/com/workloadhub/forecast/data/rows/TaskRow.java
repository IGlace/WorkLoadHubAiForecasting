package com.workloadhub.forecast.data.rows;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** One row of `tasks`, excluding archived tasks, with the task type name and status category resolved. */
public record TaskRow(UUID id, String key, String title, UUID projectId, UUID assigneeId, UUID reporterId,
        UUID parentId, String typeName, String statusCategory, String priority, Double estimate, Double remaining,
        LocalDateTime createdDate, LocalDateTime startedDate, LocalDateTime finishedDate, LocalDate dueDate,
        boolean reopened, boolean archived) {

    public TaskRow withStatus(String statusCategory) {
        return new TaskRow(id, key, title, projectId, assigneeId, reporterId, parentId, typeName, statusCategory,
                priority, estimate, remaining, createdDate, startedDate, finishedDate, dueDate, reopened, archived);
    }

    public TaskRow withStarted(LocalDateTime startedDate) {
        return new TaskRow(id, key, title, projectId, assigneeId, reporterId, parentId, typeName, statusCategory,
                priority, estimate, remaining, createdDate, startedDate, finishedDate, dueDate, reopened, archived);
    }

    public TaskRow withFinished(LocalDateTime finishedDate) {
        return new TaskRow(id, key, title, projectId, assigneeId, reporterId, parentId, typeName, statusCategory,
                priority, estimate, remaining, createdDate, startedDate, finishedDate, dueDate, reopened, archived);
    }

    public TaskRow withRemaining(Double remaining) {
        return new TaskRow(id, key, title, projectId, assigneeId, reporterId, parentId, typeName, statusCategory,
                priority, estimate, remaining, createdDate, startedDate, finishedDate, dueDate, reopened, archived);
    }

    public TaskRow withType(String typeName) {
        return new TaskRow(id, key, title, projectId, assigneeId, reporterId, parentId, typeName, statusCategory,
                priority, estimate, remaining, createdDate, startedDate, finishedDate, dueDate, reopened, archived);
    }

    public TaskRow withParent(UUID parentId) {
        return new TaskRow(id, key, title, projectId, assigneeId, reporterId, parentId, typeName, statusCategory,
                priority, estimate, remaining, createdDate, startedDate, finishedDate, dueDate, reopened, archived);
    }

    public TaskRow withReporter(UUID reporterId) {
        return new TaskRow(id, key, title, projectId, assigneeId, reporterId, parentId, typeName, statusCategory,
                priority, estimate, remaining, createdDate, startedDate, finishedDate, dueDate, reopened, archived);
    }

    public TaskRow withReopened(boolean reopened) {
        return new TaskRow(id, key, title, projectId, assigneeId, reporterId, parentId, typeName, statusCategory,
                priority, estimate, remaining, createdDate, startedDate, finishedDate, dueDate, reopened, archived);
    }

    public TaskRow withDue(LocalDate dueDate) {
        return new TaskRow(id, key, title, projectId, assigneeId, reporterId, parentId, typeName, statusCategory,
                priority, estimate, remaining, createdDate, startedDate, finishedDate, dueDate, reopened, archived);
    }

    public TaskRow withProject(UUID projectId) {
        return new TaskRow(id, key, title, projectId, assigneeId, reporterId, parentId, typeName, statusCategory,
                priority, estimate, remaining, createdDate, startedDate, finishedDate, dueDate, reopened, archived);
    }
}
