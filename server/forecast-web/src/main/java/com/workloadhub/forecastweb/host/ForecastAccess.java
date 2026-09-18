package com.workloadhub.forecastweb.host;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The v1 role rules (design 2026-09-11, section 3.2), read from the WorkloadHub tables: what the real host enforces
 * before calling the module. ADMIN runs and views any team; a SKILL_TEAM_LEADER runs and views the teams whose
 * parent team they manage; a TEAM_LEADER runs and views the teams they manage; members view the teams they belong
 * to; VIEWER and CENTER_MANAGER view any team and run none. Every answer carries its reason, so a page can say why
 * a button is disabled.
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
                    : new Decision(false, "TEAM_LEADER may only run the teams they manage");
            case "SKILL_TEAM_LEADER" -> managesParentOf(userId, teamId)
                    ? new Decision(true, "SKILL_TEAM_LEADER manages this team's parent team")
                    : new Decision(false, "SKILL_TEAM_LEADER may only run the teams under the team they manage");
            default -> new Decision(false, role + " may not run a forecast");
        };
    }

    public Decision view(UUID userId, UUID teamId) {
        String role = roleOf(userId);
        return switch (role) {
            case "ADMIN", "VIEWER", "CENTER_MANAGER" -> new Decision(true, role + " may view any team");
            case "TEAM_LEADER" -> manages(userId, teamId) ? new Decision(true, "TEAM_LEADER manages this team")
                    : memberOf(userId, teamId) ? new Decision(true, "a member of this team")
                    : new Decision(false, "TEAM_LEADER may only view the teams they manage or belong to");
            case "SKILL_TEAM_LEADER" -> managesParentOf(userId, teamId) ? new Decision(true, "SKILL_TEAM_LEADER manages this team's parent team")
                    : memberOf(userId, teamId) ? new Decision(true, "a member of this team")
                    : new Decision(false, "SKILL_TEAM_LEADER may only view the teams under the team they manage, or their own");
            case "MEMBER" -> memberOf(userId, teamId) ? new Decision(true, "a member of this team")
                    : new Decision(false, "MEMBER may only view the teams they belong to");
            default -> new Decision(false, role + " may not view a forecast");
        };
    }

    boolean manages(UUID userId, UUID teamId) {
        return !jdbc.sql("SELECT id FROM teams WHERE id = ? AND manager_id = ?")
                .param(teamId).param(userId).query().listOfRows().isEmpty();
    }

    boolean managesParentOf(UUID userId, UUID teamId) {
        return !jdbc.sql("SELECT t.id FROM teams t JOIN teams p ON p.id = t.parent_team_id WHERE t.id = ?"
                + " AND p.manager_id = ?").param(teamId).param(userId).query().listOfRows().isEmpty();
    }

    boolean memberOf(UUID userId, UUID teamId) {
        return !jdbc.sql("SELECT team_id FROM team_members WHERE team_id = ? AND user_id = ?")
                .param(teamId).param(userId).query().listOfRows().isEmpty();
    }
}
