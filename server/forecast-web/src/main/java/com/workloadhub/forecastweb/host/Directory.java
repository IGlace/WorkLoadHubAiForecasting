package com.workloadhub.forecastweb.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The WorkloadHub directory as this host reads it: users, teams and memberships. The production server has its
 * own services for these tables; the module itself only ever answers in ids, so a host joins the names.
 */
public final class Directory {

    public record User(UUID id, String fullName, String role, String jobTitle, String department, boolean active) {
    }

    public record Team(UUID id, String name, UUID managerId, UUID parentTeamId, int memberCount) {
    }

    public record Membership(UUID teamId, UUID userId) {
    }

    private final JdbcClient jdbc;

    public Directory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ActingUser> user(UUID id) {
        return jdbc.sql("SELECT id, full_name, role, job_title FROM users WHERE id = ?").param(id)
                .query().listOfRows().stream().findFirst()
                .map(r -> new ActingUser(uuid(r.get("id")), str(r.get("full_name")), str(r.get("role")), str(r.get("job_title"))));
    }

    public List<User> users() {
        List<User> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("SELECT id, full_name, role, job_title, department, active FROM users ORDER BY full_name").query().listOfRows()) {
            out.add(new User(uuid(r.get("id")), str(r.get("full_name")), str(r.get("role")), str(r.get("job_title")), str(r.get("department")),
                    bool(r.get("active"))));
        }
        return out;
    }

    public Map<UUID, User> usersById() {
        Map<UUID, User> out = new LinkedHashMap<>();
        for (User u : users()) {
            out.put(u.id(), u);
        }
        return out;
    }

    public List<Team> teams() {
        List<Team> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("""
                SELECT t.id, t.name, t.manager_id, t.parent_team_id,
                       (SELECT COUNT(*) FROM team_members tm WHERE tm.team_id = t.id) AS members
                FROM teams t ORDER BY t.name""").query().listOfRows()) {
            out.add(new Team(uuid(r.get("id")), str(r.get("name")), uuid(r.get("manager_id")), uuid(r.get("parent_team_id")),
                    ((Number) r.get("members")).intValue()));
        }
        return out;
    }

    public Optional<Team> team(UUID id) {
        return teams().stream().filter(t -> t.id().equals(id)).findFirst();
    }

    public List<Membership> memberships() {
        List<Membership> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("SELECT team_id, user_id FROM team_members").query().listOfRows()) {
            out.add(new Membership(uuid(r.get("team_id")), uuid(r.get("user_id"))));
        }
        return out;
    }

    public List<UUID> membersOf(UUID teamId) {
        return jdbc.sql("SELECT tm.user_id FROM team_members tm JOIN users u ON u.id = tm.user_id WHERE tm.team_id = ?"
                + " ORDER BY u.full_name").param(teamId).query().listOfRows().stream().map(r -> uuid(r.get("user_id"))).toList();
    }

    static UUID uuid(Object o) {
        return o == null ? null : UUID.fromString(String.valueOf(o));
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static boolean bool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.intValue() != 0;
        }
        return o != null && (o.toString().equals("1") || o.toString().equalsIgnoreCase("true"));
    }
}
