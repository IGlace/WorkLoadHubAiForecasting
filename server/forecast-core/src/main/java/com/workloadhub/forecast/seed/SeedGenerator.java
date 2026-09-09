package com.workloadhub.forecast.seed;

import com.workloadhub.forecast.data.ExportEnvelope;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/** Orchestrates the seed: directory, calendar, absences, projects, rhythm, work queue, capacity, envelope. */
public final class SeedGenerator {

    private SeedGenerator() {
    }

    /**
     * Replaces every occurrence of a scrubbed identity's original text with its replacement, in place.
     * Keys are applied longest first, so a name that is a literal prefix of another (e.g. "Amina" inside
     * "Aminata") cannot consume part of the longer name before the longer name's own entry gets a chance
     * to match the whole thing. Blank keys are never present (the caller filters them), but are skipped
     * here too: {@code "".replace("", x)} would otherwise splice x between every character of every string.
     */
    private static void scrubIdentities(LinkedHashMap<String, Object> row, Map<String, String> identityScrub) {
        if (identityScrub.isEmpty()) {
            return;
        }
        List<Map.Entry<String, String>> byLengthDesc = new ArrayList<>(identityScrub.entrySet());
        byLengthDesc.removeIf(e -> e.getKey() == null || e.getKey().isBlank());
        byLengthDesc.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getValue() instanceof String s) {
                String scrubbed = s;
                for (Map.Entry<String, String> r : byLengthDesc) {
                    scrubbed = scrubbed.replace(r.getKey(), r.getValue());
                }
                if (!scrubbed.equals(s)) {
                    e.setValue(scrubbed);
                }
            }
        }
    }

    /** Non-blank original identity fields of {@code original}, mapped to the same fields of {@code replacement}. */
    private static void addIdentityScrub(Map<String, String> identityScrub, LinkedHashMap<String, Object> original,
            LinkedHashMap<String, Object> replacement) {
        for (String field : List.of("full_name", "email", "username", "account_name")) {
            Object oldValue = original.get(field);
            if (oldValue instanceof String s && !s.isBlank()) {
                identityScrub.put(s, (String) replacement.get(field));
            }
        }
    }

    public static ExportEnvelope generate(ExportEnvelope input, SeedConfig cfg) {
        SeedRandom rnd = new SeedRandom(cfg.seed());
        boolean synthetic = cfg.synthetic();
        if (input == null && !synthetic) {
            throw new IllegalArgumentException("Real mode needs an input export");
        }

        // 1. source rows
        List<LinkedHashMap<String, Object>> users;
        List<LinkedHashMap<String, Object>> teams;
        List<LinkedHashMap<String, Object>> members;
        List<LinkedHashMap<String, Object>> projectRows;
        List<LinkedHashMap<String, Object>> statuses;
        List<LinkedHashMap<String, Object>> types;
        List<LinkedHashMap<String, Object>> roles;
        List<LinkedHashMap<String, Object>> holidays;
        List<LinkedHashMap<String, Object>> jobTitles;
        List<LinkedHashMap<String, Object>> syncMetadata;
        // every non-blank full_name/email/username/account_name of every input user (kept or not,
        // built before shrink) mapped to its anonymised replacement, for scrubbing free text carried
        // over unmodified from the input; empty (and unused) outside synthetic-mode-with-real-input.
        Map<String, String> identityScrub = new HashMap<>();
        if (input != null) {
            users = input.rows("users");
            teams = input.rows("teams");
            members = input.rows("team_members");
            projectRows = input.rows("projects");
            statuses = input.rows("task_statuses");
            types = input.rows("task_types");
            roles = input.rows("user_roles");
            holidays = input.rows("holidays");
            jobTitles = input.rows("job_titles");
            syncMetadata = input.rows("sync_metadata");
            if (synthetic) {
                // anonymise every input user first (not just the kept ones), so a person's original
                // identity can be scrubbed from carried-over free text even when they get shrunk out
                // of the users table entirely; shrink afterwards to select who is actually kept. The
                // fields shrink sorts and filters on (department, manager_id, id) are untouched by
                // anonymise, so which users end up kept is the same either order.
                List<LinkedHashMap<String, Object>> anonymisedAll = Anonymiser.anonymise(users, rnd);
                for (int i = 0; i < users.size(); i++) {
                    addIdentityScrub(identityScrub, users.get(i), anonymisedAll.get(i));
                }
                users = Anonymiser.shrink(anonymisedAll, cfg.users());
                // operator usernames with no forecast value; never carried into a synthetic export
                syncMetadata = List.of();
            }
            if (!Reference.covers(statuses, types)) {
                // a partial export (tests, early installs): use the reference rows instead
                statuses = ReferenceData.statusRows();
                types = ReferenceData.typeRows();
            }
        } else {
            users = ReferenceData.syntheticUsers(cfg.users() > 0 ? cfg.users() : 40, rnd);
            teams = List.of();
            members = List.of();
            projectRows = List.of();
            statuses = ReferenceData.statusRows();
            types = ReferenceData.typeRows();
            roles = ReferenceData.roleRows();
            holidays = ReferenceData.holidayRows(cfg.firstMonday().getYear(), cfg.lastDay().getYear());
            TreeSet<String> titles = new TreeSet<>();
            for (LinkedHashMap<String, Object> u : users) {
                if (u.get("job_title") != null) {
                    titles.add((String) u.get("job_title"));
                }
            }
            jobTitles = ReferenceData.jobTitleRows(titles);
            syncMetadata = List.of();
        }
        if (synthetic) {
            // keep only the kept users' memberships
            java.util.Set<Object> ids = new java.util.HashSet<>();
            users.forEach(u -> ids.add(u.get("id")));
            members = members.stream().filter(m -> ids.contains(m.get("user_id"))).toList();
            // real identities can be embedded in free text carried over from the input (a team name
            // built as "<dept> · <manager's full name>", say): scrub every kept and dropped input
            // user's original identity, wherever it appears in the rows kept from the input, with its
            // anonymised replacement, so no leftover string in the export can identify anyone.
            teams = teams.stream().map(t -> {
                LinkedHashMap<String, Object> row = new LinkedHashMap<>(t);
                if (row.get("manager_id") != null && !ids.contains(row.get("manager_id"))) {
                    row.put("manager_id", null);
                }
                scrubIdentities(row, identityScrub);
                return row;
            }).toList();
            projectRows = projectRows.stream()
                    .filter(p -> p.get("owner_id") == null || ids.contains(p.get("owner_id")))
                    .map(p -> {
                        LinkedHashMap<String, Object> row = new LinkedHashMap<>(p);
                        scrubIdentities(row, identityScrub);
                        return row;
                    })
                    .toList();
        }

        // 2. structure
        Directory.Result dir = Directory.derive(users, teams, members, cfg, rnd);
        Map<UUID, Person> people = new HashMap<>();
        for (Person p : dir.people()) {
            people.put(p.id(), p);
        }
        SeedCalendar cal = SeedCalendar.fromHolidayRows(holidays, cfg);

        // 3. absences, in id order for determinism
        List<Person> ordered = new ArrayList<>(dir.people());
        ordered.sort(Comparator.comparing(p -> p.id().toString()));
        Map<UUID, AbsencePlanner.Plan> plans = new HashMap<>();
        List<LinkedHashMap<String, Object>> absences = new ArrayList<>();
        List<LinkedHashMap<String, Object>> leaves = new ArrayList<>();
        for (Person p : ordered) {
            AbsencePlanner.Plan plan = AbsencePlanner.plan(p, cal, cfg, rnd);
            plans.put(p.id(), plan);
            if (p.counted()) {
                absences.addAll(plan.absenceRows());
                leaves.addAll(plan.leaveRows());
            }
        }

        // 4. projects, rhythm, work
        List<Project> projects = ProjectPlanner.plan(dir.teams(), people, projectRows, cfg, rnd);
        Rhythm rhythm = new Rhythm(cfg, cal, people, plans, dir.teams(), rnd);
        Reference ref = Reference.from(statuses, types);
        WorkQueue.Result work = WorkQueue.run(cfg, cal, dir.people(), plans, dir.teams(), projects, rhythm, ref,
                WorkQueue.Rates.DEFAULT, rnd);

        // 5. capacity
        Map<UUID, List<LinkedHashMap<String, Object>>> userCapacity = new HashMap<>();
        List<LinkedHashMap<String, Object>> userCapacityRows = new ArrayList<>();
        for (Person p : ordered) {
            if (!p.counted()) {
                continue;
            }
            List<LinkedHashMap<String, Object>> rows = CapacityWriter.userCapacity(p, plans.get(p.id()), cal, cfg, rnd);
            userCapacity.put(p.id(), rows);
            userCapacityRows.addAll(rows);
        }
        List<LinkedHashMap<String, Object>> teamCapacityRows = new ArrayList<>();
        List<Team> orderedTeams = new ArrayList<>(dir.teams());
        orderedTeams.sort(Comparator.comparing(t -> t.id().toString()));
        for (Team t : orderedTeams) {
            teamCapacityRows.addAll(CapacityWriter.teamCapacity(t, userCapacity,
                    (member, monday) -> work.assignedHours().getOrDefault(member, Map.of()).getOrDefault(monday, 0.0), cfg, rnd));
        }

        // 6. project rows with the next task number
        Map<String, LinkedHashMap<String, Object>> existingById = new HashMap<>();
        for (LinkedHashMap<String, Object> r : projectRows) {
            existingById.put((String) r.get("id"), r);
        }
        List<LinkedHashMap<String, Object>> outProjects = new ArrayList<>();
        List<Project> sortedProjects = new ArrayList<>(projects);
        sortedProjects.sort(Comparator.comparing(Project::key));
        for (Project p : sortedProjects) {
            outProjects.add(ProjectPlanner.row(p, existingById.get(p.id().toString()), work.nextTaskNumber().getOrDefault(p.id(), 1L), cfg));
        }

        // 7. envelope in table order
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>();
        for (String table : WorkloadHubSchema.TABLE_ORDER) {
            data.put(table, new ArrayList<>());
        }
        data.put("user_roles", roles);
        data.put("job_titles", jobTitles);
        data.put("users", dir.userRows());
        data.put("teams", dir.teamRows());
        data.put("team_members", dir.teamMemberRows());
        data.put("task_statuses", statuses);
        data.put("task_types", types);
        data.put("projects", outProjects);
        data.put("tasks", work.taskRows());
        data.put("task_history", work.historyRows());
        data.put("time_logs", work.timeLogRows());
        data.put("absences", absences);
        data.put("personal_leaves", leaves);
        data.put("holidays", cal.holidayRows());
        data.put("user_capacity", userCapacityRows);
        data.put("team_capacity", teamCapacityRows);
        data.put("sync_metadata", syncMetadata);
        String database = input == null ? "synthetic" : input.database();
        return new ExportEnvelope(database, "task_service", cfg.lastDay().atTime(18, 0).toString(),
                List.of("refresh_tokens"), data);
    }
}
