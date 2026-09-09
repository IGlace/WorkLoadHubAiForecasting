package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Row builders with every column of the export, in the export's column order. */
final class Rows {

    private Rows() {
    }

    static LinkedHashMap<String, Object> task(UUID id, String key, String title, String description, LocalDate dueDate,
            String priority, UUID projectId, UUID assigneeId, UUID reporterId, long taskNumber, LocalDateTime createdDate,
            LocalDate plannedWeek, UUID typeId, UUID parentId, UUID statusId, Double estimate) {
        LinkedHashMap<String, Object> t = new LinkedHashMap<>();
        t.put("id", id.toString());
        t.put("key", key);
        t.put("title", title);
        t.put("version", 0L);
        t.put("archived", false);
        t.put("due_date", dueDate == null ? null : dueDate.toString());
        t.put("priority", priority);
        t.put("created_at", createdDate.toString());
        t.put("project_id", projectId.toString());
        t.put("updated_at", createdDate.toString());
        t.put("archived_at", null);
        t.put("assignee_id", assigneeId == null ? null : assigneeId.toString());
        t.put("description", description);
        t.put("reporter_id", reporterId.toString());
        t.put("task_number", taskNumber);
        t.put("created_date", createdDate.toString());
        t.put("planned_week", plannedWeek == null ? null : plannedWeek.toString());
        t.put("started_date", null);
        t.put("task_type_id", typeId.toString());
        t.put("finished_date", null);
        t.put("parent_task_id", parentId == null ? null : parentId.toString());
        t.put("task_status_id", statusId.toString());
        t.put("last_reopened_at", null);
        t.put("reopened_from_done", false);
        t.put("original_estimate_hrs", estimate);
        t.put("remaining_estimate_hrs", estimate);
        return t;
    }

    static LinkedHashMap<String, Object> history(UUID id, UUID taskId, UUID userId, String field, String oldValue,
            String newValue, LocalDateTime changedAt) {
        LinkedHashMap<String, Object> h = new LinkedHashMap<>();
        h.put("id", id.toString());
        h.put("task_id", taskId.toString());
        h.put("user_id", userId.toString());
        h.put("new_value", newValue);
        h.put("old_value", oldValue);
        h.put("changed_at", changedAt.toString());
        h.put("created_at", changedAt.toString());
        h.put("field_name", field);
        h.put("updated_at", changedAt.toString());
        return h;
    }

    static LinkedHashMap<String, Object> timeLog(UUID id, UUID taskId, UUID userId, double hours, LocalDate day, String note) {
        LinkedHashMap<String, Object> l = new LinkedHashMap<>();
        LocalDateTime stamp = day.atTime(17, 30);
        l.put("id", id.toString());
        l.put("note", note);
        l.put("hours", hours);
        l.put("task_id", taskId.toString());
        l.put("user_id", userId.toString());
        l.put("log_date", day.toString());
        l.put("created_at", stamp.toString());
        l.put("updated_at", stamp.toString());
        return l;
    }
}
