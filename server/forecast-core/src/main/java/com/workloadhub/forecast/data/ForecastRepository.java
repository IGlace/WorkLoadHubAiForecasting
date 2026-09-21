package com.workloadhub.forecast.data;

import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.LeaveRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.data.rows.TransitionRow;
import com.workloadhub.forecast.data.rows.UserRef;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads the WorkloadHub tables into typed rows. Read only.
 *
 * <p>`teams` and `team_members` are never read: they hold project teams, an ad-hoc group working on one
 * project, not the company structure. The structure is `users.manager_id`, and a team is a leader and the
 * people who report to them directly (design 2026-09-21).
 */
public final class ForecastRepository {

    private static final Set<String> COUNTED_ROLES = Set.of("MEMBER", "TEAM_LEADER");

    private final JdbcClient jdbc;

    public ForecastRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ForecastData loadAll() {
        Map<String, String> categoryByName = new TreeMap<>();
        Map<UUID, String> categoryById = new HashMap<>();
        jdbc.sql("SELECT id, name, category FROM task_statuses").query((rs, i) -> {
            categoryByName.put(rs.getString("name"), rs.getString("category"));
            categoryById.put(rs.getObject("id", UUID.class), rs.getString("category"));
            return null;
        }).list();
        Map<UUID, String> typeNameById = new HashMap<>();
        jdbc.sql("SELECT id, name FROM task_types").query((rs, i) -> typeNameById.put(rs.getObject("id", UUID.class), rs.getString("name"))).list();
        List<UserRef> users = new ArrayList<>();
        // Counted members without their joined date yet: it comes from activity, which is loaded below.
        List<MemberRow> counted = new ArrayList<>();
        jdbc.sql("SELECT id, full_name, email, username, role, job_title, department, manager_id, active, deactivated_at FROM users")
                .query((rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String department = rs.getString("department");
                    UUID managerId = rs.getObject("manager_id", UUID.class);
                    users.add(new UserRef(id, rs.getString("full_name"), rs.getString("email"), rs.getString("username"),
                            department, managerId));
                    if (!rs.getBoolean("active") || !COUNTED_ROLES.contains(rs.getString("role"))) {
                        return null;
                    }
                    LocalDateTime left = rs.getObject("deactivated_at", LocalDateTime.class);
                    counted.add(new MemberRow(id, rs.getString("full_name"), rs.getString("email"), rs.getString("role"),
                            rs.getString("job_title"), department, managerId,
                            null, left == null ? null : left.toLocalDate()));
                    return null;
                }).list();
        List<ProjectRow> projects = jdbc.sql("SELECT id, key, name, status FROM projects WHERE archived = FALSE")
                .query((rs, i) -> new ProjectRow(rs.getObject("id", UUID.class), rs.getString("key"), rs.getString("name"), rs.getString("status")))
                .list();
        List<TaskRow> tasks = jdbc.sql("SELECT id, key, title, project_id, assignee_id, reporter_id, parent_task_id, task_type_id,"
                + " task_status_id, priority, original_estimate_hrs, remaining_estimate_hrs, created_date, started_date, finished_date,"
                + " due_date, planned_week, reopened_from_done FROM tasks WHERE archived = FALSE")
                .query((rs, i) -> {
                    LocalDate pw = rs.getObject("planned_week", LocalDate.class);
                    return new TaskRow(rs.getObject("id", UUID.class), rs.getString("key"), rs.getString("title"), rs.getObject("project_id", UUID.class),
                            rs.getObject("assignee_id", UUID.class), rs.getObject("reporter_id", UUID.class), rs.getObject("parent_task_id", UUID.class),
                            typeNameById.get(rs.getObject("task_type_id", UUID.class)), categoryById.get(rs.getObject("task_status_id", UUID.class)),
                            rs.getString("priority"), rs.getObject("original_estimate_hrs", Double.class), rs.getObject("remaining_estimate_hrs", Double.class),
                            rs.getObject("created_date", LocalDateTime.class), rs.getObject("started_date", LocalDateTime.class),
                            rs.getObject("finished_date", LocalDateTime.class), rs.getObject("due_date", LocalDate.class),
                            pw == null ? null : Weeks.mondayOf(pw), rs.getBoolean("reopened_from_done"), false);
                })
                .list();
        // The member's first activity, which is their joined date (design 2026-09-21, section 5). Both signals
        // name the person directly, so neither needs the assignee-name resolution Lifecycle does.
        Map<UUID, LocalDate> firstActivity = new HashMap<>();
        List<TransitionRow> transitions = jdbc.sql("SELECT task_id, user_id, field_name, old_value, new_value, changed_at FROM task_history")
                .query((rs, i) -> {
                    TransitionRow row = new TransitionRow(rs.getObject("task_id", UUID.class), rs.getObject("user_id", UUID.class),
                            rs.getString("field_name"), rs.getString("old_value"), rs.getString("new_value"),
                            rs.getObject("changed_at", LocalDateTime.class));
                    if (row.userId() != null && row.changedAt() != null) {
                        earliest(firstActivity, row.userId(), row.changedAt().toLocalDate());
                    }
                    return row;
                })
                .list();
        List<TimeLogRow> logs = jdbc.sql("SELECT task_id, user_id, log_date, hours FROM time_logs")
                .query((rs, i) -> {
                    TimeLogRow row = new TimeLogRow(rs.getObject("task_id", UUID.class), rs.getObject("user_id", UUID.class),
                            rs.getObject("log_date", LocalDate.class), rs.getDouble("hours"));
                    if (row.userId() != null && row.day() != null) {
                        earliest(firstActivity, row.userId(), row.day());
                    }
                    return row;
                })
                .list();
        List<MemberRow> members = counted.stream().map(m -> m.withJoined(firstActivity.get(m.id()))).toList();
        List<LeaveRow> leaves = new ArrayList<>();
        List<LeaveRow> pendingLeaves = new ArrayList<>();
        jdbc.sql("SELECT employee_id, start_date, end_date, begin_time, end_time, absence_hours, status, leave_type"
                + " FROM personal_leaves WHERE status IN ('APPROVED', 'PENDING')").query((rs, i) -> {
                    LeaveRow row = new LeaveRow(rs.getObject("employee_id", UUID.class), rs.getObject("start_date", LocalDate.class),
                            rs.getObject("end_date", LocalDate.class), rs.getObject("begin_time", LocalTime.class), rs.getObject("end_time", LocalTime.class),
                            rs.getObject("absence_hours", Double.class), rs.getString("status"), rs.getString("leave_type"));
                    ("APPROVED".equals(row.status()) ? leaves : pendingLeaves).add(row);
                    return null;
                }).list();
        List<HolidayRow> holidays = jdbc.sql("SELECT start_date, end_date, status, active, title FROM holidays")
                .query((rs, i) -> new HolidayRow(rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                        "CONFIRMED".equals(rs.getString("status")), rs.getBoolean("active"), rs.getString("title")))
                .list();
        return new ForecastData(members, projects, tasks, transitions, logs, leaves, pendingLeaves, holidays, users, categoryByName);
    }

    private static void earliest(Map<UUID, LocalDate> into, UUID user, LocalDate day) {
        into.merge(user, day, (a, b) -> a.isBefore(b) ? a : b);
    }
}
