package com.workloadhub.forecast.samplehost;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The v1 role rules (design 2026-09-11, section 3.2), read from `users` alone: what the real host enforces
 * before calling the module. ADMIN runs and views any team; a SKILL_TEAM_LEADER runs and views the team of a
 * leader who reports to them, one at a time; a TEAM_LEADER runs and views their own team; members view the team
 * they belong to; VIEWER and CENTER_MANAGER view any team and run none.
 *
 * <p>A team is a leader and their direct reports, keyed by the leader's user id (design 2026-09-21), so
 * `teams` and `team_members` are not consulted: they hold project teams, which grant nobody anything.
 */
public final class ForecastAccess {

    private final JdbcClient jdbc;

    public ForecastAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String roleOf(UUID userId) {
        return jdbc.sql("SELECT role FROM users WHERE id = ?").param(userId).query().listOfRows().stream()
                .findFirst().map(r -> String.valueOf(r.get("role"))).orElseThrow(() -> new HostForbidden("unknown user " + userId));
    }

    public boolean canRun(UUID userId, UUID teamId) {
        return switch (roleOf(userId)) {
            case "ADMIN" -> true;
            case "TEAM_LEADER" -> manages(userId, teamId);
            case "SKILL_TEAM_LEADER" -> managesParentOf(userId, teamId);
            default -> false;
        };
    }

    public boolean canView(UUID userId, UUID teamId) {
        return switch (roleOf(userId)) {
            case "ADMIN", "VIEWER", "CENTER_MANAGER" -> true;
            case "TEAM_LEADER" -> manages(userId, teamId) || memberOf(userId, teamId);
            case "SKILL_TEAM_LEADER" -> managesParentOf(userId, teamId) || memberOf(userId, teamId);
            case "MEMBER" -> memberOf(userId, teamId);
            default -> false;
        };
    }

    /** A leader manages exactly one team: their own, keyed by their own id. */
    boolean manages(UUID userId, UUID teamId) {
        return userId.equals(teamId) && leadsSomeone(teamId);
    }

    /** A skill team leader may act for one leader beneath them at a time, never for everyone below at once. */
    boolean managesParentOf(UUID userId, UUID teamId) {
        return leadsSomeone(teamId) && !jdbc.sql("SELECT 1 FROM users WHERE id = ? AND manager_id = ?")
                .param(teamId).param(userId).query().listOfRows().isEmpty();
    }

    /** The leader belongs to their own team, and so does everyone who reports to them. */
    boolean memberOf(UUID userId, UUID teamId) {
        if (!leadsSomeone(teamId)) {
            return false;
        }
        return userId.equals(teamId) || !jdbc.sql("SELECT 1 FROM users WHERE id = ? AND manager_id = ?")
                .param(userId).param(teamId).query().listOfRows().isEmpty();
    }

    /**
     * Whether a team exists at all: this user is a TEAM_LEADER and somebody counted reports to them. The role
     * matters — a SKILL_TEAM_LEADER has reports too, and their "team" would be the leaders beneath them,
     * which nobody may run (design 2026-09-21, rulings 1 and 3).
     */
    private boolean leadsSomeone(UUID teamId) {
        return !jdbc.sql("SELECT 1 FROM users lead JOIN users r ON r.manager_id = lead.id"
                        + " WHERE lead.id = ? AND lead.role = 'TEAM_LEADER'"
                        + " AND r.active = TRUE AND r.role IN ('MEMBER', 'TEAM_LEADER')")
                .param(teamId).query().listOfRows().isEmpty();
    }
}
