package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Turns the directory columns of users (manager, department, job title) into teams, roles and memberships. */
public final class Directory {

    public record Result(List<Person> people, List<Team> teams, List<LinkedHashMap<String, Object>> userRows,
            List<LinkedHashMap<String, Object>> teamRows, List<LinkedHashMap<String, Object>> teamMemberRows) {
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
     */
    private static String uniqueName(String candidate, Set<String> usedNames) {
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

    public static Result derive(List<LinkedHashMap<String, Object>> userRows, List<LinkedHashMap<String, Object>> teamRows,
            List<LinkedHashMap<String, Object>> memberRows, SeedConfig cfg, SeedRandom rnd) {
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
            people.put(id, new Person(id, (String) u.get("full_name"), (String) u.get("email"), title, dept,
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

        // 3. roles: managers lead, department heads are skill team leaders, admins and the center manager stay
        for (UUID managerId : reports.keySet()) {
            Person m = people.get(managerId);
            if (m.role().equals("MEMBER") || m.role().equals("TEAM_LEADER") || m.role().equals("VIEWER")) {
                people.put(managerId, m.withRole("TEAM_LEADER"));
            }
        }
        Map<String, UUID> heads = new TreeMap<>();
        for (Map.Entry<String, List<UUID>> e : byDept.entrySet()) {
            if (e.getKey().isEmpty()) {
                continue;
            }
            UUID head = e.getValue().stream()
                    .filter(id -> people.get(id).jobTitle() != null
                            && people.get(id).jobTitle().toLowerCase(Locale.ROOT).contains("skill team leader"))
                    .findFirst()
                    .orElseGet(() -> e.getValue().stream()
                            .filter(reports::containsKey)
                            .max(Comparator.comparingInt(id -> reports.get(id).size()))
                            .orElse(null));
            if (head != null) {
                heads.put(e.getKey(), head);
                Person h = people.get(head);
                if (!h.role().equals("ADMIN") && !h.role().equals("CENTER_MANAGER")) {
                    people.put(head, h.withRole("SKILL_TEAM_LEADER"));
                }
            }
        }

        // 4. department teams (one per code, plus "Unassigned" for people with neither manager nor department)
        List<Team> teams = new ArrayList<>();
        Map<String, UUID> deptTeamIds = new TreeMap<>();
        // seeded from the export's own team names so a generated name never collides with one of theirs
        // (teams.name is UNIQUE); every name this loop and the next one mint is added as it is chosen.
        Set<String> usedNames = new HashSet<>();
        for (LinkedHashMap<String, Object> t : teamRows) {
            if (t.get("name") instanceof String s) {
                usedNames.add(s);
            }
        }
        for (String code : byDept.keySet()) {
            UUID teamId = rnd.uuid();
            deptTeamIds.put(code, teamId);
            List<UUID> members = new ArrayList<>();
            for (UUID id : byDept.get(code)) {
                Person p = people.get(id);
                boolean hasManager = p.managerId() != null && people.containsKey(p.managerId());
                if (!hasManager || id.equals(heads.get(code))) {
                    members.add(id);
                }
            }
            String name = uniqueName(code.isEmpty() ? "Unassigned" : deptLabel.getOrDefault(code, code), usedNames);
            teams.add(new Team(teamId, name, heads.get(code), null, members, true, code.isEmpty() ? null : code));
        }

        // 5. manager teams
        for (Map.Entry<UUID, List<UUID>> e : reports.entrySet()) {
            Person m = people.get(e.getKey());
            String code = m.deptCode();
            if (code == null) {
                Map<String, Integer> votes = new TreeMap<>();
                for (UUID r : e.getValue()) {
                    String c = people.get(r).deptCode();
                    if (c != null) {
                        votes.merge(c, 1, Integer::sum);
                    }
                }
                code = votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
            }
            List<UUID> members = new ArrayList<>();
            members.add(m.id());
            members.addAll(e.getValue());
            String name = uniqueName((code.isEmpty() ? "Team" : code) + " · " + m.fullName(), usedNames);
            teams.add(new Team(rnd.uuid(), name, m.id(), deptTeamIds.get(code), members, false, code.isEmpty() ? null : code));
        }

        // 6. rows: users updated, existing teams and memberships kept, new ones appended
        List<LinkedHashMap<String, Object>> newUserRows = new ArrayList<>();
        for (LinkedHashMap<String, Object> u : sortedUsers) {
            Person p = people.get(UUID.fromString((String) u.get("id")));
            LinkedHashMap<String, Object> row = new LinkedHashMap<>(u);
            row.put("role", p.role());
            row.put("active", p.left() == null);
            row.put("deactivated_at", p.left() == null ? null : p.left().atTime(18, 0).toString());
            newUserRows.add(row);
        }
        List<LinkedHashMap<String, Object>> newTeamRows = new ArrayList<>(teamRows);
        List<LinkedHashMap<String, Object>> newMemberRows = new ArrayList<>(memberRows);
        String now = first.atTime(8, 0).toString();
        for (Team t : teams) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.id().toString());
            row.put("name", t.name());
            row.put("active", true);
            row.put("version", 0L);
            row.put("manager_id", t.managerId() == null ? null : t.managerId().toString());
            row.put("parent_team_id", t.parentId() == null ? null : t.parentId().toString());
            row.put("created_at", now);
            row.put("updated_at", now);
            newTeamRows.add(row);
            for (UUID member : t.memberIds()) {
                LocalDateTime joined = people.get(member).joined().atTime(8, 0);
                LinkedHashMap<String, Object> m = new LinkedHashMap<>();
                m.put("id", rnd.uuid().toString());
                m.put("team_id", t.id().toString());
                m.put("user_id", member.toString());
                m.put("joined_at", joined.toString());
                m.put("created_at", joined.toString());
                m.put("updated_at", joined.toString());
                newMemberRows.add(m);
            }
        }
        for (Team existing : existingTeams(teamRows, memberRows)) {
            teams.add(existing);
        }
        return new Result(new ArrayList<>(people.values()), teams, newUserRows, newTeamRows, newMemberRows);
    }

    /** The export's own teams, as Team values, so the generator can give them tasks too. */
    static List<Team> existingTeams(List<LinkedHashMap<String, Object>> teamRows, List<LinkedHashMap<String, Object>> memberRows) {
        List<Team> out = new ArrayList<>();
        for (LinkedHashMap<String, Object> t : teamRows) {
            UUID id = UUID.fromString((String) t.get("id"));
            List<UUID> members = new ArrayList<>();
            for (LinkedHashMap<String, Object> m : memberRows) {
                if (id.toString().equals(m.get("team_id"))) {
                    members.add(UUID.fromString((String) m.get("user_id")));
                }
            }
            out.add(new Team(id, (String) t.get("name"),
                    t.get("manager_id") == null ? null : UUID.fromString((String) t.get("manager_id")),
                    t.get("parent_team_id") == null ? null : UUID.fromString((String) t.get("parent_team_id")),
                    members, false, null));
        }
        return out;
    }
}
