package com.workloadhub.forecastweb.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The WorkloadHub directory as this host reads it: users, and the teams the company structure makes of them.
 * The production server has its own services for these; the module itself only ever answers in ids, so a host
 * joins the names.
 *
 * <p>A team is a leader and the people who report to them directly, keyed by the leader's own user id (design
 * 2026-09-21). The application's `teams` table is not read: it holds project teams, an ad-hoc group around one
 * project, which say nothing about who manages whom.
 */
public final class Directory {

    public record User(UUID id, String fullName, String role, String jobTitle, String department, boolean active) {
    }

    /** {@code id} is the leader's user id; {@code parentTeamId} is the team of the leader's own manager. */
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

    /** Counted: what {@code ForecastRepository} counts, so the host's member lists agree with the module's. */
    private static final String COUNTED = " active = TRUE AND role IN ('MEMBER', 'TEAM_LEADER')";

    public List<Team> teams() {
        String sql = "SELECT m.id, m.full_name, m.manager_id,"
                + " (SELECT COUNT(*) FROM users r WHERE r.manager_id = m.id AND" + COUNTED + ") AS reports,"
                + " CASE WHEN m." + COUNTED + " THEN 1 ELSE 0 END AS counts_itself"
                + " FROM users m"
                + " WHERE EXISTS (SELECT 1 FROM users r WHERE r.manager_id = m.id AND" + COUNTED + ")"
                + " ORDER BY m.full_name";
        List<Team> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql(sql).query().listOfRows()) {
            int members = ((Number) r.get("reports")).intValue() + ((Number) r.get("counts_itself")).intValue();
            out.add(new Team(uuid(r.get("id")), str(r.get("full_name")), uuid(r.get("id")), uuid(r.get("manager_id")), members));
        }
        return out;
    }

    public Optional<Team> team(UUID id) {
        return teams().stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /** Every counted user's place: their manager's team, and their own when they lead one. */
    public List<Membership> memberships() {
        List<Membership> out = new ArrayList<>();
        for (Team t : teams()) {
            for (UUID member : membersOf(t.id())) {
                out.add(new Membership(t.id(), member));
            }
        }
        return out;
    }

    /** The leader, when they are counted themselves, and everyone who reports to them directly. */
    public List<UUID> membersOf(UUID teamId) {
        return jdbc.sql("SELECT id FROM users WHERE (id = ? OR manager_id = ?) AND" + COUNTED + " ORDER BY full_name")
                .param(teamId).param(teamId).query().listOfRows().stream().map(r -> uuid(r.get("id"))).toList();
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
