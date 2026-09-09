package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectPlannerTest {

    static final SeedConfig CFG = new SeedConfig(52, LocalDate.of(2026, 9, 6), 1, false, 0);

    static Map<UUID, Person> people(Team dept, Team mgr) {
        Map<UUID, Person> m = new HashMap<>();
        for (UUID id : dept.memberIds()) {
            m.put(id, new Person(id, "Head " + id.toString().substring(0, 4), "h@example.test", "Skill Team Leader", "PTE / CT2", "CT2", null, "SKILL_TEAM_LEADER", WorkFamily.COORDINATION, CFG.firstMonday(), null));
        }
        for (UUID id : mgr.memberIds()) {
            m.put(id, new Person(id, "Eng " + id.toString().substring(0, 4), "e@example.test", "Calibration Engineer", "PTE / CT2", "CT2", dept.managerId(), "MEMBER", WorkFamily.CALIBRATION, CFG.firstMonday(), null));
        }
        return m;
    }

    @Test
    void departmentTeamsGetTwoToFourProjectsWithWindowsAndMostlyActive() {
        UUID head = UUID.randomUUID();
        Team dept = new Team(UUID.randomUUID(), "PTE / CT2 Calibration & Testing 2", head, null, List.of(head), true, "CT2");
        Team mgr = new Team(UUID.randomUUID(), "CT2 · M", UUID.randomUUID(), dept.id(), List.of(UUID.randomUUID(), UUID.randomUUID()), false, "CT2");
        List<Project> projects = ProjectPlanner.plan(List.of(dept, mgr), people(dept, mgr), List.of(), CFG, new SeedRandom(11));
        assertTrue(projects.size() >= 2 && projects.size() <= 4, "projects " + projects.size());
        for (Project p : projects) {
            assertTrue(p.key().startsWith("CT2-"), p.key());
            assertEquals(dept.id(), p.teamId());
            assertEquals(head, p.ownerId());
            assertTrue(p.windowEnd().isAfter(p.windowStart()));
            if (p.status().equals("PLANNING")) {
                assertTrue(p.windowStart().isAfter(CFG.lastDay()));
            } else {
                assertEquals("ACTIVE", p.status());
                assertTrue(!p.windowStart().isBefore(CFG.firstMonday()));
            }
            assertEquals(WorkFamily.CALIBRATION, p.family());
        }
        assertEquals(projects.size(), projects.stream().map(Project::key).distinct().count(), "unique keys");
        assertEquals(projects, ProjectPlanner.projectsFor(mgr, List.of(dept, mgr), projects), "manager team sees its department's projects");
    }

    @Test
    void existingProjectsSpanTheWholeHistoryAndKeepTheirRows() {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        UUID team = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        row.put("id", UUID.randomUUID().toString());
        row.put("key", "WH");
        row.put("name", "WorkloadHub");
        row.put("status", "ACTIVE");
        row.put("previous_status", null);
        row.put("archived", false);
        row.put("archived_at", null);
        row.put("archived_by", null);
        row.put("owner_id", owner.toString());
        row.put("team_id", team.toString());
        row.put("version", 0L);
        row.put("description", "The app");
        row.put("next_task_number", 5L);
        row.put("created_at", "2026-09-03T13:59:58");
        row.put("updated_at", "2026-09-03T13:59:58");
        Team existing = new Team(team, "Backend Team", owner, null, List.of(owner), false, null);
        List<Project> projects = ProjectPlanner.plan(List.of(existing), Map.of(owner, new Person(owner, "O", "o@example.test", "Developer", null, null, null, "TEAM_LEADER", WorkFamily.UNKNOWN, CFG.firstMonday(), null)), List.of(row), CFG, new SeedRandom(1));
        Project wh = projects.stream().filter(Project::existing).findFirst().orElseThrow();
        assertEquals(CFG.firstMonday(), wh.windowStart());
        assertTrue(wh.windowEnd().isAfter(CFG.lastDay()));
        LinkedHashMap<String, Object> out = ProjectPlanner.row(wh, row, 42, CFG);
        assertEquals(42L, out.get("next_task_number"));
        assertEquals("The app", out.get("description"));
        assertEquals(List.of(wh), ProjectPlanner.projectsFor(existing, List.of(existing), projects));
    }

    @Test
    void headlessDepartmentProjectsGetTheFallbackOwner() {
        UUID member = UUID.randomUUID();
        UUID centerManager = UUID.randomUUID();
        Team dept = new Team(UUID.randomUUID(), "Unassigned", null, null, List.of(member), true, "GEN");
        Map<UUID, Person> people = new HashMap<>();
        people.put(member, new Person(member, "Member", "m@example.test", "Generalist", null, null, null, "MEMBER",
                WorkFamily.SUPPORT, CFG.firstMonday(), null));
        people.put(centerManager, new Person(centerManager, "Center Manager", "cm@example.test", "Center Manager",
                null, null, null, "CENTER_MANAGER", WorkFamily.COORDINATION, CFG.firstMonday(), null));
        List<Project> projects = ProjectPlanner.plan(List.of(dept), people, List.of(), CFG, new SeedRandom(7));
        assertTrue(!projects.isEmpty(), "a headless department still gets projects");
        for (Project p : projects) {
            assertEquals(centerManager, p.ownerId());
            LinkedHashMap<String, Object> row = ProjectPlanner.row(p, null, 1, CFG);
            assertTrue(row.get("owner_id") != null, "owner_id must not be null");
        }
    }
}
