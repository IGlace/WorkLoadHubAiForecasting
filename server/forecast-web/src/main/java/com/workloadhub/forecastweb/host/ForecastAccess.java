package com.workloadhub.forecastweb.host;

import com.workloadhub.forecast.data.EffectiveRole;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The v1 role rules (design 2026-09-11, section 3.2), read from `users` alone: what the real host enforces
 * before calling the module. ADMIN runs and views any team; a SKILL_TEAM_LEADER runs and views the team of a
 * leader who reports to them, one at a time; a TEAM_LEADER runs and views their own team; members view the team
 * they belong to; a VIEWER views any team and runs none. A CENTER_MANAGER runs and views any team: the owner
 * ruled on 2026-09-21 that they, like an ADMIN, act for the whole organisation and lead no team of their own.
 * Every answer carries its reason, so a page can say why a button is disabled.
 *
 * <p>A team is a leader and their direct reports, keyed by the leader's user id (design 2026-09-21), so `teams`
 * and `team_members` are not consulted: they hold project teams, which grant nobody anything. The role is the
 * effective one — what the job title says, not what `users.role` holds (see {@link EffectiveRole}).
 */
public final class ForecastAccess {

    /** Allowed or not, and why, in words a page can show. */
    public record Decision(boolean allowed, String reason) {
    }

    private final JdbcClient jdbc;

    public ForecastAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The user's effective role. Their own row is not enough, because a leader nobody counted reports to is a
     * member, so their direct reports are read with them; nothing further out can change the answer, because
     * whether a report is counted depends on their own job title alone.
     */
    public String roleOf(UUID userId) {
        return roleOf(userId, null);
    }

    public String roleOf(UUID userId, Directory.Snapshot directory) {
        String role = directory != null ? directory.roleOf(userId) : effectiveRole(userId);
        if (role == null) {
            throw new HostForbidden("unknown user " + userId);
        }
        return role;
    }

    /** The same answer, or null for a user who is not in the directory. */
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
        return run(userId, teamId).allowed();
    }

    public boolean canView(UUID userId, UUID teamId) {
        return view(userId, teamId).allowed();
    }

    /** The same answers from a directory already resolved, without a further query. See {@link #run}. */
    public boolean canRun(UUID userId, UUID teamId, Directory.Snapshot directory) {
        return run(userId, teamId, directory).allowed();
    }

    public boolean canView(UUID userId, UUID teamId, Directory.Snapshot directory) {
        return view(userId, teamId, directory).allowed();
    }

    public Decision run(UUID userId, UUID teamId) {
        return run(userId, teamId, null);
    }

    /**
     * Whether this user may run this team. {@code directory}, when given, is a directory already resolved by
     * the caller and every question is answered from it: a page asking about every team otherwise re-queries
     * the acting user's role twice per team and asks again whether each team exists.
     */
    public Decision run(UUID userId, UUID teamId, Directory.Snapshot directory) {
        String role = roleOf(userId, directory);
        return switch (role) {
            case "ADMIN", "CENTER_MANAGER" -> new Decision(true, role + " may run a forecast for any team");
            case "TEAM_LEADER" -> manages(userId, teamId, directory)
                    ? new Decision(true, "TEAM_LEADER manages this team")
                    : new Decision(false, "TEAM_LEADER may only run their own team");
            case "SKILL_TEAM_LEADER" -> managesParentOf(userId, teamId, directory)
                    ? new Decision(true, "SKILL_TEAM_LEADER manages this team's leader")
                    : new Decision(false, "SKILL_TEAM_LEADER may only run the team of a leader who reports to them");
            default -> new Decision(false, role + " may not run a forecast");
        };
    }

    public Decision view(UUID userId, UUID teamId) {
        return view(userId, teamId, null);
    }

    public Decision view(UUID userId, UUID teamId, Directory.Snapshot directory) {
        String role = roleOf(userId, directory);
        return switch (role) {
            case "ADMIN", "VIEWER", "CENTER_MANAGER" -> new Decision(true, role + " may view any team");
            case "TEAM_LEADER" -> manages(userId, teamId, directory) ? new Decision(true, "TEAM_LEADER manages this team")
                    : memberOf(userId, teamId, directory) ? new Decision(true, "a member of this team")
                    : new Decision(false, "TEAM_LEADER may only view their own team or the one they belong to");
            case "SKILL_TEAM_LEADER" -> managesParentOf(userId, teamId, directory) ? new Decision(true, "SKILL_TEAM_LEADER manages this team's leader")
                    : memberOf(userId, teamId, directory) ? new Decision(true, "a member of this team")
                    : new Decision(false, "SKILL_TEAM_LEADER may only view the team of a leader who reports to them, or their own");
            case "MEMBER" -> memberOf(userId, teamId, directory) ? new Decision(true, "a member of this team")
                    : new Decision(false, "MEMBER may only view the teams they belong to");
            default -> new Decision(false, role + " may not view a forecast");
        };
    }

    /** A leader manages exactly one team: their own, keyed by their own id. */
    boolean manages(UUID userId, UUID teamId, Directory.Snapshot directory) {
        return userId.equals(teamId) && leadsSomeone(teamId, directory);
    }

    /** A skill team leader may act for one leader beneath them at a time, never for everyone below at once. */
    boolean managesParentOf(UUID userId, UUID teamId, Directory.Snapshot directory) {
        return leadsSomeone(teamId, directory) && userId.equals(managerOf(teamId, directory));
    }

    /**
     * The leader belongs to their own team, and so does every <b>counted</b> person who reports to them. The
     * counted test is not decoration: {@code Directory.membersOf} and {@code memberships} both apply it, and
     * without it somebody who has left, or a skill team leader reporting to a leader, is told they are "a
     * member of this team" while appearing in no member list and never being forecast.
     */
    boolean memberOf(UUID userId, UUID teamId, Directory.Snapshot directory) {
        if (!leadsSomeone(teamId, directory)) {
            return false;
        }
        if (userId.equals(teamId)) {
            return true;
        }
        Directory.Snapshot d = directory == null ? new Directory(jdbc).snapshot() : directory;
        return d.counted(userId) && teamId.equals(managerOf(userId, d));
    }

    /** The user's manager, from the directory. */
    private UUID managerOf(UUID userId, Directory.Snapshot directory) {
        Directory.Snapshot d = directory == null ? new Directory(jdbc).snapshot() : directory;
        return d.users().stream().filter(u -> u.id().equals(userId)).findFirst()
                .map(Directory.User::managerId).orElse(null);
    }

    /**
     * Whether a team exists at all. An effective TEAM_LEADER is exactly that: their job title says they lead
     * and somebody counted reports to them, since a leader nobody reports to is demoted to a member. The role
     * matters — a SKILL_TEAM_LEADER has reports too, and their "team" would be the leaders beneath them,
     * which nobody may run (design 2026-09-21, rulings 1 and 3).
     */
    private boolean leadsSomeone(UUID teamId, Directory.Snapshot directory) {
        // Not roleOf: a team id naming nobody is simply not a team, which is an answer, not a refusal.
        return "TEAM_LEADER".equals(directory != null ? directory.roleOf(teamId) : effectiveRole(teamId));
    }
}
