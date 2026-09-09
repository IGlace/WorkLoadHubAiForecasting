package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Two to four projects per department, named for the department's kind of work, plus the export's own projects. */
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

    /** The dominant family of a team's members (department teams: everyone in that department code). */
    static WorkFamily dominantFamily(Team team, List<Team> teams, Map<UUID, Person> people) {
        Map<WorkFamily, Integer> votes = new EnumMap<>(WorkFamily.class);
        for (Team t : teams) {
            boolean inDept = t.id().equals(team.id()) || (team.department() && team.id().equals(t.parentId()));
            if (!inDept) {
                continue;
            }
            for (UUID id : t.memberIds()) {
                Person p = people.get(id);
                if (p != null && !p.family().isUnknown()) {
                    votes.merge(p.family(), 1, Integer::sum);
                }
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

    public static List<Project> plan(List<Team> teams, Map<UUID, Person> people,
            List<LinkedHashMap<String, Object>> existingProjectRows, SeedConfig cfg, SeedRandom rnd) {
        List<Project> out = new ArrayList<>();
        UUID fallbackOwner = fallbackOwner(people);
        for (LinkedHashMap<String, Object> row : existingProjectRows) {
            UUID teamId = row.get("team_id") == null ? null : UUID.fromString((String) row.get("team_id"));
            UUID ownerId = row.get("owner_id") == null ? null : UUID.fromString((String) row.get("owner_id"));
            out.add(new Project(UUID.fromString((String) row.get("id")), (String) row.get("key"), (String) row.get("name"),
                    teamId, ownerId, String.valueOf(row.get("status")),
                    cfg.firstMonday(), cfg.lastDay().plusWeeks(1), true, WorkFamily.UNKNOWN));
        }
        List<LocalDate> mondays = cfg.mondays();
        for (Team team : teams) {
            if (!team.department()) {
                continue;
            }
            String code = team.deptCode() == null ? "GEN" : team.deptCode();
            WorkFamily family = dominantFamily(team, teams, people);
            List<Template> templates = TEMPLATES.get(family);
            int count = rnd.between(2, 4);
            Set<String> used = new HashSet<>();
            for (int i = 0; i < count; i++) {
                Template t = templates.get(i % templates.size());
                int n = 1;
                String key = code + "-" + t.suffix();
                while (used.contains(key)) {
                    n++;
                    key = code + "-" + t.suffix() + n;
                }
                used.add(key);
                boolean planning = rnd.chance(1.0 / 6);
                LocalDate start;
                LocalDate end;
                String status;
                if (planning) {
                    start = cfg.lastDay().plusWeeks(rnd.between(1, 8));
                    end = start.plusWeeks(rnd.between(12, 40));
                    status = "PLANNING";
                } else {
                    int startIndex = rnd.between(0, Math.max(0, (int) (mondays.size() * 0.6) - 1));
                    start = mondays.get(startIndex);
                    end = start.plusWeeks(rnd.between(12, 40));
                    if (end.isAfter(cfg.lastDay().plusWeeks(1))) {
                        end = cfg.lastDay().plusWeeks(1);
                    }
                    status = "ACTIVE";
                }
                UUID owner = team.managerId() != null ? team.managerId() : fallbackOwner;
                out.add(new Project(rnd.uuid(), key, String.format(t.name(), code, n), team.id(), owner,
                        status, start, end, false, family));
            }
        }
        return out;
    }

    /** The projects a team's members work on. */
    public static List<Project> projectsFor(Team team, List<Team> teams, List<Project> projects) {
        UUID deptId = team.department() ? team.id() : team.parentId();
        List<Project> out = new ArrayList<>();
        for (Project p : projects) {
            if (p.teamId() != null && (p.teamId().equals(deptId) || p.teamId().equals(team.id()))) {
                out.add(p);
            }
        }
        return out;
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
