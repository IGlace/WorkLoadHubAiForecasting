package com.workloadhub.forecast.lifecycle;

import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.data.rows.TaskRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** A task with the dates and hours the forecast derives from it. */
public record TaskFacts(
        TaskRow task,
        LocalDateTime assigned,
        LocalDateTime started,
        LocalDateTime finished,
        Family family,
        Mode mode,
        double actualHours,
        boolean unlogged,
        boolean assignmentFallback) {

    public UUID id() {
        return task.id();
    }

    public UUID assignee() {
        return task.assigneeId();
    }

    public double estimate() {
        return task.estimate() == null ? 0.0 : task.estimate();
    }

    public Double remaining() {
        return task.remaining();
    }

    public boolean done() {
        return "DONE".equals(task.statusCategory());
    }

    public boolean inProgress() {
        return "IN_PROGRESS".equals(task.statusCategory());
    }

    public boolean isAssigned() {
        return assigned != null;
    }

    public LocalDate assignedDay() {
        return assigned == null ? null : assigned.toLocalDate();
    }

    public LocalDate assignedWeek() {
        return assigned == null ? null : Weeks.mondayOf(assigned.toLocalDate());
    }

    public long lagDays() {
        return assigned == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(task.createdDate().toLocalDate(), assigned.toLocalDate()));
    }

    public boolean fresh() {
        return lagDays() < Lifecycle.BACKLOG_LAG_DAYS;
    }

    public Integer cycleDays() {
        if (assigned == null || finished == null) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(assigned.toLocalDate(), finished.toLocalDate()) + 1;
    }

    public Integer latenessDays() {
        if (finished == null || task.dueDate() == null) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(task.dueDate(), finished.toLocalDate());
    }

    /** Assigned on or before the day and not finished by its end. */
    public boolean openAtEndOf(LocalDate day) {
        if (assigned == null || assigned.toLocalDate().isAfter(day)) {
            return false;
        }
        return finished == null || finished.toLocalDate().isAfter(day);
    }

    public boolean inProgressAtEndOf(LocalDate day) {
        return openAtEndOf(day) && started != null && !started.toLocalDate().isAfter(day);
    }
}
