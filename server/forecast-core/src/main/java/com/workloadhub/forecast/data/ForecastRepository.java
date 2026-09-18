package com.workloadhub.forecast.data;

import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.LeaveRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TeamRow;
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

/** Reads the WorkloadHub tables into typed rows. Read only. */
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
        List<TeamRow> teams = jdbc.sql("SELECT id, name, manager_id, parent_team_id FROM teams")
                .query((rs, i) -> new TeamRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getObject("manager_id", UUID.class),
                        rs.getObject("parent_team_id", UUID.class)))
                .list();
        Map<UUID, TeamRow> teamById = new HashMap<>();
        teams.forEach(t -> teamById.put(t.id(), t));
        Map<UUID, List<UUID>> teamsOfUser = new HashMap<>();
        Map<UUID, LocalDate> joinedOfUser = new HashMap<>();
        jdbc.sql("SELECT team_id, user_id, joined_at FROM team_members").query((rs, i) -> {
            UUID user = rs.getObject("user_id", UUID.class);
            teamsOfUser.computeIfAbsent(user, k -> new ArrayList<>()).add(rs.getObject("team_id", UUID.class));
            joinedOfUser.merge(user, rs.getObject("joined_at", LocalDateTime.class).toLocalDate(), (a, b) -> a.isBefore(b) ? a : b);
            return null;
        }).list();
        List<UserRef> users = new ArrayList<>();
        List<MemberRow> members = new ArrayList<>();
        jdbc.sql("SELECT id, full_name, email, username, role, job_title, active, deactivated_at FROM users").query((rs, i) -> {
            UUID id = rs.getObject("id", UUID.class);
            users.add(new UserRef(id, rs.getString("full_name"), rs.getString("email"), rs.getString("username")));
            List<UUID> teamIds = teamsOfUser.getOrDefault(id, List.of());
            if (!rs.getBoolean("active") || !COUNTED_ROLES.contains(rs.getString("role")) || teamIds.isEmpty()) {
                return null;
            }
            List<UUID> sorted = teamIds.stream().sorted(Ids.UUID_ORDER).toList();
            UUID primary = sorted.stream()
                    .filter(t -> teamById.containsKey(t) && teamById.get(t).parentId() != null)
                    .findFirst().orElse(sorted.get(0));
            LocalDateTime left = rs.getObject("deactivated_at", LocalDateTime.class);
            members.add(new MemberRow(id, rs.getString("full_name"), rs.getString("email"), rs.getString("role"), rs.getString("job_title"),
                    sorted, primary, joinedOfUser.get(id), left == null ? null : left.toLocalDate()));
            return null;
        }).list();
        List<ProjectRow> projects = jdbc.sql("SELECT id, key, name, status, team_id FROM projects WHERE archived = FALSE")
                .query((rs, i) -> new ProjectRow(rs.getObject("id", UUID.class), rs.getString("key"), rs.getString("name"), rs.getString("status"),
                        rs.getObject("team_id", UUID.class)))
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
        List<TransitionRow> transitions = jdbc.sql("SELECT task_id, user_id, field_name, old_value, new_value, changed_at FROM task_history")
                .query((rs, i) -> new TransitionRow(rs.getObject("task_id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("field_name"),
                        rs.getString("old_value"), rs.getString("new_value"), rs.getObject("changed_at", LocalDateTime.class)))
                .list();
        List<TimeLogRow> logs = jdbc.sql("SELECT task_id, user_id, log_date, hours FROM time_logs")
                .query((rs, i) -> new TimeLogRow(rs.getObject("task_id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("log_date", LocalDate.class), rs.getDouble("hours")))
                .list();
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
        return new ForecastData(members, teams, projects, tasks, transitions, logs, leaves, pendingLeaves, holidays, users, categoryByName);
    }
}
