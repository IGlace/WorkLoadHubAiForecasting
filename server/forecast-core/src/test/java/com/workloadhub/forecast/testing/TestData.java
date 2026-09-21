package com.workloadhub.forecast.testing;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
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

    /**
     * A team is keyed by its leader's user id (design 2026-09-21), so this is a person, not a team row.
     * {@code member(suffix, TEAM)} makes someone who reports to that leader, which is what puts them in the team.
     */
    public static final UUID TEAM = id("member-lead");
    /** The leader above {@link #TEAM}'s leader: the skill team leader a team's risks escalate to. */
    public static final UUID PARENT_TEAM = id("member-top");
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

    /** A counted MEMBER reporting to {@code manager}, and so forecast in {@code manager}'s team. */
    public static MemberRow member(String suffix, UUID manager) {
        return new MemberRow(id("member-" + suffix), "Member " + suffix, suffix + "@example.test", "MEMBER", "Engineer",
                manager, JOINED, null);
    }

    /** A TEAM_LEADER, who keys their own team and is counted inside it, reporting to {@code manager}. */
    public static MemberRow leader(String suffix, UUID manager) {
        return new MemberRow(id("member-" + suffix), "Member " + suffix, suffix + "@example.test", "TEAM_LEADER", "Team Leader",
                manager, JOINED, null);
    }

    public static UserRef user(MemberRow m) {
        return new UserRef(m.id(), m.fullName(), m.email(), m.email().substring(0, m.email().indexOf('@')), m.managerId());
    }

    public static TaskRow task(String suffix, UUID assignee, LocalDateTime created, double estimate) {
        return taskIn(suffix, assignee, null, created, estimate);
    }

    /** As {@link #task}, in a named project: a team's projects are those of its members' tasks. */
    public static TaskRow taskIn(String suffix, UUID assignee, UUID projectId, LocalDateTime created, double estimate) {
        return new TaskRow(id("task-" + suffix), "T-" + suffix, "Task " + suffix, projectId, assignee, id("reporter"), null,
                "Task", "TO_DO", "MEDIUM", estimate, estimate, created, null, null, null, null, false, false);
    }

    public static TransitionRow assignee(UUID task, String newValue, LocalDateTime at) {
        return new TransitionRow(task, id("reporter"), "assignee", null, newValue, at);
    }

    public static TransitionRow assignedBy(UUID task, UUID actor, String newValue, LocalDateTime at) {
        return new TransitionRow(task, actor, "assignee", null, newValue, at);
    }

    public static TransitionRow status(UUID task, String newStatus, LocalDateTime at) {
        return new TransitionRow(task, id("reporter"), "status", null, newStatus, at);
    }

    public static TimeLogRow log(UUID task, UUID user, LocalDate day, double hours) {
        return new TimeLogRow(task, user, day, hours);
    }

    public static ForecastData data(List<MemberRow> members, List<TaskRow> tasks, List<TransitionRow> transitions, List<TimeLogRow> logs) {
        List<UserRef> users = members.stream().map(TestData::user).toList();
        return new ForecastData(members, List.of(), tasks, transitions, logs, List.of(), List.of(), List.of(), users, STATUS_CATEGORIES);
    }
}
