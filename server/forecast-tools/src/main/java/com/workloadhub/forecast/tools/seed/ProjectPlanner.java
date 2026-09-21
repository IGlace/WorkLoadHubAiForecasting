package com.workloadhub.forecast.tools.seed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Two to four projects per department, named for the department's kind of work, plus the export's own
 * projects. Each project also gets its own **project team**: the group who work on it, which is what the
 * application's `teams` table means (design 2026-09-21, ruling 10). The team's membership is only known once
 * the work is generated, so {@link #teamRows} and {@link #memberRows} are called afterwards.
 */
public final class ProjectPlanner {

    private record Template(String suffix, String name) {
    }

    private static final Map<WorkFamily, List<Template>> TEMPLATES = new EnumMap<>(WorkFamily.class);

    static {
        TEMPLATES.put(WorkFamily.CALIBRATION, List.of(new Template("CAL", "%s calibration campaign %d"),
                new Template("DATASET", "%s dataset consolidation %d"), new Template("MAP", "%s engine mapping wave %d")));
        TEMPLATES.put(WorkFamily.DATA, List.of(new Template("DATA", "%s data platform %d"),
                new Template("AI", "%s AI assistant %d"), new Template("DASH", "%s analytics dashboard %d")));
        TEMPLATES.put(WorkFamily.SUPPORT, List.of(new Template("OPS", "%s operations %d"),
                new Template("ONB", "%s onboarding cycle %d")));
        TEMPLATES.put(WorkFamily.SYSTEMS, List.of(new Template("SYS", "%s system design %d"),
                new Template("REQ", "%s requirements baseline %d"), new Template("DIAG", "%s diagnostics %d")));
        TEMPLATES.put(WorkFamily.ELECTRONICS, List.of(new Template("EE", "%s EE integration %d"),
                new Template("SW", "%s software release %d"), new Template("HW", "%s hardware bring-up %d")));
        TEMPLATES.put(WorkFamily.VALIDATION, List.of(new Template("VAL", "%s validation wave %d"),
                new Template("HOMOL", "%s homologation %d"), new Template("FLEET", "%s fleet test %d")));
        TEMPLATES.put(WorkFamily.DESIGN, List.of(new Template("SIM", "%s simulation study %d"),
                new Template("DMU", "%s DMU release %d"), new Template("CFD", "%s CFD campaign %d")));
        TEMPLATES.put(WorkFamily.COORDINATION, List.of(new Template("PMO", "%s coordination %d")));
        TEMPLATES.put(WorkFamily.UNKNOWN, List.of(new Template("PRJ", "%s project %d")));
    }

    private ProjectPlanner() {
    }

    /** The dominant family of a department's people. */
    static WorkFamily dominantFamily(Department department, Map<UUID, Person> people) {
        Map<WorkFamily, Integer> votes = new EnumMap<>(WorkFamily.class);
        for (UUID id : department.memberIds()) {
            Person p = people.get(id);
            if (p != null && !p.family().isUnknown()) {
                votes.merge(p.family(), 1, Integer::sum);
            }
        }
        return votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(WorkFamily.SYSTEMS);
    }

    /**
     * The owner for a headless department's projects: `projects.owner_id` is NOT NULL, but a department
     * team (e.g. the "Unassigned" one `Directory` builds for people with neither manager nor department,
     * or a real department without a head) can have a null `managerId`. Deterministic given the seed:
     * among `people`'s values sorted by id string, the first `CENTER_MANAGER`, else the first `ADMIN`,
     * else the first person at all.
     */
    static UUID fallbackOwner(Map<UUID, Person> people) {
        List<Person> sorted = new ArrayList<>(people.values());
        sorted.sort(Comparator.comparing(p -> p.id().toString()));
        return sorted.stream().filter(p -> p.role().equals("CENTER_MANAGER")).findFirst()
                .or(() -> sorted.stream().filter(p -> p.role().equals("ADMIN")).findFirst())
                .or(() -> sorted.stream().findFirst())
                .map(Person::id)
                .orElse(null);
    }

    public static List<Project> plan(List<Department> departments, Map<UUID, Person> people,
            List<LinkedHashMap<String, Object>> existingProjectRows, SeedConfig cfg, SeedRandom rnd) {
        List<Project> out = new ArrayList<>();
        UUID fallbackOwner = fallbackOwner(people);
        for (LinkedHashMap<String, Object> row : existingProjectRows) {
            UUID teamId = row.get("team_id") == null ? null : UUID.fromString((String) row.get("team_id"));
            UUID ownerId = row.get("owner_id") == null ? null : UUID.fromString((String) row.get("owner_id"));
            // An export's own project keeps its own team. Its department is its owner's, so the people who
            // already work under that owner are the ones who pick it up; with no owner in the directory it has
            // none, and is open to everybody.
            Person owner = ownerId == null ? null : people.get(ownerId);
            out.add(new Project(UUID.fromString((String) row.get("id")), (String) row.get("key"), (String) row.get("name"),
                    teamId, ownerId, String.valueOf(row.get("status")), owner == null ? null : owner.deptCode(),
                    cfg.firstMonday(), cfg.lastDay().plusWeeks(1)));
        }
        List<LocalDate> mondays = cfg.mondays();
        // global, not per department: projects.key is UNIQUE across the whole export, and the export's
        // own projects (existingProjectRows, already added to `out` above) must never be re-minted.
        Set<String> used = new HashSet<>();
        for (LinkedHashMap<String, Object> row : existingProjectRows) {
            if (row.get("key") instanceof String s) {
                used.add(s);
            }
        }
        for (Department department : departments) {
            String code = department.code() == null || department.code().isEmpty() ? "GEN" : department.code();
            WorkFamily family = dominantFamily(department, people);
            List<Template> templates = TEMPLATES.get(family);
            int count = rnd.between(2, 4);

            // Which slots are PLANNING, keeping at least one ACTIVE: a department with no active project
            // produces no work at all, which is the defect this guarantee exists to prevent.
            boolean[] planning = new boolean[count];
            int activeCount = 0;
            for (int i = 0; i < count; i++) {
                planning[i] = rnd.chance(1.0 / 6);
                if (!planning[i]) {
                    activeCount++;
                }
            }
            if (activeCount == 0) {
                planning[0] = false;
                activeCount = 1;
            }

            // The active slots' starts, dealt evenly across the WHOLE window. Before 2026-09-18 every
            // ACTIVE project started in its first 60%, so the projects begun early expired through the tail
            // with nothing to replace them and WorkQueue stopped giving those members work at all.
            int lastMonday = mondays.size() - 1;
            int[] startIndex = new int[count];
            int[] nextActiveStart = new int[count];
            int dealt = 0;
            for (int i = 0; i < count; i++) {
                if (!planning[i]) {
                    startIndex[i] = (int) ((long) dealt * lastMonday / activeCount);
                    dealt++;
                }
            }
            int following = lastMonday + 1;
            for (int i = count - 1; i >= 0; i--) {
                if (!planning[i]) {
                    nextActiveStart[i] = following;
                    following = startIndex[i];
                }
            }
            int lastActiveSlot = -1;
            for (int i = 0; i < count; i++) {
                if (!planning[i]) {
                    lastActiveSlot = i;
                }
            }

            for (int i = 0; i < count; i++) {
                Template t = templates.get(i % templates.size());
                int n = 1;
                String key = code + "-" + t.suffix();
                while (used.contains(key)) {
                    n++;
                    key = code + "-" + t.suffix() + n;
                }
                used.add(key);
                LocalDate start;
                LocalDate end;
                String status;
                if (planning[i]) {
                    start = cfg.lastDay().plusWeeks(rnd.between(1, 8));
                    end = start.plusWeeks(rnd.between(12, 40));
                    status = "PLANNING";
                } else {
                    start = mondays.get(startIndex[i]);
                    if (i == lastActiveSlot) {
                        // The department's last project always runs past the as-of date, so the horizon's
                        // first future week is covered; `activeOn` is half-open and this end is exactly
                        // lastDay + 1 week, so only that first week is ever covered here, not the +2 or +3
                        // weeks WorkQueue.planArrivals also looks at.
                        end = cfg.lastDay().plusWeeks(1);
                    } else {
                        // At least four weeks past the next project's start: its window opens inside this
                        // one, so the department's coverage is continuous.
                        int minWeeks = Math.max(12, nextActiveStart[i] - startIndex[i] + 4);
                        end = start.plusWeeks(rnd.between(minWeeks, Math.max(40, minWeeks)));
                        if (end.isAfter(cfg.lastDay().plusWeeks(1))) {
                            end = cfg.lastDay().plusWeeks(1);
                        }
                    }
                    status = "ACTIVE";
                }
                UUID owner = department.headId() != null ? department.headId() : fallbackOwner;
                // Real mode writes only the five work tables, so it writes no team row either: a project team
                // id minted here would be a dangling foreign key at import. `projects.team_id` is nullable,
                // and a null says honestly that this generator does not know who the project team is.
                UUID projectTeam = cfg.synthetic() ? rnd.uuid() : null;
                out.add(new Project(rnd.uuid(), key, String.format(t.name(), code, n), projectTeam, owner,
                        status, department.code(), start, end));
            }
        }
        return out;
    }

    /**
     * The projects a person can pick up: their department's, plus any project with no department of ours — a
     * real export's own, whose owner is outside the directory.
     */
    public static List<Project> projectsFor(Person p, List<Project> projects) {
        List<Project> out = new ArrayList<>();
        for (Project pr : projects) {
            if (pr.deptCode() == null || pr.deptCode().equals(p.deptCode())) {
                out.add(pr);
            }
        }
        return out;
    }

    /** The `teams` and `team_members` rows of the project teams, which the two lists must stay in step. */
    public record ProjectTeams(List<LinkedHashMap<String, Object>> teamRows, List<LinkedHashMap<String, Object>> memberRows) {
    }

    /**
     * One project team per project the seed invented: the people who were actually given its tasks. This is
     * what the application's `teams` table holds — a group formed around a project — so it is derived from the
     * generated work rather than declared in front of it, and it is written only for projects this generator
     * created. A project an export already carried keeps whatever team that export gave it.
     *
     * @param taskRows the generated tasks, read for `project_id` and `assignee_id`
     * @param usedNames the team names already taken, since `teams.name` is UNIQUE
     */
    public static ProjectTeams projectTeams(List<Project> minted, List<LinkedHashMap<String, Object>> taskRows,
            Set<String> usedNames, SeedConfig cfg, SeedRandom rnd) {
        Map<UUID, Set<UUID>> workersByProject = new TreeMap<>();
        for (LinkedHashMap<String, Object> t : taskRows) {
            Object project = t.get("project_id");
            Object assignee = t.get("assignee_id");
            if (project != null && assignee != null) {
                workersByProject.computeIfAbsent(UUID.fromString(String.valueOf(project)), k -> new TreeSet<>())
                        .add(UUID.fromString(String.valueOf(assignee)));
            }
        }
        List<LinkedHashMap<String, Object>> teamRows = new ArrayList<>();
        List<LinkedHashMap<String, Object>> memberRows = new ArrayList<>();
        for (Project p : minted) {
            if (p.teamId() == null) {
                // Real mode: no team was minted for it, so there are no rows to write (see plan).
                continue;
            }
            String stamp = p.windowStart().atTime(8, 0).toString();
            LinkedHashMap<String, Object> team = new LinkedHashMap<>();
            team.put("id", p.teamId().toString());
            team.put("name", Directory.uniqueName(p.name(), usedNames));
            team.put("active", true);
            team.put("version", 0L);
            team.put("manager_id", p.ownerId() == null ? null : p.ownerId().toString());
            // A project team is flat: it is a group around a project, not a place in the company structure.
            team.put("parent_team_id", null);
            team.put("created_at", stamp);
            team.put("updated_at", stamp);
            teamRows.add(team);
            for (UUID worker : workersByProject.getOrDefault(p.id(), Set.of())) {
                LinkedHashMap<String, Object> m = new LinkedHashMap<>();
                m.put("id", rnd.uuid().toString());
                m.put("team_id", p.teamId().toString());
                m.put("user_id", worker.toString());
                m.put("joined_at", stamp);
                m.put("created_at", stamp);
                m.put("updated_at", stamp);
                memberRows.add(m);
            }
        }
        return new ProjectTeams(teamRows, memberRows);
    }

    public static LinkedHashMap<String, Object> row(Project p, LinkedHashMap<String, Object> existingRow, long nextTaskNumber, SeedConfig cfg) {
        LinkedHashMap<String, Object> row = existingRow == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existingRow);
        if (existingRow == null) {
            String created = cfg.firstMonday().atTime(9, 0).toString();
            row.put("archived", false);
            row.put("archived_at", null);
            row.put("created_at", created);
            row.put("next_task_number", nextTaskNumber);
            row.put("updated_at", created);
            row.put("version", 0L);
            row.put("archived_by", null);
            row.put("id", p.id().toString());
            row.put("owner_id", p.ownerId() == null ? null : p.ownerId().toString());
            row.put("team_id", p.teamId() == null ? null : p.teamId().toString());
            row.put("key", p.key());
            row.put("previous_status", null);
            row.put("status", p.status());
            row.put("name", p.name());
            row.put("description", null);
        } else {
            row.put("next_task_number", nextTaskNumber);
        }
        return row;
    }
}
