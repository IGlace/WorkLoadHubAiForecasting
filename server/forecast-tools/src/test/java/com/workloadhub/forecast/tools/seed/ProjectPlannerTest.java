package com.workloadhub.forecast.tools.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectPlannerTest {

    /** Synthetic: the mode that mints a project team per project. Real mode is checked on its own below. */
    static final SeedConfig CFG = new SeedConfig(52, LocalDate.of(2026, 9, 6), 1, true, 0);
    static final SeedConfig REAL = new SeedConfig(52, LocalDate.of(2026, 9, 6), 1, false, 0);

    static Person person(UUID id, String name, String title, String role, WorkFamily family, UUID manager) {
        return new Person(id, name, title, "PTE / CT2", "CT2", manager, role, family, CFG.firstMonday(), null);
    }

    static Map<UUID, Person> people(UUID head, List<UUID> engineers) {
        Map<UUID, Person> m = new HashMap<>();
        m.put(head, person(head, "Head", "Skill Team Leader", "SKILL_TEAM_LEADER", WorkFamily.COORDINATION, null));
        for (UUID id : engineers) {
            m.put(id, person(id, "Eng " + id.toString().substring(0, 4), "Calibration Engineer", "MEMBER", WorkFamily.CALIBRATION, head));
        }
        return m;
    }

    @Test
    void aDepartmentGetsTwoToFourProjectsWithWindowsAndMostlyActive() {
        UUID head = UUID.randomUUID();
        List<UUID> engineers = List.of(UUID.randomUUID(), UUID.randomUUID());
        List<UUID> all = new java.util.ArrayList<>(engineers);
        all.add(head);
        Department dept = new Department("CT2", "PTE / CT2 Calibration & Testing 2", head, all);
        Map<UUID, Person> people = people(head, engineers);
        List<Project> projects = ProjectPlanner.plan(List.of(dept), people, List.of(), CFG, new SeedRandom(11));
        assertTrue(projects.size() >= 2 && projects.size() <= 4, "projects " + projects.size());
        for (Project p : projects) {
            assertTrue(p.key().startsWith("CT2-"), p.key());
            assertEquals("CT2", p.deptCode());
            assertEquals(head, p.ownerId());
            assertTrue(p.windowEnd().isAfter(p.windowStart()));
            if (p.status().equals("PLANNING")) {
                assertTrue(p.windowStart().isAfter(CFG.lastDay()));
            } else {
                assertEquals("ACTIVE", p.status());
                assertTrue(!p.windowStart().isBefore(CFG.firstMonday()));
            }
        }
        assertEquals(projects.size(), projects.stream().map(Project::key).distinct().count(), "unique keys");
        assertEquals(projects.size(), projects.stream().map(Project::teamId).distinct().count(), "one project team each");
        assertEquals(projects, ProjectPlanner.projectsFor(people.get(engineers.get(0)), projects),
                "everyone in the department can pick up its projects");
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
        Person ownerPerson = new Person(owner, "O", "Developer", null, null, null, "TEAM_LEADER", WorkFamily.UNKNOWN, CFG.firstMonday(), null);
        List<Project> projects = ProjectPlanner.plan(List.of(), Map.of(owner, ownerPerson), List.of(row), CFG, new SeedRandom(1));
        Project wh = projects.stream().filter(p -> p.key().equals("WH")).findFirst().orElseThrow();
        assertEquals(CFG.firstMonday(), wh.windowStart());
        assertTrue(wh.windowEnd().isAfter(CFG.lastDay()));
        LinkedHashMap<String, Object> out = ProjectPlanner.row(wh, row, 42, CFG);
        assertEquals(42L, out.get("next_task_number"));
        assertEquals("The app", out.get("description"));
        assertEquals(team, wh.teamId(), "an export's project keeps the team the export gave it");
        assertEquals(List.of(wh), ProjectPlanner.projectsFor(ownerPerson, projects),
                "its owner has no department of ours, so it is open to everybody");
    }

    @Test
    void peopleWithNoDepartmentGetTheProjectsOfTheDepartmentNobodyNamed() {
        // Directory.deptCode answers null for a blank department while Department.code() spells the same
        // thing as "". Compared raw, "".equals(null) is false, and these people would match no project at
        // all: no candidate, so no task, no log and no history. Invisible on the seed fixture, where every
        // synthetic user has a department; routine on a real export, where many have none.
        UUID member = UUID.randomUUID();
        Department unnamed = new Department("", "Unassigned", null, List.of(member));
        Person p = new Person(member, "Nobody", "Generalist", null, null, null, "MEMBER",
                WorkFamily.SUPPORT, CFG.firstMonday(), null);
        List<Project> projects = ProjectPlanner.plan(List.of(unnamed), Map.of(member, p), List.of(), CFG, new SeedRandom(5));
        assertTrue(!projects.isEmpty(), "the department nobody named still gets projects");
        for (Project pr : projects) {
            assertNull(pr.deptCode(), "a blank code is stored as null, the same thing the person carries");
            assertTrue(!pr.openToAll(), "it belongs to a department of ours, so it is not open to everybody");
        }
        assertEquals(projects, ProjectPlanner.projectsFor(p, projects), "and its own people can pick them up");
    }

    @Test
    void aDepartmentsProjectsAreNotOfferedToAnotherDepartment() {
        UUID ours = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        Department ct2 = new Department("CT2", "PTE / CT2", ours, List.of(ours));
        Person outsider = new Person(theirs, "Outsider", "Simulation Engineer", "PTE / SIM", "SIM", null, "MEMBER",
                WorkFamily.DESIGN, CFG.firstMonday(), null);
        List<Project> projects = ProjectPlanner.plan(List.of(ct2),
                Map.of(ours, person(ours, "Head", "Skill Team Leader", "SKILL_TEAM_LEADER", WorkFamily.COORDINATION, null)),
                List.of(), CFG, new SeedRandom(5));
        assertTrue(!projects.isEmpty());
        assertTrue(ProjectPlanner.projectsFor(outsider, projects).isEmpty(), "SIM does not work on CT2's projects");
    }

    @Test
    void realModeMintsNoProjectTeamBecauseItWritesNoTeamRows() {
        UUID head = UUID.randomUUID();
        List<UUID> engineers = List.of(UUID.randomUUID());
        List<UUID> all = new java.util.ArrayList<>(engineers);
        all.add(head);
        Department dept = new Department("CT2", "PTE / CT2", head, all);
        List<Project> projects = ProjectPlanner.plan(List.of(dept), people(head, engineers), List.of(), REAL, new SeedRandom(11));
        assertTrue(!projects.isEmpty());
        for (Project p : projects) {
            assertNull(p.teamId(), "a minted team id would be a dangling foreign key at import");
        }
        assertTrue(ProjectPlanner.projectTeams(projects, List.of(), new java.util.HashSet<>(), REAL, new SeedRandom(1))
                .teamRows().isEmpty(), "and so no team rows are written either");
    }

    @Test
    void headlessDepartmentProjectsGetTheFallbackOwner() {
        UUID member = UUID.randomUUID();
        UUID centerManager = UUID.randomUUID();
        Department dept = new Department("", "Unassigned", null, List.of(member));
        Map<UUID, Person> people = new HashMap<>();
        people.put(member, new Person(member, "Member", "Generalist", null, null, null, "MEMBER",
                WorkFamily.SUPPORT, CFG.firstMonday(), null));
        people.put(centerManager, new Person(centerManager, "Center Manager", "Center Manager",
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
