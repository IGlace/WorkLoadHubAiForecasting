package com.workloadhub.forecast.tools.seed;

import com.workloadhub.forecast.data.EffectiveRole;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Turns the directory columns of users (manager, department, job title) into departments and roles.
 *
 * <p>It writes no `teams` or `team_members` rows. It used to synthesise both, on the belief that they were
 * the company structure; they are project teams, and the structure is `users.manager_id` (design
 * 2026-09-21). {@link ProjectPlanner} writes the project teams instead, from the work it plans.
 */
public final class Directory {

    public record Result(List<Person> people, List<Department> departments, List<LinkedHashMap<String, Object>> userRows) {
    }

    private static final double LEAVE_SHARE = 0.03;
    private static final double LATE_JOIN_SHARE = 0.10;
    /** `teams.name varchar(100)`. */
    private static final int MAX_TEAM_NAME = 100;

    private Directory() {
    }

    /**
     * {@code candidate}, truncated to {@link #MAX_TEAM_NAME} and, if that collides with a name already
     * in {@code usedNames} (the export's own teams, plus every name generated so far this run), given a
     * numeric suffix (`"CT2 · Lead One 2"`) until it is unique. `teams.name` is UNIQUE, and both a real
     * export's own rows and a naming pattern this generator reuses can otherwise collide.
     *
     */
    static String uniqueName(String candidate, Set<String> usedNames) {
        String truncated = candidate.length() > MAX_TEAM_NAME ? candidate.substring(0, MAX_TEAM_NAME) : candidate;
        if (usedNames.add(truncated)) {
            return truncated;
        }
        for (int n = 2;; n++) {
            String suffix = " " + n;
            int maxBase = MAX_TEAM_NAME - suffix.length();
            String base = candidate.length() > maxBase ? candidate.substring(0, maxBase) : candidate;
            String name = base + suffix;
            if (usedNames.add(name)) {
                return name;
            }
        }
    }

    /** "PTE / CT2 Calibration & Testing 2" -> "CT2"; the first token after the slash, trailing punctuation dropped. */
    public static String deptCode(String department) {
        if (department == null || department.isBlank()) {
            return null;
        }
        String tail = department.contains("/") ? department.substring(department.indexOf('/') + 1) : department;
        String first = tail.trim().split("\\s+")[0];
        first = first.replaceAll("[^A-Za-z0-9&]+$", "");
        return first.isEmpty() ? null : first.toUpperCase(Locale.ROOT);
    }

