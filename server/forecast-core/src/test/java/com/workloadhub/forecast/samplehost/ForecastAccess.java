package com.workloadhub.forecast.samplehost;

import com.workloadhub.forecast.store.Dialect;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The v1 role rules (design 2026-09-11, section 3.2), read from the WorkloadHub tables: what the real host enforces
 * before calling the module. ADMIN runs and views any team; a SKILL_TEAM_LEADER runs and views the teams whose
 * parent team they manage; a TEAM_LEADER runs and views the teams they manage; members view the teams they belong
 * to; VIEWER and CENTER_MANAGER view any team and run none.
 */
public final class ForecastAccess {

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public ForecastAccess(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    public String roleOf(UUID userId) {
        return jdbc.sql("SELECT role FROM users WHERE id = " + dialect.placeholder("uuid")).param(userId.toString()).query().listOfRows().stream()
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

    boolean manages(UUID userId, UUID teamId) {
        return !jdbc.sql("SELECT id FROM teams WHERE id = " + dialect.placeholder("uuid") + " AND manager_id = " + dialect.placeholder("uuid"))
                .param(teamId.toString()).param(userId.toString()).query().listOfRows().isEmpty();
    }

    boolean managesParentOf(UUID userId, UUID teamId) {
        return !jdbc.sql("SELECT t.id FROM teams t JOIN teams p ON p.id = t.parent_team_id WHERE t.id = " + dialect.placeholder("uuid")
                + " AND p.manager_id = " + dialect.placeholder("uuid")).param(teamId.toString()).param(userId.toString()).query().listOfRows().isEmpty();
    }

    boolean memberOf(UUID userId, UUID teamId) {
        return !jdbc.sql("SELECT team_id FROM team_members WHERE team_id = " + dialect.placeholder("uuid") + " AND user_id = " + dialect.placeholder("uuid"))
                .param(teamId.toString()).param(userId.toString()).query().listOfRows().isEmpty();
    }
}
