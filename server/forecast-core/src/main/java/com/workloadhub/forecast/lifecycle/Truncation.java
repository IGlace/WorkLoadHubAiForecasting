package com.workloadhub.forecast.lifecycle;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.data.rows.TransitionRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The database as it stood at the end of a day: the replay behind the leakage test and the evaluation harness. */
public final class Truncation {

    private Truncation() {
    }

    public static ForecastData at(ForecastData data, LocalDate cutoff) {
        Lifecycle full = Lifecycle.derive(data);
        Lifecycle.Resolver resolver = new Lifecycle.Resolver(data);
        Map<UUID, List<TransitionRow>> history = data.transitionsByTask();
        Map<UUID, List<TimeLogRow>> logs = data.logsByTask();
        List<TransitionRow> keptTransitions = data.transitions().stream().filter(t -> !after(t.changedAt(), cutoff)).toList();
        List<TimeLogRow> keptLogs = data.timeLogs().stream().filter(l -> !l.day().isAfter(cutoff)).toList();
        List<TaskRow> tasks = new ArrayList<>();
        for (TaskRow t : data.tasks()) {
            if (after(t.createdDate(), cutoff)) {
                continue;
            }
            TaskFacts f = full.of(t.id());
            UUID assignee = assigneeAt(t, f, history.getOrDefault(t.id(), List.of()), resolver, cutoff);
            LocalDateTime started = after(t.startedDate(), cutoff) ? null : t.startedDate();
            LocalDateTime finished = f.finished() != null && !after(f.finished(), cutoff) ? f.finished() : null;
            String status = finished != null ? "DONE" : (started != null || (f.started() != null && !after(f.started(), cutoff)) ? "IN_PROGRESS" : "TO_DO");
            Double remaining = t.remaining();
            if (finished == null) {
                double logged = 0;
                for (TimeLogRow l : logs.getOrDefault(t.id(), List.of())) {
                    if (assignee != null && assignee.equals(l.userId()) && !l.day().isAfter(cutoff)) {
                        logged += l.hours();
                    }
                }
                remaining = t.estimate() == null ? null : Math.max(0.0, t.estimate() - logged);
            }
            tasks.add(new TaskRow(t.id(), t.key(), t.title(), t.projectId(), assignee, t.reporterId(), t.parentId(), t.typeName(),
                    status, t.priority(), t.estimate(), remaining, t.createdDate(), started, finished, t.dueDate(), t.reopened(), t.archived()));
        }
        return new ForecastData(data.members(), data.teams(), data.projects(), tasks, keptTransitions, keptLogs, data.capacity(),
                data.absences(), data.holidays(), data.users(), data.statusCategoryByName())
                .withTeamCapacity(data.teamCapacity());
    }

    private static boolean after(LocalDateTime t, LocalDate cutoff) {
        return t != null && t.toLocalDate().isAfter(cutoff);
    }

    private static UUID assigneeAt(TaskRow t, TaskFacts f, List<TransitionRow> history, Lifecycle.Resolver resolver, LocalDate cutoff) {
        TransitionRow last = null;
        for (TransitionRow row : history) {
            if ("assignee".equals(row.field()) && !after(row.changedAt(), cutoff)) {
                last = row;
            }
        }
        boolean currentAssignedByCutoff = f.isAssigned() && !after(f.assigned(), cutoff);
        if (last == null) {
            return currentAssignedByCutoff ? t.assigneeId() : null;
        }
        String value = last.newValue();
        if (value == null || value.isBlank() || value.equalsIgnoreCase("none") || value.equalsIgnoreCase("null")) {
            return null;
        }
        UUID resolved = resolver.resolve(value, t.assigneeId());
        return resolved != null ? resolved : (currentAssignedByCutoff ? t.assigneeId() : null);
    }
}
