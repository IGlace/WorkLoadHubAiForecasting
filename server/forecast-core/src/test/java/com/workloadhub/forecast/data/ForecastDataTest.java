package com.workloadhub.forecast.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.data.rows.TransitionRow;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A team is a leader and the people whose {@code manager_id} names them (design 2026-09-21, section 4).
 * The {@code teams} table is not read at all: it holds project teams, which are a different thing.
 */
class ForecastDataTest {

    private static final UUID LEAD = TestData.id("member-lead");
    private static final UUID OTHER_LEAD = TestData.id("member-other-lead");

    @Test
    void aTeamIsItsLeaderAndTheirDirectReports() {
        MemberRow lead = TestData.leader("lead", null);
        MemberRow a = TestData.member("a", LEAD);
        MemberRow b = TestData.member("b", LEAD);
        MemberRow elsewhere = TestData.member("c", OTHER_LEAD);
        ForecastData data = TestData.data(List.of(lead, a, b, elsewhere), List.of(), List.of(), List.of());

        assertEquals(Set.of(lead.id(), a.id(), b.id()),
                data.membersOfTeam(LEAD).stream().map(MemberRow::id).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void aLeaderIsInTheirOwnTeamAndInTheirManagersTeam() {
        MemberRow top = TestData.leader("other-lead", null);
        MemberRow lead = TestData.leader("lead", OTHER_LEAD);
        MemberRow a = TestData.member("a", LEAD);
        ForecastData data = TestData.data(List.of(top, lead, a), List.of(), List.of(), List.of());

        assertTrue(data.membersOfTeam(LEAD).contains(lead), "the leader is counted in their own team");
        assertTrue(data.membersOfTeam(OTHER_LEAD).contains(lead), "and is a member of their manager's team");
        assertEquals(2, data.membersOfTeam(LEAD).size());
        assertEquals(2, data.membersOfTeam(OTHER_LEAD).size());
    }

    @Test
    void aTeamWithNoSuchLeaderIsEmptyRatherThanEveryone() {
        MemberRow a = TestData.member("a", LEAD);
        ForecastData data = TestData.data(List.of(a), List.of(), List.of(), List.of());

        assertTrue(data.membersOfTeam(TestData.id("nobody")).isEmpty());
    }

    @Test
    void aTeamsProjectsAreTheProjectsOfItsMembersTasks() {
        MemberRow lead = TestData.leader("lead", null);
        MemberRow a = TestData.member("a", LEAD);
        MemberRow elsewhere = TestData.member("c", OTHER_LEAD);
        UUID ours = TestData.id("project-ours");
        UUID theirs = TestData.id("project-theirs");
        LocalDateTime at = LocalDateTime.of(2026, 1, 6, 9, 0);

        TaskRow mine = TestData.taskIn("1", a.id(), ours, at, 4);
        TaskRow leads = TestData.taskIn("2", lead.id(), ours, at, 4);
        TaskRow notMine = TestData.taskIn("3", elsewhere.id(), theirs, at, 4);
        List<ProjectRow> projects = List.of(
                new ProjectRow(ours, "OURS", "Ours", "ACTIVE"),
                new ProjectRow(theirs, "THEIRS", "Theirs", "ACTIVE"));
        List<TransitionRow> transitions = List.of();
        List<TimeLogRow> logs = List.of();
        ForecastData data = TestData.data(List.of(lead, a, elsewhere), List.of(mine, leads, notMine), transitions, logs)
                .withProjects(projects);

        assertEquals(Set.of(ours), data.projectIdsOfTeam(LEAD));
        assertEquals(Set.of(theirs), data.projectIdsOfTeam(OTHER_LEAD));
    }

    @Test
    void anUnassignedTaskBelongsToNoTeamsProjects() {
        MemberRow lead = TestData.leader("lead", null);
        UUID orphan = TestData.id("project-orphan");
        TaskRow nobodys = TestData.taskIn("9", null, orphan, LocalDateTime.of(2026, 1, 6, 9, 0), 4);
        ForecastData data = TestData.data(List.of(lead), List.of(nobodys), List.of(), List.of())
                .withProjects(List.of(new ProjectRow(orphan, "ORPH", "Orphan", "ACTIVE")));

        assertTrue(data.projectIdsOfTeam(LEAD).isEmpty());
    }
}
