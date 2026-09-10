package com.workloadhub.forecast.data;

import com.workloadhub.forecast.data.rows.AbsenceRow;
import com.workloadhub.forecast.data.rows.CapacityRow;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.data.rows.TransitionRow;
import com.workloadhub.forecast.data.rows.UserRef;
import com.workloadhub.forecast.store.Dialect;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads the WorkloadHub tables into typed rows. Read only; portable SQL only. */
public final class ForecastRepository {

    private static final Set<String> COUNTED_ROLES = Set.of("MEMBER", "TEAM_LEADER");

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public ForecastRepository(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    public ForecastData loadAll() {
        List<Map<String, Object>> statusRows = rows("SELECT id, name, category FROM task_statuses");
        Map<String, String> categoryByName = new TreeMap<>();
        Map<String, String> categoryById = new HashMap<>();
        for (Map<String, Object> r : statusRows) {
            categoryByName.put(str(r, "name"), str(r, "category"));
            categoryById.put(str(r, "id"), str(r, "category"));
        }
        Map<String, String> typeNameById = new HashMap<>();
        for (Map<String, Object> r : rows("SELECT id, name FROM task_types")) {
            typeNameById.put(str(r, "id"), str(r, "name"));
        }
        List<TeamRow> teams = new ArrayList<>();
        Map<UUID, TeamRow> teamById = new HashMap<>();
        for (Map<String, Object> r : rows("SELECT id, name, manager_id, parent_team_id FROM teams")) {
            TeamRow t = new TeamRow(uuid(r, "id"), str(r, "name"), uuid(r, "manager_id"), uuid(r, "parent_team_id"));
            teams.add(t);
            teamById.put(t.id(), t);
        }
        Map<UUID, List<UUID>> teamsOfUser = new HashMap<>();
        Map<UUID, LocalDate> joinedOfUser = new HashMap<>();
        for (Map<String, Object> r : rows("SELECT team_id, user_id, joined_at FROM team_members")) {
            UUID user = uuid(r, "user_id");
            teamsOfUser.computeIfAbsent(user, k -> new ArrayList<>()).add(uuid(r, "team_id"));
            LocalDate joined = dateTime(r, "joined_at").toLocalDate();
            joinedOfUser.merge(user, joined, (a, b) -> a.isBefore(b) ? a : b);
        }
        List<UserRef> users = new ArrayList<>();
        List<MemberRow> members = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT id, full_name, email, username, role, job_title, active, deactivated_at FROM users")) {
            UUID id = uuid(r, "id");
            users.add(new UserRef(id, str(r, "full_name"), str(r, "email"), str(r, "username")));
            boolean active = dialect.asBoolean(r.get("active"));
            List<UUID> teamIds = teamsOfUser.getOrDefault(id, List.of());
            if (!active || !COUNTED_ROLES.contains(str(r, "role")) || teamIds.isEmpty()) {
                continue;
            }
            List<UUID> sorted = teamIds.stream().sorted(Ids.UUID_ORDER).toList();
            UUID primary = sorted.stream()
                    .filter(t -> teamById.containsKey(t) && teamById.get(t).parentId() != null)
                    .findFirst().orElse(sorted.get(0));
            LocalDateTime left = dateTime(r, "deactivated_at");
            members.add(new MemberRow(id, str(r, "full_name"), str(r, "email"), str(r, "role"), str(r, "job_title"),
                    sorted, primary, joinedOfUser.get(id), left == null ? null : left.toLocalDate()));
        }
        List<ProjectRow> projects = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT id, key, name, status, team_id FROM projects WHERE archived = " + dialect.boolLiteral(false))) {
            projects.add(new ProjectRow(uuid(r, "id"), str(r, "key"), str(r, "name"), str(r, "status"), uuid(r, "team_id")));
        }
        List<TaskRow> tasks = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT id, key, title, project_id, assignee_id, reporter_id, parent_task_id, task_type_id,"
                + " task_status_id, priority, original_estimate_hrs, remaining_estimate_hrs, created_date, started_date, finished_date,"
                + " due_date, reopened_from_done, archived FROM tasks")) {
            if (dialect.asBoolean(r.get("archived"))) {
                continue;
            }
            tasks.add(new TaskRow(uuid(r, "id"), str(r, "key"), str(r, "title"), uuid(r, "project_id"), uuid(r, "assignee_id"),
                    uuid(r, "reporter_id"), uuid(r, "parent_task_id"), typeNameById.get(str(r, "task_type_id")),
                    categoryById.get(str(r, "task_status_id")), str(r, "priority"), dbl(r, "original_estimate_hrs"),
                    dbl(r, "remaining_estimate_hrs"), dateTime(r, "created_date"), dateTime(r, "started_date"),
                    dateTime(r, "finished_date"), date(r, "due_date"), dialect.asBoolean(r.get("reopened_from_done")), false));
        }
        List<TransitionRow> transitions = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT task_id, user_id, field_name, old_value, new_value, changed_at FROM task_history")) {
            transitions.add(new TransitionRow(uuid(r, "task_id"), uuid(r, "user_id"), str(r, "field_name"), str(r, "old_value"),
                    str(r, "new_value"), dateTime(r, "changed_at")));
        }
        List<TimeLogRow> logs = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT task_id, user_id, log_date, hours FROM time_logs")) {
            logs.add(new TimeLogRow(uuid(r, "task_id"), uuid(r, "user_id"), date(r, "log_date"), dbl(r, "hours")));
        }
        List<CapacityRow> capacity = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT user_id, week_start, base_capacity_hrs, absence_hrs, available_hrs FROM user_capacity")) {
            capacity.add(new CapacityRow(uuid(r, "user_id"), date(r, "week_start"), dbl(r, "base_capacity_hrs"),
                    dbl(r, "absence_hrs"), dbl(r, "available_hrs")));
        }
        List<AbsenceRow> absences = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT user_id, date, hours FROM absences")) {
            absences.add(new AbsenceRow(uuid(r, "user_id"), date(r, "date"), dbl(r, "hours")));
        }
        List<HolidayRow> holidays = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT start_date, end_date, status, active, title FROM holidays")) {
            holidays.add(new HolidayRow(date(r, "start_date"), date(r, "end_date"), "CONFIRMED".equals(str(r, "status")),
                    dialect.asBoolean(r.get("active")), str(r, "title")));
        }
        return new ForecastData(members, teams, projects, tasks, transitions, logs, capacity, absences, holidays, users, categoryByName);
    }

    private List<Map<String, Object>> rows(String sql) {
        return jdbc.sql(sql).query().listOfRows();
    }

    static String str(Map<String, Object> r, String col) {
        Object v = r.get(col);
        return v == null ? null : v.toString();
    }

    static UUID uuid(Map<String, Object> r, String col) {
        String s = str(r, col);
        return s == null || s.isBlank() ? null : UUID.fromString(s);
    }

    static Double dbl(Map<String, Object> r, String col) {
        Object v = r.get(col);
        return v == null ? null : ((Number) v).doubleValue();
    }

    static LocalDate date(Map<String, Object> r, String col) {
        Object v = r.get(col);
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        String s = v.toString();
        return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }

    static LocalDateTime dateTime(Map<String, Object> r, String col) {
        Object v = r.get(col);
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime();
        }
        String s = v.toString().replace(' ', 'T');
        return s.length() == 10 ? LocalDate.parse(s).atStartOfDay() : LocalDateTime.parse(s);
    }
}
