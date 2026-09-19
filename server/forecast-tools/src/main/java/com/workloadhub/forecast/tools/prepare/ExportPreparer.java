package com.workloadhub.forecast.tools.prepare;

import com.workloadhub.forecast.tools.export.ExportEnvelope;
import com.workloadhub.forecast.tools.seed.Directory;
import com.workloadhub.forecast.tools.seed.SeedRandom;
import com.workloadhub.forecast.tools.seed.Team;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

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

    /** One user, reduced to the fields the structure is derived from. */
    record Member(UUID id, String fullName, String jobTitle, String department, String deptCode,
            UUID managerId, String role) {
    }

    /** The users as {@link Member}s, sorted by id string so the derivation is the same on every run. */
    static List<Member> members(List<LinkedHashMap<String, Object>> users) {
        List<LinkedHashMap<String, Object>> sorted = new ArrayList<>(users);
        sorted.sort(Comparator.comparing(u -> String.valueOf(u.get("id"))));
        List<Member> out = new ArrayList<>();
        for (LinkedHashMap<String, Object> u : sorted) {
            String dept = (String) u.get("department");
            out.add(new Member(UUID.fromString((String) u.get("id")), (String) u.get("full_name"),
                    (String) u.get("job_title"), dept, Directory.deptCode(dept),
                    u.get("manager_id") == null ? null : UUID.fromString((String) u.get("manager_id")),
                    String.valueOf(u.get("role"))));
        }
        return out;
    }

    /**
     * The department teams (parentless, one per department code) followed by the manager teams (one per user
     * with reports, child of that manager's department team).
     *
     * <p>The two levels are not cosmetic. {@code ForecastRepository} picks a member's primary team as the first
     * of their teams that has a parent; {@code ProjectPlanner} gives projects only to parentless teams, so a flat
     * structure produces no work at all; and {@code Rhythm} lets a manager team win over a department team.
     * This mirrors what {@code Directory} builds on the synthetic path.
     *
     * <p>{@code usedNames} is mutated as names are minted, and should be seeded with the names of any
     * pre-existing team the caller is keeping: {@code teams.name} is UNIQUE.
     */
    static List<Team> deriveTeams(List<Member> people, Set<String> usedNames, SeedRandom rnd) {
        Map<UUID, Member> byId = new LinkedHashMap<>();
        people.forEach(m -> byId.put(m.id(), m));

        Map<UUID, List<UUID>> reports = new TreeMap<>();
        Map<String, List<UUID>> byDept = new TreeMap<>();
        Map<String, String> deptLabel = new TreeMap<>();
        for (Member m : people) {
            if (m.managerId() != null && byId.containsKey(m.managerId())) {
                reports.computeIfAbsent(m.managerId(), k -> new ArrayList<>()).add(m.id());
            }
            String code = m.deptCode() == null ? "" : m.deptCode();
            byDept.computeIfAbsent(code, k -> new ArrayList<>()).add(m.id());
            // the longest department string wins, so "PTE / CT2" and "PTE / CT2 Calibration & Testing 2"
            // share a code and the team carries the fuller label
            if (m.department() != null && m.department().length() > deptLabel.getOrDefault(code, "").length()) {
                deptLabel.put(code, m.department());
            }
        }

        Map<String, UUID> heads = new TreeMap<>();
        for (Map.Entry<String, List<UUID>> e : byDept.entrySet()) {
            if (e.getKey().isEmpty()) {
                continue;
            }
            UUID head = e.getValue().stream()
                    .filter(id -> byId.get(id).jobTitle() != null
                            && byId.get(id).jobTitle().toLowerCase(Locale.ROOT).contains("skill team leader"))
                    .findFirst()
                    .orElseGet(() -> e.getValue().stream()
                            .filter(reports::containsKey)
                            .max(Comparator.comparingInt(id -> reports.get(id).size()))
                            .orElse(null));
            if (head != null) {
                heads.put(e.getKey(), head);
            }
        }

        List<Team> teams = new ArrayList<>();
        Map<String, UUID> deptTeamIds = new TreeMap<>();
        for (Map.Entry<String, List<UUID>> e : byDept.entrySet()) {
            String code = e.getKey();
            UUID teamId = rnd.uuid();
            deptTeamIds.put(code, teamId);
            List<UUID> memberIds = new ArrayList<>();
            for (UUID id : e.getValue()) {
                Member m = byId.get(id);
                boolean hasManager = m.managerId() != null && byId.containsKey(m.managerId());
                if (!hasManager || id.equals(heads.get(code))) {
                    memberIds.add(id);
                }
            }
            String name = Directory.uniqueName(code.isEmpty() ? "Unassigned" : deptLabel.getOrDefault(code, code), usedNames);
            // managerId may be null: a department with no head is legal, and ProjectPlanner.fallbackOwner
            // gives its projects to the CENTER_MANAGER or the ADMIN.
            teams.add(new Team(teamId, name, heads.get(code), null, memberIds, true, code.isEmpty() ? null : code));
        }

        for (Map.Entry<UUID, List<UUID>> e : reports.entrySet()) {
            Member m = byId.get(e.getKey());
            String code = m.deptCode();
            if (code == null) {
                // a manager with no department of their own takes the majority code of their reports
                Map<String, Integer> votes = new TreeMap<>();
                for (UUID r : e.getValue()) {
                    String c = byId.get(r).deptCode();
                    if (c != null) {
                        votes.merge(c, 1, Integer::sum);
                    }
                }
                code = votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
            }
            List<UUID> memberIds = new ArrayList<>();
            memberIds.add(m.id());
            memberIds.addAll(e.getValue());
            String name = Directory.uniqueName((code.isEmpty() ? "Team" : code) + " · " + m.fullName(), usedNames);
            teams.add(new Team(rnd.uuid(), name, m.id(), deptTeamIds.get(code), memberIds, false,
                    code.isEmpty() ? null : code));
        }
        return teams;
    }
}
