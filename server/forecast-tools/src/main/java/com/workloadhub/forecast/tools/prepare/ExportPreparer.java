package com.workloadhub.forecast.tools.prepare;

import com.workloadhub.forecast.data.EffectiveRole;
import com.workloadhub.forecast.tools.export.ExportEnvelope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rewrites a real WorkloadHub export so the forecast can count its people.
 *
 * <p>{@code ForecastRepository} counts a member when {@code users.active} is true and their effective role is
 * MEMBER or TEAM_LEADER. A real export straight out of the application fails the first while WorkloadHub is
 * in testing: almost nobody is active, and the owner has confirmed the flag carries no meaning there. This
 * class corrects the export file, in front of the seed, so that {@code SeedGenerator}'s real mode,
 * {@code ExportImporter} and {@code forecast-core} all stay untouched.
 *
 * <p>It no longer decides anyone's role. It used to promote managers from {@code manager_id}; the module now
 * derives the role from the job title itself ({@link EffectiveRole}), so this step only writes that same
 * answer back into {@code users.role} — which leaves the prepared file saying what the forecast will do with
 * it, instead of the MEMBER the application leaves behind. Running it twice changes nothing.
 *
 * <p>It does not touch {@code teams} or {@code team_members}: they hold project teams and are copied through
 * verbatim (design 2026-09-21, section 9, withdrawing sections 5 and 6 of the 2026-09-19 design).
 *
 * <p><b>Transitional.</b> Delete this class and the driver's {@code prepare} verb once WorkloadHub's own
 * {@code users.active} means what it says.
 */
public final class ExportPreparer {

    /**
     * The prepared export and what it holds afterwards, so the owner can see at a glance whether the file is
     * worth seeding. {@code teamLeaders} is also the number of teams: a leader nobody reports to is demoted
     * to a member, so every effective TEAM_LEADER keys exactly one team.
     *
     * <p>{@code countedMembers} and {@code teamMembers} are not the same number, and the difference is the
     * point. A run is per team, so only somebody inside a team is ever forecast: {@code teamMembers} counts
     * the leaders and the people who report to them, while {@code countedMembers} counts everybody the role
     * rule would allow — including those whose manager is an actor, or who have no manager at all. On the
     * owner's directory that gap is 79 people of 258, and reporting only the larger figure would say the
     * forecast covers everyone when it covers 179.
     */
    public record Result(ExportEnvelope envelope, int usersActivated, int countedMembers, int teamMembers,
            int teamLeaders, int skillTeamLeaders) {

        /** Counted by the role rule but inside no team, so never forecast. */
        public int inNoTeam() {
            return countedMembers - teamMembers;
        }
    }

    private ExportPreparer() {
    }

    public static Result prepare(ExportEnvelope input) {
        List<LinkedHashMap<String, Object>> inputUsers = input.rows("users");
        if (inputUsers.isEmpty()) {
            throw new IllegalArgumentException("the export carries no users; there is nothing to prepare");
        }

        // Activation comes first: a user the export left inactive would be demoted out of every count below,
        // and the whole point of this step is that the flag carries no meaning yet.
        int activated = 0;
        List<LinkedHashMap<String, Object>> users = new ArrayList<>();
        List<EffectiveRole.Candidate> candidates = new ArrayList<>();
        for (LinkedHashMap<String, Object> u : inputUsers) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>(u);
            if (!Boolean.TRUE.equals(row.get("active")) || row.get("deactivated_at") != null) {
                activated++;
            }
            row.put("active", true);
            // Not tidiness: ForecastRepository reads deactivated_at as the member's leaving date, so a user
            // flipped active while still carrying one is counted and then dropped by the run — FeatureBuilder
            // stops their rows at that date and ForecastRunner.forTeam filters them out, raising
            // TEAM_NOT_FOUND for a team of them.
            row.put("deactivated_at", null);
            users.add(row);
            candidates.add(new EffectiveRole.Candidate(id(row.get("id")), id(row.get("manager_id")),
                    str(row.get("role")), str(row.get("job_title")), true));
        }

        Map<UUID, String> roles = EffectiveRole.resolve(candidates);
        int counted = 0;
        int inTeam = 0;
        int teamLeaders = 0;
        int skillTeamLeaders = 0;
        for (LinkedHashMap<String, Object> row : users) {
            UUID id = id(row.get("id"));
            String role = roles.get(id);
            row.put("role", role);
            if (EffectiveRole.counted(role, true)) {
                counted++;
                // Inside a team: they lead one, or they report to somebody who does.
                UUID manager = id(row.get("manager_id"));
                if ("TEAM_LEADER".equals(role)
                        || (manager != null && !manager.equals(id) && "TEAM_LEADER".equals(roles.get(manager)))) {
                    inTeam++;
                }
            }
            if ("SKILL_TEAM_LEADER".equals(role)) {
                skillTeamLeaders++;
            } else if ("TEAM_LEADER".equals(role)) {
                teamLeaders++;
            }
        }

        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>(input.data());
        data.put("users", users);
        return new Result(input.withData(data), activated, counted, inTeam, teamLeaders, skillTeamLeaders);
    }

    /** A user id straight out of the export's JSON. A manager outside the export is simply absent. */
    private static UUID id(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the export carries a user id that is not a uuid: " + value, e);
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