    public static Result derive(List<LinkedHashMap<String, Object>> userRows, SeedConfig cfg, SeedRandom rnd) {
        List<LinkedHashMap<String, Object>> sortedUsers = new ArrayList<>(userRows);
        sortedUsers.sort(Comparator.comparing(u -> String.valueOf(u.get("id"))));

        // 1. people, joined/left dates, families
        Map<UUID, Person> people = new LinkedHashMap<>();
        LocalDate first = cfg.firstMonday();
        LocalDate last = cfg.lastDay();
        for (LinkedHashMap<String, Object> u : sortedUsers) {
            UUID id = UUID.fromString((String) u.get("id"));
            String dept = (String) u.get("department");
            UUID manager = u.get("manager_id") == null ? null : UUID.fromString((String) u.get("manager_id"));
            LocalDate joined = first;
            if (rnd.chance(LATE_JOIN_SHARE)) {
                joined = first.plusWeeks(rnd.between(1, Math.max(1, cfg.weeks() - 4)));
            }
            LocalDate left = null;
            if (rnd.chance(LEAVE_SHARE)) {
                int span = (int) (java.time.temporal.ChronoUnit.DAYS.between(joined, last));
                if (span > 60) {
                    left = joined.plusDays(rnd.between((int) (span * 0.6), span - 1));
                }
            }
            String role = String.valueOf(u.get("role"));
            String title = (String) u.get("job_title");
            people.put(id, new Person(id, (String) u.get("full_name"), title, dept,
                    deptCode(dept), manager, role, WorkFamily.classify(title), joined, left));
        }

        // 2. reports per manager, people per department code
        Map<UUID, List<UUID>> reports = new TreeMap<>();
        Map<String, List<UUID>> byDept = new TreeMap<>();
        Map<String, String> deptLabel = new HashMap<>();
        for (Person p : people.values()) {
            if (p.managerId() != null && people.containsKey(p.managerId())) {
                reports.computeIfAbsent(p.managerId(), k -> new ArrayList<>()).add(p.id());
            }
            String code = p.deptCode() == null ? "" : p.deptCode();
            byDept.computeIfAbsent(code, k -> new ArrayList<>()).add(p.id());
            if (p.department() != null && p.department().length() > deptLabel.getOrDefault(code, "").length()) {
                deptLabel.put(code, p.department());
            }
        }

        // 3. roles: the job title says who leads, and a leader nobody counted reports to is a member. This
        // is the module's own rule (EffectiveRole), applied here so the seeded users.role says what the
        // forecast will derive from the same rows rather than something else. ADMIN and CENTER_MANAGER are
        // left alone by the rule itself, which ProjectPlanner.fallbackOwner depends on.
        //
        // The activity flag is this directory's own leaving date, the one step 1 invented and step 5 writes
        // back as `active`, so the seeded column and the role the module derives from the seeded rows are the
        // same answer. In real mode nothing is written, and a leader whose invented departures empty their
        // team is shaped as a member for the rest of the seed — a simulation detail, since the real rows the
        // module reads are untouched.
        List<EffectiveRole.Candidate> candidates = new ArrayList<>();
        for (Person p : people.values()) {
            UUID manager = people.containsKey(p.managerId()) ? p.managerId() : null;
            candidates.add(new EffectiveRole.Candidate(p.id(), manager, p.role(), p.jobTitle(), p.left() == null));
        }
        Map<UUID, String> roles = EffectiveRole.resolve(candidates);
        for (Person p : List.copyOf(people.values())) {
            people.put(p.id(), p.withRole(roles.get(p.id())));
        }

        // 4. departments: one per code, headed by the senior-most person in it. The head owns its projects.
        List<Department> departments = new ArrayList<>();
        for (Map.Entry<String, List<UUID>> e : byDept.entrySet()) {
            String code = e.getKey();
            UUID head = e.getValue().stream()
                    .filter(id -> people.get(id).role().equals("SKILL_TEAM_LEADER"))
                    .findFirst()
                    .orElseGet(() -> e.getValue().stream()
                            .filter(reports::containsKey)
                            .max(Comparator.comparingInt(id -> reports.get(id).size()))
                            .orElse(null));
            String label = code.isEmpty() ? "Unassigned" : deptLabel.getOrDefault(code, code);
            departments.add(new Department(code, label, head, List.copyOf(e.getValue())));
        }

        // Real mode leaves the application's own users alone: it writes the five work tables and nothing else
        // (design 2026-09-17, section 7.1). The structure above is still derived, because the work is shaped by
        // it; only the rows are left untouched.
        if (!cfg.synthetic()) {
            return new Result(new ArrayList<>(people.values()), departments, userRows);
        }

        // 5. rows: the synthetic directory's own users, with the roles and dates this derivation gave them
        List<LinkedHashMap<String, Object>> newUserRows = new ArrayList<>();
        for (LinkedHashMap<String, Object> u : sortedUsers) {
            Person p = people.get(UUID.fromString((String) u.get("id")));
            LinkedHashMap<String, Object> row = new LinkedHashMap<>(u);
            row.put("role", p.role());
            row.put("active", p.left() == null);
            row.put("deactivated_at", p.left() == null ? null : p.left().atTime(18, 0).toString());
            newUserRows.add(row);
        }
        return new Result(new ArrayList<>(people.values()), departments, newUserRows);
    }

    /**
     * The team a person is forecast in: their manager's, or their own when they have no manager inside the
     * directory. The same rule as {@code FeatureBuilder.teamOf}, so the seed and the module agree on who
     * belongs with whom.
     */
    public static UUID teamKey(Person p, Map<UUID, Person> people) {
        return p.managerId() != null && people.containsKey(p.managerId()) ? p.managerId() : p.id();
    }
}
