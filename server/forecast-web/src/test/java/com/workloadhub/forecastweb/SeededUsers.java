package com.workloadhub.forecastweb;

import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecastweb.host.ActingUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Users and the teams the company structure makes of them, read straight from the seeded `users` table: the
 * core's loaded data holds the counted members only. A team is a leader and their direct reports, keyed by
 * the leader (design 2026-09-21), so `teams` and `team_members` are never read here either.
 */
public final class SeededUsers {

    /** {@code id} and {@code managerId} are both the leader; {@code parentId} is the leader's own manager. */
    public record Team(UUID id, String name, UUID managerId, UUID parentId) {
    }

    private SeededUsers() {
    }

    static JdbcClient jdbc() {
        return JdbcClient.create(SeededData.dataSource());
    }

    public static List<ActingUser> users() {
        return jdbc().sql("SELECT id, full_name, role, job_title FROM users ORDER BY full_name").query().listOfRows().stream()
                .map(r -> new ActingUser(UUID.fromString(str(r, "id")), str(r, "full_name"), str(r, "role"), str(r, "job_title"))).toList();
    }

    public static ActingUser withRole(String role) {
        return users().stream().filter(u -> u.role().equals(role)).findFirst().orElseThrow(() -> new AssertionError("no " + role + " in the seed"));
    }

    /** Counted, as {@code ForecastRepository} counts: the host's lists must agree with the module's. */
    private static final String COUNTED = " active = TRUE AND role IN ('MEMBER', 'TEAM_LEADER')";

    /** Every team of the seed: one per TEAM_LEADER somebody counted reports to, keyed by that leader. */
    public static List<Team> teams() {
        String sql = "SELECT m.id, m.full_name, m.manager_id FROM users m"
                + " WHERE m.role = 'TEAM_LEADER'"
                + " AND EXISTS (SELECT 1 FROM users r WHERE r.manager_id = m.id AND" + COUNTED + ")"
                + " ORDER BY m.full_name";
        return jdbc().sql(sql).query().listOfRows().stream()
                .map(r -> new Team(UUID.fromString(str(r, "id")), str(r, "full_name"),
                        UUID.fromString(str(r, "id")), uuid(r, "manager_id")))
                .toList();
    }

    /** The team this user leads, which is keyed by their own id. */
    public static Team managedBy(UUID userId) {
        return teams().stream().filter(t -> userId.equals(t.id())).findFirst()
                .orElseThrow(() -> new AssertionError(userId + " leads no team"));
    }


    /** Whether any team's leader reports to this user: a skill team leader stands above at least one team. */
    public static boolean leadsLeaders(UUID userId) {
        return !childrenOf(userId).isEmpty();
    }

    /** The teams whose leader reports to this one: what a skill team leader may run, one at a time. */
    public static List<Team> childrenOf(UUID teamId) {
        return teams().stream().filter(t -> teamId.equals(t.parentId())).toList();
    }

    /** The leader, when counted themselves, and everyone who reports to them directly. */
    public static List<UUID> membersOf(UUID teamId) {
        return jdbc().sql("SELECT id FROM users WHERE (id = ? OR manager_id = ?) AND" + COUNTED + " ORDER BY full_name")
                .param(teamId).param(teamId).query().listOfRows().stream()
                .map(r -> UUID.fromString(str(r, "id"))).toList();
    }

    /** The teams this user belongs to: their manager's, and their own when they lead one. */
    public static List<UUID> teamsOf(UUID userId) {
        return teams().stream().filter(t -> membersOf(t.id()).contains(userId)).map(Team::id).toList();
    }

    /** A team the user neither leads, belongs to, nor stands above. */
    public static Team notInvolving(UUID userId) {
        List<UUID> mine = teamsOf(userId);
        return teams().stream()
                .filter(t -> !userId.equals(t.id()) && !mine.contains(t.id()) && !userId.equals(t.parentId()))
                .findFirst().orElseThrow();
    }

    private static String str(Map<String, Object> r, String k) {
        Object o = r.get(k);
        return o == null ? null : String.valueOf(o);
    }

    private static UUID uuid(Map<String, Object> r, String k) {
        String s = str(r, k);
        return s == null ? null : UUID.fromString(s);
    }
}
