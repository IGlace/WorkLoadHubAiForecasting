package com.workloadhub.forecast.samplehost;

import com.workloadhub.forecast.data.EffectiveRole;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The v1 role rules (design 2026-09-11, section 3.2), read from `users` alone: what the real host enforces
 * before calling the module. ADMIN runs and views any team; a SKILL_TEAM_LEADER runs and views the team of a
 * leader who reports to them, one at a time; a TEAM_LEADER runs and views their own team; members view the team
 * they belong to; a VIEWER views any team and runs none. A CENTER_MANAGER, like an ADMIN, runs and views any
 * team: they act for the whole organisation and lead no team of their own (owner, 2026-09-21).
 *
 * <p>A team is a leader and their direct reports, keyed by the leader's user id (design 2026-09-21), so
 * `teams` and `team_members` are not consulted: they hold project teams, which grant nobody anything. The
 * role is the effective one — what the job title says, not what `users.role` holds.
 */
public final class ForecastAccess {

    private final JdbcClient jdbc;

    public ForecastAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String roleOf(UUID userId) {
        String role = effectiveRole(userId);
        if (role == null) {
            throw new HostForbidden("unknown user " + userId);
        }
        return role;
    }

    /**
     * The effective role, or null for a user who is not in the directory. Their own row is not enough: a
     * leader nobody counted reports to is a member, so their direct reports are read with them.
     */
    private String effectiveRole(UUID userId) {
        List<EffectiveRole.Candidate> rows = jdbc
                .sql("SELECT id, manager_id, role, job_title, active FROM users WHERE id = ? OR manager_id = ?")
                .param(userId).param(userId)
                .query((rs, i) -> new EffectiveRole.Candidate(rs.getObject("id", UUID.class),
                        rs.getObject("manager_id", UUID.class), rs.getString("role"), rs.getString("job_title"),
                        rs.getBoolean("active")))
                .list();
        return EffectiveRole.resolve(rows).get(userId);
    }

    public boolean canRun(UUID userId, UUID teamId) {
        return switch (roleOf(userId)) {
            case "ADMIN", "CENTER_MANAGER" -> true;
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
     * Whether a team exists at all. An effective TEAM_LEADER is exactly that: their title says they lead and
     * somebody counted reports to them, since a leader nobody reports to is demoted to a member. The role
     * matters — a SKILL_TEAM_LEADER has reports too, and their "team" would be the leaders beneath them,
     * which nobody may run (design 2026-09-21, rulings 1 and 3).
     */
    private boolean leadsSomeone(UUID teamId) {
        return "TEAM_LEADER".equals(effectiveRole(teamId));
    }
}
