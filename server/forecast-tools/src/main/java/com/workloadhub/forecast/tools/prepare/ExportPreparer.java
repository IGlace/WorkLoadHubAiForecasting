package com.workloadhub.forecast.tools.prepare;

import com.workloadhub.forecast.tools.export.ExportEnvelope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Rewrites a real WorkloadHub export so the forecast can count its people.
 *
 * <p>{@code ForecastRepository} counts a member only when {@code users.active} is true, the role is MEMBER or
 * TEAM_LEADER, and there is at least one {@code team_members} row. A real export straight out of the application
 * fails the first and the third while WorkloadHub is in testing: almost nobody is active, and its teams screen
 * has not been used. This class corrects the export file, in front of the seed, so that
 * {@code SeedGenerator}'s real mode, {@code ExportImporter} and {@code forecast-core} all stay untouched.
 *
 * <p><b>Transitional.</b> The owner has confirmed WorkloadHub's teams will be populated for real. On that day
 * this class and the driver's {@code prepare} verb are deleted, and nothing else moves — which is exactly why
 * the derivation does not live inside {@code SeedGenerator}, where it would silently overwrite the company's
 * own structure on every run. Design: {@code docs/superpowers/specs/2026-09-19-real-export-preparation-design.md}.
 */
public final class ExportPreparer {

    /**
     * The {@code SeedRandom} seed the driver always passes, so two runs over one export are byte-identical.
     * It is a parameter of {@link #prepare} only so the tests can vary it.
     */
    public static final long SEED = 20260919L;

    /** The prepared export and what the run did, for the driver to print. */
    public record Result(ExportEnvelope envelope, int usersActivated, int managersPromoted,
            int departmentTeams, int managerTeams, int teamsKept, int teamsDropped) {
    }

    private ExportPreparer() {
    }

    public static Result prepare(ExportEnvelope input, LocalDate joined, long seed) {
        List<LinkedHashMap<String, Object>> inputUsers = input.rows("users");
        if (inputUsers.isEmpty()) {
            throw new IllegalArgumentException("the export carries no users; there is nothing to prepare");
        }

        // Only a manager who is themselves in the export: users.manager_id can name somebody outside it, and
        // a team whose manager_id does not resolve fails the foreign key at import.
        Set<Object> ids = new HashSet<>();
        inputUsers.forEach(u -> ids.add(u.get("id")));
        Set<Object> managerIds = new HashSet<>();
        for (LinkedHashMap<String, Object> u : inputUsers) {
            Object manager = u.get("manager_id");
            if (manager != null && ids.contains(manager)) {
                managerIds.add(manager);
            }
        }

        int activated = 0;
        int promoted = 0;
        List<LinkedHashMap<String, Object>> users = new ArrayList<>();
        for (LinkedHashMap<String, Object> u : inputUsers) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>(u);
            if (!Boolean.TRUE.equals(row.get("active")) || row.get("deactivated_at") != null) {
                activated++;
            }
            row.put("active", true);
            // Not tidiness: ForecastRepository reads deactivated_at as the member's leaving date, so a user
            // flipped active while still carrying one is counted and then forecast at zero from that day.
            row.put("deactivated_at", null);
            if (managerIds.contains(row.get("id")) && "MEMBER".equals(row.get("role"))) {
                // A team whose manager_id names a plain MEMBER is inconsistent, and Rhythm halves a
                // TEAM_LEADER's seeded hours, which is the realistic shape. SKILL_TEAM_LEADER is never
                // produced: ForecastRepository does not count it, so it would drop these people entirely.
                row.put("role", "TEAM_LEADER");
                promoted++;
            }
            users.add(row);
        }

        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>(input.data());
        data.put("users", users);
        return new Result(input.withData(data), activated, promoted, 0, 0, 0, 0);
    }
}
