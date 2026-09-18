package com.workloadhub.forecast.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.testing.SeededData;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

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
    void holidaysLeavesAndProjectsArePresentAndTheCapacityTablesAreNeverRead() {
        ForecastData data = SeededData.data();
        assertTrue(data.holidays().stream().anyMatch(h -> h.confirmed() && h.active()));
        long approved = SeededData.envelope().rows("personal_leaves").stream().filter(l -> "APPROVED".equals(l.get("status"))).count();
        long pending = SeededData.envelope().rows("personal_leaves").stream().filter(l -> "PENDING".equals(l.get("status"))).count();
        assertEquals(approved, data.leaves().size());
        assertEquals(pending, data.pendingLeaves().size());
        assertTrue(data.leaves().stream().allMatch(l -> "APPROVED".equals(l.status()) && l.absenceHours() != null && l.absenceHours() > 0));
        assertEquals(SeededData.envelope().rows("projects").size(), data.projects().size());
        MemberRow m = data.members().get(0);
        assertFalse(data.projectIdsOfTeamAndParent(m.primaryTeamId()).isEmpty());
        assertEquals(SeededData.envelope().rows("users").size(), data.users().size());
        for (int i = 1; i < data.leaves().size(); i++) {
            var prev = data.leaves().get(i - 1);
            var cur = data.leaves().get(i);
            int cmp = prev.employeeId().toString().compareTo(cur.employeeId().toString());
            assertTrue(cmp < 0 || (cmp == 0 && !cur.start().isBefore(prev.start())), "leaves out of order at " + i);
        }
        for (int i = 1; i < data.holidays().size(); i++) {
            var prev = data.holidays().get(i - 1);
            var cur = data.holidays().get(i);
            int cmp = prev.start().compareTo(cur.start());
            assertTrue(cmp < 0 || (cmp == 0 && !cur.end().isBefore(prev.end())),
                    "holidays out of order at " + i);
        }
        // The three tables the module no longer reads: drop them and the load must still succeed.
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        new ExportImporter(ds).importAll(SeededData.envelope(), true);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE user_capacity");
            st.execute("DROP TABLE team_capacity");
            st.execute("DROP TABLE absences");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        ForecastData without = new ForecastRepository(JdbcClient.create(ds), Dialect.of(ds)).loadAll();
        assertEquals(data.leaves().size(), without.leaves().size());
    }

    @Test
    void rejectedAndCancelledLeavesAreNotLoadedAndTimesAreParsed() {
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        new ExportImporter(ds).importAll(SeededData.envelope(), true);
        String member = SeededData.envelope().rows("users").get(0).get("id").toString();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM personal_leaves");
            for (String status : List.of("APPROVED", "PENDING", "REJECTED", "CANCELLED")) {
                st.execute("INSERT INTO personal_leaves (id, employee_id, start_date, end_date, begin_time, end_time, absence_hours, status, leave_type)"
                        + " VALUES ('" + UUID.randomUUID() + "', '" + member + "', '2026-09-07', '2026-09-09', '13:00:00', NULL, 22.0, '" + status + "', 'PAID_LEAVE')");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        ForecastData data = new ForecastRepository(JdbcClient.create(ds), Dialect.of(ds)).loadAll();
        assertEquals(1, data.leaves().size());
        assertEquals(1, data.pendingLeaves().size());
        assertEquals(java.time.LocalTime.of(13, 0), data.leaves().get(0).beginTime());
        assertEquals(22.0, data.leaves().get(0).absenceHours());
        assertEquals("PAID_LEAVE", data.leaves().get(0).leaveType());
    }
}
