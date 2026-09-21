package com.workloadhub.forecast.tools.prepare;

import com.workloadhub.forecast.tools.export.ExportEnvelope;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Rewrites a real WorkloadHub export so the forecast can count its people.
 *
 * <p>{@code ForecastRepository} counts a member when {@code users.active} is true and the role is MEMBER or
 * TEAM_LEADER. A real export straight out of the application fails the first while WorkloadHub is in testing:
 * almost nobody is active, and the owner has confirmed the flag carries no meaning there. This class corrects
 * the export file, in front of the seed, so that {@code SeedGenerator}'s real mode, {@code ExportImporter} and
 * {@code forecast-core} all stay untouched.
 *
 * <p>It does not touch {@code teams} or {@code team_members}. It used to derive both from {@code department}
 * and {@code manager_id}, because the forecast then required a team membership; it reads the hierarchy
 * directly now, so that derivation is gone and those tables are copied through verbatim (design 2026-09-21,
 * section 9, withdrawing sections 5 and 6 of the 2026-09-19 design).
 *
 * <p><b>Transitional.</b> Delete this class and the driver's {@code prepare} verb once WorkloadHub's own
 * {@code users.active} means what it says.
 */
public final class ExportPreparer {

    /** Roles this step never rewrites: {@code ProjectPlanner.fallbackOwner} looks for exactly these two. */
    private static final Set<String> FIXED_ROLES = Set.of("ADMIN", "CENTER_MANAGER");

    /** The prepared export and what the run did, for the driver to print. */
    public record Result(ExportEnvelope envelope, int usersActivated, int teamLeaders, int skillTeamLeaders) {
    }

    private ExportPreparer() {
    }

    public static Result prepare(ExportEnvelope input) {
        List<LinkedHashMap<String, Object>> inputUsers = input.rows("users");
        if (inputUsers.isEmpty()) {
            throw new IllegalArgumentException("the export carries no users; there is nothing to prepare");
        }

        // Only a manager who is themselves in the export: users.manager_id can name somebody outside it, and a
        // manager nobody can look up leads no team the forecast could run.
        Set<Object> ids = new HashSet<>();
        inputUsers.forEach(u -> ids.add(u.get("id")));
        Set<Object> managers = new HashSet<>();
        for (LinkedHashMap<String, Object> u : inputUsers) {
            Object manager = u.get("manager_id");
            if (manager != null && ids.contains(manager)) {
                managers.add(manager);
            }
        }
        // A skill team leader is the manager of a team leader (design 2026-09-21, ruling 3), so: a manager
        // whose own reports include another manager.
        Set<Object> managersOfManagers = new HashSet<>();
        for (LinkedHashMap<String, Object> u : inputUsers) {
            Object manager = u.get("manager_id");
            if (manager != null && managers.contains(u.get("id"))) {
                managersOfManagers.add(manager);
            }
        }

        int activated = 0;
        int teamLeaders = 0;
        int skillTeamLeaders = 0;
        List<LinkedHashMap<String, Object>> users = new ArrayList<>();
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
            String role = String.valueOf(row.get("role"));
            if (managers.contains(row.get("id")) && !FIXED_ROLES.contains(role)) {
                String wanted = managersOfManagers.contains(row.get("id")) ? "SKILL_TEAM_LEADER" : "TEAM_LEADER";
                if (!wanted.equals(role)) {
                    row.put("role", wanted);
                    if ("SKILL_TEAM_LEADER".equals(wanted)) {
                        skillTeamLeaders++;
                    } else {
                        teamLeaders++;
                    }
                }
            }
            users.add(row);
        }

        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>(input.data());
        data.put("users", users);
        return new Result(input.withData(data), activated, teamLeaders, skillTeamLeaders);
    }
}
