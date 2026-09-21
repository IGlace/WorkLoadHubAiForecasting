package com.workloadhub.forecastweb.host;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The v1 role rules (design 2026-09-11, section 3.2), read from `users` alone: what the real host enforces
 * before calling the module. ADMIN runs and views any team; a SKILL_TEAM_LEADER runs and views the team of a
 * leader who reports to them, one at a time; a TEAM_LEADER runs and views their own team; members view the team
 * they belong to; VIEWER and CENTER_MANAGER view any team and run none. Every answer carries its reason, so a
 * page can say why a button is disabled.
 *
 * <p>A team is a leader and their direct reports, keyed by the leader's user id (design 2026-09-21), so `teams`
 * and `team_members` are not consulted: they hold project teams, which grant nobody anything.
 */
public final class ForecastAccess {

    /** Allowed or not, and why, in words a page can show. */
    public record Decision(boolean allowed, String reason) {
    }

    private final JdbcClient jdbc;

    public ForecastAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String roleOf(UUID userId) {
        return jdbc.sql("SELECT role FROM users WHERE id = ?").param(userId).query().listOfRows().stream()
                .findFirst().map(r -> String.valueOf(r.get("role"))).orElseThrow(() -> new HostForbidden("unknown user " + userId));
    }

    public boolean canRun(UUID userId, UUID teamId) {
        return run(userId, teamId).allowed();
    }

    public boolean canView(UUID userId, UUID teamId) {
        return view(userId, teamId).allowed();
    }

    public Decision run(UUID userId, UUID teamId) {
        String role = roleOf(userId);
        return switch (role) {
            case "ADMIN" -> new Decision(true, "ADMIN may run a forecast for any team");
            case "TEAM_LEADER" -> manages(userId, teamId)
                    ? new Decision(true, "TEAM_LEADER manages this team")
                    : new Decision(false, "TEAM_LEADER may only run their own team");
            case "SKILL_TEAM_LEADER" -> managesParentOf(userId, teamId)
                    ? new Decision(true, "SKILL_TEAM_LEADER manages this team's leader")
                    : new Decision(false, "SKILL_TEAM_LEADER may only run the team of a leader who reports to them");
            default -> new Decision(false, role + " may not run a forecast");
        };
    }

    public Decision view(UUID userId, UUID teamId) {
        String role = roleOf(userId);
        return switch (role) {
            case "ADMIN", "VIEWER", "CENTER_MANAGER" -> new Decision(true, role + " may view any team");
            case "TEAM_LEADER" -> manages(userId, teamId) ? new Decision(true, "TEAM_LEADER manages this team")
                    : memberOf(userId, teamId) ? new Decision(true, "a member of this team")
                    : new Decision(false, "TEAM_LEADER may only view their own team or the one they belong to");
            case "SKILL_TEAM_LEADER" -> managesParentOf(userId, teamId) ? new Decision(true, "SKILL_TEAM_LEADER manages this team's leader")
                    : memberOf(userId, teamId) ? new Decision(true, "a member of this team")
                    : new Decision(false, "SKILL_TEAM_LEADER may only view the team of a leader who reports to them, or their own");
            case "MEMBER" -> memberOf(userId, teamId) ? new Decision(true, "a member of this team")
                    : new Decision(false, "MEMBER may only view the teams they belong to");
            default -> new Decision(false, role + " may not view a forecast");
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

    /** Whether a team exists at all: somebody counted reports to this user. */
    private boolean leadsSomeone(UUID teamId) {
        return !jdbc.sql("SELECT 1 FROM users WHERE manager_id = ? AND active = TRUE AND role IN ('MEMBER', 'TEAM_LEADER')")
                .param(teamId).query().listOfRows().isEmpty();
    }
}
