package com.workloadhub.forecast.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.testing.SeededData;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ForecastRepositoryTest {

    @Test
    void loadsCountedMembersWithTheirTeams() {
        ForecastData data = SeededData.data();
        assertFalse(data.members().isEmpty());
        for (MemberRow m : data.members()) {
            assertTrue(Set.of("MEMBER", "TEAM_LEADER").contains(m.role()), m.role());
            assertFalse(m.teamIds().isEmpty());
            assertTrue(m.teamIds().contains(m.primaryTeamId()));
            assertNotNull(m.joined());
        }
        long rows = SeededData.envelope().rows("users").stream()
                .filter(u -> List.of("MEMBER", "TEAM_LEADER").contains(u.get("role")) && Boolean.TRUE.equals(u.get("active")))
                .count();
        assertTrue(data.members().size() <= rows && data.members().size() >= rows - 5, "counted members " + data.members().size() + " of " + rows);
    }

    @Test
    void primaryTeamPrefersTheManagerTeam() {
        ForecastData data = SeededData.data();
        long withParent = data.members().stream()
                .filter(m -> data.teamById().get(m.primaryTeamId()).parentId() != null).count();
        assertTrue(withParent > data.members().size() / 2, "most members sit in a manager team");
    }

    @Test
    void tasksTransitionsAndLogsAreTypedAndOrdered() {
        ForecastData data = SeededData.data();
        assertEquals(SeededData.envelope().rows("tasks").size(), data.tasks().size());
        TaskRow first = data.tasks().get(0);
        assertNotNull(first.createdDate());
        assertTrue(Set.of("TO_DO", "IN_PROGRESS", "DONE").contains(first.statusCategory()));
        for (int i = 1; i < data.tasks().size(); i++) {
            assertTrue(data.tasks().get(i - 1).id().toString().compareTo(data.tasks().get(i).id().toString()) < 0);
        }
        UUID anyTask = data.transitions().get(0).taskId();
        var mine = data.transitionsByTask().get(anyTask);
        for (int i = 1; i < mine.size(); i++) {
            assertFalse(mine.get(i).changedAt().isBefore(mine.get(i - 1).changedAt()));
        }
        assertEquals(SeededData.envelope().rows("time_logs").size(), data.timeLogs().size());
        assertEquals(9, data.statusCategoryByName().size());
        assertEquals("DONE", data.statusCategoryByName().get("Done"));
    }

    @Test
    void holidaysCapacityAbsencesAndProjectsArePresent() {
        ForecastData data = SeededData.data();
        assertTrue(data.holidays().stream().anyMatch(h -> h.confirmed() && h.active()));
        assertEquals(SeededData.envelope().rows("user_capacity").size(), data.capacity().size());
        assertEquals(SeededData.envelope().rows("absences").size(), data.absences().size());
        assertEquals(SeededData.envelope().rows("projects").size(), data.projects().size());
        MemberRow m = data.members().get(0);
        assertFalse(data.projectIdsOfTeamAndParent(m.primaryTeamId()).isEmpty());
        assertEquals(SeededData.envelope().rows("users").size(), data.users().size());
        assertEquals(SeededData.envelope().rows("team_capacity").size(), data.teamCapacity().size());
        for (int i = 1; i < data.capacity().size(); i++) {
            var prev = data.capacity().get(i - 1);
            var cur = data.capacity().get(i);
            int cmp = prev.userId().toString().compareTo(cur.userId().toString());
            assertTrue(cmp < 0 || (cmp == 0 && !cur.weekStart().isBefore(prev.weekStart())),
                    "capacity out of order at " + i);
        }
        for (int i = 1; i < data.absences().size(); i++) {
            var prev = data.absences().get(i - 1);
            var cur = data.absences().get(i);
            int cmp = prev.userId().toString().compareTo(cur.userId().toString());
            assertTrue(cmp < 0 || (cmp == 0 && !cur.day().isBefore(prev.day())),
                    "absences out of order at " + i);
        }
        for (int i = 1; i < data.holidays().size(); i++) {
            var prev = data.holidays().get(i - 1);
            var cur = data.holidays().get(i);
            int cmp = prev.start().compareTo(cur.start());
            assertTrue(cmp < 0 || (cmp == 0 && !cur.end().isBefore(prev.end())),
                    "holidays out of order at " + i);
        }
    }
}
