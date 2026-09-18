package com.workloadhub.forecastweb;

import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecastweb.host.ActingUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Users, teams and memberships read straight from the seeded tables: the core's loaded data holds the counted members only. */
public final class SeededUsers {

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

    public static List<Team> teams() {
        return jdbc().sql("SELECT id, name, manager_id, parent_team_id FROM teams ORDER BY name").query().listOfRows().stream()
                .map(r -> new Team(UUID.fromString(str(r, "id")), str(r, "name"), uuid(r, "manager_id"), uuid(r, "parent_team_id"))).toList();
    }

    public static Team managedBy(UUID userId) {
        return teams().stream().filter(t -> userId.equals(t.managerId())).findFirst().orElseThrow(() -> new AssertionError(userId + " manages no team"));
    }

    /** The department team a skill team leader heads: managed by them, no parent, with child teams under it. */
    public static Team departmentOf(UUID userId) {
        return teams().stream().filter(t -> userId.equals(t.managerId()) && t.parentId() == null && !childrenOf(t.id()).isEmpty()).findFirst()
                .orElseThrow(() -> new AssertionError(userId + " heads no department team with children"));
    }

    public static boolean headsADepartment(UUID userId) {
        return teams().stream().anyMatch(t -> userId.equals(t.managerId()) && t.parentId() == null && !childrenOf(t.id()).isEmpty());
    }

    public static List<Team> childrenOf(UUID teamId) {
        return teams().stream().filter(t -> teamId.equals(t.parentId())).toList();
    }

    public static List<UUID> membersOf(UUID teamId) {
        return jdbc().sql("SELECT user_id FROM team_members WHERE team_id = ?").param(teamId.toString()).query().listOfRows().stream()
                .map(r -> UUID.fromString(str(r, "user_id"))).toList();
    }

    public static List<UUID> teamsOf(UUID userId) {
        return jdbc().sql("SELECT team_id FROM team_members WHERE user_id = ?").param(userId.toString()).query().listOfRows().stream()
                .map(r -> UUID.fromString(str(r, "team_id"))).toList();
    }

    /** A team the user neither manages, belongs to, nor manages the parent of. */
    public static Team notInvolving(UUID userId) {
        List<UUID> mine = teamsOf(userId);
        List<Team> all = teams();
        return all.stream().filter(t -> !userId.equals(t.managerId()) && !mine.contains(t.id()))
                .filter(t -> t.parentId() == null || all.stream().filter(p -> p.id().equals(t.parentId())).noneMatch(p -> userId.equals(p.managerId())))
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
