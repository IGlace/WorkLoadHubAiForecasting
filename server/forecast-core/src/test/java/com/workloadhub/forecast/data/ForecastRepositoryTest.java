package com.workloadhub.forecast.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.testing.SeededData;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class ForecastRepositoryTest {

    /** What the seeded database itself holds, now that the tests read the fixture rather than the envelope. */
    static long count(DataSource ds, String sql) {
        return JdbcClient.create(ds).sql(sql).query(Long.class).single();
    }

    @Test
    void countsEveryActiveUserOfACountedRole() {
        ForecastData data = SeededData.data();
        assertFalse(data.members().isEmpty());
        for (MemberRow m : data.members()) {
            assertTrue(Set.of("MEMBER", "TEAM_LEADER").contains(m.role()), m.role());
        }
        // Team membership is no longer a condition: every such user is counted, exactly (design 2026-09-21).
        long rows = count(SeededData.dataSource(),
                "SELECT COUNT(*) FROM users WHERE role IN ('MEMBER', 'TEAM_LEADER') AND active");
        assertEquals(rows, data.members().size());
    }

    @Test
    void aMemberCarriesTheirManagerAndDepartmentFromTheUsersTable() {
        ForecastData data = SeededData.data();
        assertTrue(data.members().stream().anyMatch(m -> m.managerId() != null), "the seed has a hierarchy");
        assertTrue(data.members().stream().anyMatch(m -> m.department() != null), "and departments");
        for (MemberRow m : data.members()) {
            if (m.managerId() != null) {
                assertNotNull(data.userById().get(m.managerId()), "a manager id resolves to a user");
                assertTrue(data.membersOfTeam(m.managerId()).contains(m), "a member is in their manager's team");
            }
            assertTrue(data.membersOfTeam(m.id()).contains(m), "and keys a team of their own");
        }
    }

    @Test
    void theJoinedDateIsTheMembersFirstActivity() {
        ForecastData data = SeededData.data();
        Map<UUID, LocalDate> firstLog = new HashMap<>();
        for (var l : data.timeLogs()) {
            firstLog.merge(l.userId(), l.day(), (a, b) -> a.isBefore(b) ? a : b);
        }
        assertFalse(firstLog.isEmpty());
        int checked = 0;
        for (MemberRow m : data.members()) {
            LocalDate log = firstLog.get(m.id());
            if (log == null) {
                continue;
            }
            assertNotNull(m.joined(), m.fullName() + " logged hours and so has a joined date");
            assertFalse(m.joined().isAfter(log), "joined " + m.joined() + " is not after the first logged day " + log);
            checked++;
        }
        assertTrue(checked > 0, "at least one member logged hours");
    }

    @Test
    void tasksTransitionsAndLogsAreTypedAndOrdered() {
        ForecastData data = SeededData.data();
        assertEquals(count(SeededData.dataSource(), "SELECT COUNT(*) FROM tasks WHERE archived = FALSE"), data.tasks().size());
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
        assertEquals(count(SeededData.dataSource(), "SELECT COUNT(*) FROM time_logs"), data.timeLogs().size());
        assertEquals(9, data.statusCategoryByName().size());
        assertEquals("DONE", data.statusCategoryByName().get("Done"));
    }

    @Test
    void holidaysLeavesAndProjectsArePresentAndTheCapacityTablesAreNeverRead() {
        ForecastData data = SeededData.data();
        assertTrue(data.holidays().stream().anyMatch(h -> h.confirmed() && h.active()));
        long approved = count(SeededData.dataSource(), "SELECT COUNT(*) FROM personal_leaves WHERE status = 'APPROVED'");
        long pending = count(SeededData.dataSource(), "SELECT COUNT(*) FROM personal_leaves WHERE status = 'PENDING'");
        assertEquals(approved, data.leaves().size());
        assertEquals(pending, data.pendingLeaves().size());
        assertTrue(data.leaves().stream().allMatch(l -> "APPROVED".equals(l.status()) && l.absenceHours() != null && l.absenceHours() > 0));
        assertEquals(count(SeededData.dataSource(), "SELECT COUNT(*) FROM projects WHERE archived = FALSE"), data.projects().size());
        MemberRow m = data.members().get(0);
        assertFalse(data.projectIdsOfTeam(m.managerId() == null ? m.id() : m.managerId()).isEmpty());
        assertEquals(count(SeededData.dataSource(), "SELECT COUNT(*) FROM users"), data.users().size());
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
        DataSource ds = SeededData.freshDataSource();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE user_capacity");
            st.execute("DROP TABLE team_capacity");
            st.execute("DROP TABLE absences");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        ForecastData without = new ForecastRepository(JdbcClient.create(ds)).loadAll();
        assertEquals(data.leaves().size(), without.leaves().size());
    }

    @Test
    void rejectedAndCancelledLeavesAreNotLoadedAndTimesAreParsed() {
        DataSource ds = SeededData.freshDataSource();
        JdbcClient client = JdbcClient.create(ds);
        UUID member = client.sql("SELECT id FROM users ORDER BY id LIMIT 1").query(UUID.class).single();
        client.sql("DELETE FROM personal_leaves").update();
        for (String status : List.of("APPROVED", "PENDING", "REJECTED", "CANCELLED")) {
            client.sql("INSERT INTO personal_leaves (id, employee_id, start_date, end_date, begin_time, end_time, absence_hours, status, leave_type)"
                    + " VALUES (?, ?, '2026-09-07', '2026-09-09', '13:00:00', NULL, 22.0, ?, 'PAID_LEAVE')")
                    .param(UUID.randomUUID()).param(member).param(status).update();
        }
        ForecastData data = new ForecastRepository(JdbcClient.create(ds)).loadAll();
        assertEquals(1, data.leaves().size());
        assertEquals(1, data.pendingLeaves().size());
        assertEquals(java.time.LocalTime.of(13, 0), data.leaves().get(0).beginTime());
        assertEquals(22.0, data.leaves().get(0).absenceHours());
        assertEquals("PAID_LEAVE", data.leaves().get(0).leaveType());
    }
}
