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
 * <p>Every question here is answered from one read. {@link #snapshot()} takes it and hands back a view that
 * answers all of them; the convenience methods each take their own, so a caller that asks several questions
 * -- a controller building a page -- must take one snapshot and keep it. Before that, one team page of the
 * owner's directory read and classified its 264 users five times, and each poll of a run's progress paid for
 * another, purely to resolve the acting user.
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

    /**
     * {@code id} is the leader's user id, and so is {@code managerId}: a team is its leader.
     *
     * <p>{@code reportsToId} is that leader's own manager, and {@code reportsToName} their name. It is a
     * <b>user</b> id, not a team id, and usually names somebody who keys no team at all — a team leader
     * reports to a skill team leader, who leads team leaders and has no team of their own. It was called
     * {@code parentTeamId} and read as a team id, which is why every real team's page said its leader
     * reported to nobody.
     */
    public record Team(UUID id, String name, UUID managerId, UUID reportsToId, String reportsToName, int memberCount) {
    }

    public record Membership(UUID teamId, UUID userId) {
    }

    private final JdbcClient jdbc;
    private int reads;

    public Directory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** How many times the directory has been read. The guard on the one-read-per-question rule. */
    public int reads() {
        return reads;
    }

    /**
     * The directory as of now, resolved once. Every question below is answered from it without touching the
     * database again, so a caller with several questions takes one of these and keeps it.
     */
    public Snapshot snapshot() {
        List<User> raw = new ArrayList<>();
        List<EffectiveRole.Candidate> candidates = new ArrayList<>();
        reads++;
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
        return new Snapshot(List.copyOf(out), Map.copyOf(roles));
    }

    /** Every user with their effective role, by name, from one read. */
    public List<User> users() {
        return snapshot().users();
    }

    public Optional<ActingUser> user(UUID id) {
        return snapshot().actingUser(id);
    }

    public Map<UUID, User> usersById() {
        return snapshot().usersById();
    }

    public List<Team> teams() {
        return snapshot().teams();
    }

    public Optional<Team> team(UUID id) {
        return snapshot().team(id);
    }

    public List<Membership> memberships() {
        return snapshot().memberships();
    }

    public List<UUID> membersOf(UUID teamId) {
        return snapshot().membersOf(teamId);
    }

    /** The directory resolved once: the answers, without the database. */
    public static final class Snapshot {

        private final List<User> users;
        private final Map<UUID, String> roles;

        private Snapshot(List<User> users, Map<UUID, String> roles) {
            this.users = users;
            this.roles = roles;
        }

        public List<User> users() {
            return users;
        }

        /** The effective role of a user, or null for somebody who is not in the directory. */
        public String roleOf(UUID id) {
            return roles.get(id);
        }

        public Optional<ActingUser> actingUser(UUID id) {
            return users.stream().filter(u -> u.id().equals(id)).findFirst()
                    .map(u -> new ActingUser(u.id(), u.fullName(), u.role(), u.jobTitle()));
        }

        public Map<UUID, User> usersById() {
            Map<UUID, User> out = new LinkedHashMap<>();
            for (User u : users) {
                out.put(u.id(), u);
            }
            return out;
        }

        /** Counted, as {@code ForecastRepository} counts, so the host's member lists agree with the module's. */
        public boolean counted(User u) {
            return EffectiveRole.counted(u.role(), u.active());
        }

        /** Counted, by id: a user who is not in the directory is not counted. */
        public boolean counted(UUID id) {
            return users.stream().filter(u -> u.id().equals(id)).findFirst().map(this::counted).orElse(false);
        }

        /** Whether this user leads a team of their own — the only thing that makes a team id a team. */
        public boolean leads(UUID id) {
            return "TEAM_LEADER".equals(roles.get(id));
        }

        public List<Team> teams() {
            Map<UUID, Integer> reports = new HashMap<>();
            Map<UUID, String> names = new HashMap<>();
            for (User u : users) {
                names.put(u.id(), u.fullName());
                if (counted(u) && reportsToAnother(u)) {
                    reports.merge(u.managerId(), 1, Integer::sum);
                }
            }
            List<Team> out = new ArrayList<>();
            for (User u : users) {
                // Only an effective TEAM_LEADER keys a team, and every one of them does: a leader nobody
                // counted reports to is already a member, and a skill team leader's "team" would be the
                // leaders beneath them, which ruling 3 says nobody runs.
                if (leads(u.id())) {
                    out.add(new Team(u.id(), u.fullName(), u.id(), u.managerId(), names.get(u.managerId()),
                            reports.getOrDefault(u.id(), 0) + (counted(u) ? 1 : 0)));
                }
            }
            return out;
        }

        public Optional<Team> team(UUID id) {
            return teams().stream().filter(t -> t.id().equals(id)).findFirst();
        }

        /**
         * Every counted user's place: their manager's team, and their own when they lead one.
         */
        public List<Membership> memberships() {
            Set<UUID> teams = new HashSet<>();
            for (User u : users) {
                if (leads(u.id())) {
                    teams.add(u.id());
                }
            }
            List<Membership> out = new ArrayList<>();
            for (User u : users) {
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

        /** The leader, when they are counted themselves, and every counted person who reports to them. */
        public List<UUID> membersOf(UUID teamId) {
            return users.stream()
                    .filter(this::counted)
                    .filter(u -> u.id().equals(teamId) || teamId.equals(u.managerId()))
                    .map(User::id)
                    .toList();
        }

        /** Whether this user reports to somebody other than themselves, and so is somebody else's member. */
        private static boolean reportsToAnother(User u) {
            return u.managerId() != null && !u.managerId().equals(u.id());
        }
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
