package com.workloadhub.forecast.testing;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.data.rows.TransitionRow;
import com.workloadhub.forecast.data.rows.UserRef;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Hand-built rows with sensible defaults, for rule tests that do not need the seed. */
public final class TestData {

    public static final UUID TEAM = UUID.fromString("40000000-0000-0000-0000-000000000001");
    public static final UUID PARENT_TEAM = UUID.fromString("40000000-0000-0000-0000-000000000000");
    public static final LocalDate JOINED = LocalDate.of(2026, 1, 5);
    public static final Map<String, String> STATUS_CATEGORIES = Map.of(
            "Open", "TO_DO", "To Do", "TO_DO", "On Hold", "TO_DO",
            "In Progress", "IN_PROGRESS", "In Review", "IN_PROGRESS", "Testing", "IN_PROGRESS", "Blocked", "IN_PROGRESS",
            "Done", "DONE", "Closed", "DONE");

    private TestData() {
    }

    public static UUID id(String suffix) {
        return UUID.nameUUIDFromBytes(suffix.getBytes(StandardCharsets.UTF_8));
    }

    public static MemberRow member(String suffix, UUID team) {
        return new MemberRow(id("member-" + suffix), "Member " + suffix, suffix + "@example.test", "MEMBER", "Engineer",
                List.of(team), team, JOINED, null);
    }

    public static UserRef user(MemberRow m) {
        return new UserRef(m.id(), m.fullName(), m.email(), m.email().substring(0, m.email().indexOf('@')));
    }

    public static TaskRow task(String suffix, UUID assignee, LocalDateTime created, double estimate) {
        return new TaskRow(id("task-" + suffix), "T-" + suffix, "Task " + suffix, null, assignee, id("reporter"), null,
                "Task", "TO_DO", "MEDIUM", estimate, estimate, created, null, null, null, false, false);
    }

    public static TransitionRow assignee(UUID task, String newValue, LocalDateTime at) {
        return new TransitionRow(task, id("reporter"), "assignee", null, newValue, at);
    }

    public static TransitionRow status(UUID task, String newStatus, LocalDateTime at) {
        return new TransitionRow(task, id("reporter"), "status", null, newStatus, at);
    }

    public static TimeLogRow log(UUID task, UUID user, LocalDate day, double hours) {
        return new TimeLogRow(task, user, day, hours);
    }

    public static ForecastData data(List<MemberRow> members, List<TaskRow> tasks, List<TransitionRow> transitions, List<TimeLogRow> logs) {
        List<TeamRow> teams = List.of(new TeamRow(PARENT_TEAM, "Dept", null, null), new TeamRow(TEAM, "Team", null, PARENT_TEAM));
        List<UserRef> users = members.stream().map(TestData::user).toList();
        return new ForecastData(members, teams, List.of(), tasks, transitions, logs, List.of(), List.of(), List.of(), users, STATUS_CATEGORIES);
    }
}
