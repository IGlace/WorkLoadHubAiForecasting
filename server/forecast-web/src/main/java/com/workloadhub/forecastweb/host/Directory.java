package com.workloadhub.forecastweb.host;

import com.workloadhub.forecast.data.EffectiveRole;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 *
 * <p>Neither is `users.role` trusted. Who leads comes from the job title, through the module's own
 * {@link EffectiveRole}, so this host and the module always agree on who is a leader and who is counted. That
 * rule needs the whole directory at once — a leader nobody reports to is a member — so the queries that used
 * to test the role in SQL are one read and a classification in Java.
 */
public final class Directory {

    public record User(UUID id, String fullName, String role, String jobTitle, String department, boolean active,
            UUID managerId) {
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

    /** Every user with their effective role, by name. The one read every other method here is built on. */
    public List<User> users() {
        List<User> raw = new ArrayList<>();
        List<EffectiveRole.Candidate> candidates = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("SELECT id, full_name, role, job_title, department, active, manager_id"
                + " FROM users ORDER BY full_name").query().listOfRows()) {
            User u = new User(uuid(r.get("id")), str(r.get("full_name")), str(r.get("role")), str(r.get("job_title")),
                    str(r.get("department")), bool(r.get("active")), uuid(r.get("manager_id")));
            raw.add(u);
            candidates.add(new EffectiveRole.Candidate(u.id(), u.managerId(), u.role(), u.jobTitle(), u.active()));
        }
        Map<UUID, String> roles = EffectiveRole.resolve(candidates);
        List<User> out = new ArrayList<>(raw.size());
        for (User u : raw) {
            out.add(new User(u.id(), u.fullName(), roles.get(u.id()), u.jobTitle(), u.department(), u.active(), u.managerId()));
        }
        return out;
    }

    public Optional<ActingUser> user(UUID id) {
        return users().stream().filter(u -> u.id().equals(id)).findFirst()
                .map(u -> new ActingUser(u.id(), u.fullName(), u.role(), u.jobTitle()));
    }

    public Map<UUID, User> usersById() {
        Map<UUID, User> out = new LinkedHashMap<>();
        for (User u : users()) {
            out.put(u.id(), u);
        }
        return out;
    }

    /** Counted, as {@code ForecastRepository} counts, so the host's member lists agree with the module's. */
    private static boolean counted(User u) {
        return EffectiveRole.counted(u.role(), u.active());
    }

    /** Whether this user reports to somebody other than themselves, and so is somebody else's team member. */
    private static boolean reportsToAnother(User u) {
        return u.managerId() != null && !u.managerId().equals(u.id());
    }

    public List<Team> teams() {
        List<User> all = users();
        Map<UUID, Integer> reports = new HashMap<>();
        for (User u : all) {
            if (counted(u) && reportsToAnother(u)) {
                reports.merge(u.managerId(), 1, Integer::sum);
            }
        }
        List<Team> out = new ArrayList<>();
        for (User u : all) {
            // Only an effective TEAM_LEADER keys a team, and every one of them does: a leader nobody counted
            // reports to is already a member, and a skill team leader's "team" would be the leaders beneath
            // them, which ruling 3 says nobody runs.
            if ("TEAM_LEADER".equals(u.role())) {
                out.add(new Team(u.id(), u.fullName(), u.id(), u.managerId(),
                        reports.getOrDefault(u.id(), 0) + (counted(u) ? 1 : 0)));
            }
        }
        return out;
    }

    public Optional<Team> team(UUID id) {
        return teams().stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /**
     * Every counted user's place: their manager's team, and their own when they lead one. One read, not one
     * per team.
     */
    public List<Membership> memberships() {
        List<User> all = users();
        Set<UUID> teams = new HashSet<>();
        for (User u : all) {
            if ("TEAM_LEADER".equals(u.role())) {
                teams.add(u.id());
            }
        }
        List<Membership> out = new ArrayList<>();
        for (User u : all) {
            if (!counted(u)) {
                continue;
            }
            if (teams.contains(u.id())) {
                out.add(new Membership(u.id(), u.id()));
            }
            if (reportsToAnother(u) && teams.contains(u.managerId())) {
                out.add(new Membership(u.managerId(), u.id()));
            }
        }
        return out;
    }

    /** The leader, when they are counted themselves, and everyone who reports to them directly. */
    public List<UUID> membersOf(UUID teamId) {
        return users().stream()
                .filter(Directory::counted)
                .filter(u -> u.id().equals(teamId) || teamId.equals(u.managerId()))
                .map(User::id)
                .toList();
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
