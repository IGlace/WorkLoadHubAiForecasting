package com.workloadhub.forecast.tools.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.tools.export.ExportEnvelope;
import com.workloadhub.forecast.tools.seed.SeedRandom;
import com.workloadhub.forecast.tools.seed.Team;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExportPreparerTest {

    static final UUID HEAD = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final UUID MGR = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final UUID ENG1 = UUID.fromString("30000000-0000-0000-0000-000000000003");
    static final UUID ENG2 = UUID.fromString("30000000-0000-0000-0000-000000000004");
    static final UUID ORPHAN = UUID.fromString("30000000-0000-0000-0000-000000000005");
    static final UUID LOST = UUID.fromString("30000000-0000-0000-0000-000000000006");
    static final UUID BOSS = UUID.fromString("30000000-0000-0000-0000-000000000007");

    static final LocalDate JOINED = LocalDate.of(2021, 1, 4);

    static LinkedHashMap<String, Object> user(UUID id, String name, String title, String dept, UUID manager,
            String role, boolean active) {
        LinkedHashMap<String, Object> u = new LinkedHashMap<>();
        u.put("id", id.toString());
        u.put("role", role);
        u.put("email", name.toLowerCase().replace(' ', '.') + "@example.test");
        u.put("active", active);
        u.put("version", 0L);
        u.put("password", null);
        u.put("username", name.toLowerCase().replace(' ', '.'));
        u.put("full_name", name);
        u.put("job_title", title);
        u.put("object_id", null);
        u.put("created_at", "2025-01-01T08:00:00");
        u.put("department", dept);
        u.put("manager_id", manager == null ? null : manager.toString());
        u.put("updated_at", "2025-01-01T08:00:00");
        u.put("account_name", null);
        u.put("deactivated_at", active ? null : "2026-01-01T08:00:00");
        u.put("manager_object_id", null);
        return u;
    }

    /** Six people in two departments, one of them with no department at all, plus a centre manager. */
    static List<LinkedHashMap<String, Object>> users() {
        return new ArrayList<>(List.of(
                user(HEAD, "Head One", "Skill Team Leader", "PTE / CT2 Calibration & Testing 2", null, "MEMBER", false),
                user(MGR, "Manager Two", "Team Leader Calibration", "PTE / CT2 Calibration & Testing 2", HEAD, "MEMBER", false),
                user(ENG1, "Eng Three", "Calibration Engineer", "PTE / CT2", MGR, "MEMBER", false),
                user(ENG2, "Eng Four", "Calibration Engineer", "PTE / CT2 Calibration & Testing 2", MGR, "MEMBER", false),
                user(ORPHAN, "Orphan Five", "Simulation Engineer", "PTE / SIM Simulation", null, "MEMBER", true),
                user(LOST, "Lost Six", null, null, null, "MEMBER", false),
                user(BOSS, "Boss Seven", "Centre Manager", null, null, "CENTER_MANAGER", false)));
    }

    static ExportEnvelope envelope(List<LinkedHashMap<String, Object>> users,
            List<LinkedHashMap<String, Object>> teams, List<LinkedHashMap<String, Object>> members) {
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>();
        data.put("users", users);
        data.put("teams", teams);
        data.put("team_members", members);
        return new ExportEnvelope("workloadhub", "task_service", "2026-09-19T10:00:00", List.of(), data);
    }

    static ExportPreparer.Result prepared() {
        return ExportPreparer.prepare(envelope(users(), new ArrayList<>(), new ArrayList<>()), JOINED, ExportPreparer.SEED);
    }

    static LinkedHashMap<String, Object> row(List<LinkedHashMap<String, Object>> rows, UUID id) {
        return rows.stream().filter(r -> id.toString().equals(r.get("id"))).findFirst().orElseThrow();
    }

    @Test
    void everyUserComesOutActiveAndNeverDeactivated() {
        ExportPreparer.Result result = prepared();
        List<LinkedHashMap<String, Object>> out = result.envelope().rows("users");
        assertEquals(7, out.size());
        for (LinkedHashMap<String, Object> u : out) {
            assertEquals(Boolean.TRUE, u.get("active"), u.get("full_name") + " must be active");
            assertNull(u.get("deactivated_at"), u.get("full_name") + " must carry no deactivation");
        }
        assertEquals(6, result.usersActivated(), "Orphan Five arrived active and is not counted");
    }

    @Test
    void managersBecomeTeamLeadersAndNobodyBecomesASkillTeamLeader() {
        ExportPreparer.Result result = prepared();
        List<LinkedHashMap<String, Object>> out = result.envelope().rows("users");
        assertEquals("TEAM_LEADER", row(out, HEAD).get("role"), "Head One manages Manager Two");
        assertEquals("TEAM_LEADER", row(out, MGR).get("role"), "Manager Two manages two engineers");
        assertEquals("MEMBER", row(out, ENG1).get("role"), "an engineer manages nobody");
        assertEquals("MEMBER", row(out, ORPHAN).get("role"));
        assertEquals("CENTER_MANAGER", row(out, BOSS).get("role"), "the centre manager keeps their role");
        assertEquals(2, result.managersPromoted());
        assertTrue(out.stream().noneMatch(u -> "SKILL_TEAM_LEADER".equals(u.get("role"))),
                "SKILL_TEAM_LEADER is not counted by ForecastRepository; the forecast would lose these people");
    }

    @Test
    void everyOtherTableIsCopiedThroughUnchanged() {
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>();
        data.put("users", users());
        LinkedHashMap<String, Object> holiday = new LinkedHashMap<>();
        holiday.put("id", "90000000-0000-0000-0000-000000000001");
        holiday.put("date", "2026-01-01");
        holiday.put("active", true);
        data.put("holidays", new ArrayList<>(List.of(holiday)));
        ExportEnvelope input = new ExportEnvelope("workloadhub", "task_service", "2026-09-19T10:00:00", List.of("notifications"), data);

        ExportEnvelope out = ExportPreparer.prepare(input, JOINED, ExportPreparer.SEED).envelope();

        assertEquals(List.of(holiday), out.rows("holidays"));
        assertEquals("workloadhub", out.database());
        assertEquals("task_service", out.schema());
        assertEquals("2026-09-19T10:00:00", out.exportedAt());
        assertEquals(List.of("notifications"), out.excludedTables());
    }

    @Test
    void anExportWithNoUsersIsRefused() {
        ExportEnvelope empty = new ExportEnvelope("workloadhub", "task_service", null, List.of(), new LinkedHashMap<>());
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ExportPreparer.prepare(empty, JOINED, ExportPreparer.SEED));
        assertTrue(e.getMessage().contains("users"), e.getMessage());
    }

    static List<Team> derived() {
        return ExportPreparer.deriveTeams(ExportPreparer.members(users()), new HashSet<>(), new SeedRandom(ExportPreparer.SEED));
    }

    static Team named(List<Team> teams, String name) {
        return teams.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void departmentsBecomeParentlessTeamsAndManagersBecomeTheirChildren() {
        List<Team> teams = derived();
        // CT2 (five people, since "PTE / CT2" collapses to the same code), SIM (one), Unassigned (two)
        List<Team> departments = teams.stream().filter(Team::department).toList();
        assertEquals(3, departments.size(), teams.toString());
        assertTrue(departments.stream().allMatch(t -> t.parentId() == null));

        Team ct2 = named(teams, "PTE / CT2 Calibration & Testing 2");
        assertTrue(ct2.department());
        assertEquals(HEAD, ct2.managerId(), "the Skill Team Leader by job title heads the department");
        assertEquals("CT2", ct2.deptCode());

        List<Team> managerTeams = teams.stream().filter(t -> !t.department()).toList();
        assertEquals(2, managerTeams.size(), "Head One and Manager Two each have reports");
        for (Team t : managerTeams) {
            assertEquals(ct2.id(), t.parentId(), "a manager team hangs under its department team");
        }
        assertNotNull(named(teams, "CT2 · Manager Two"));
    }

    @Test
    void peopleWithNoDepartmentLandInUnassigned() {
        Team unassigned = named(derived(), "Unassigned");
        assertTrue(unassigned.department());
        assertNull(unassigned.deptCode());
        assertTrue(unassigned.memberIds().contains(LOST));
        assertTrue(unassigned.memberIds().contains(BOSS));
    }

    @Test
    void everyUserIsInAtLeastOneTeam() {
        List<Team> teams = derived();
        for (LinkedHashMap<String, Object> u : users()) {
            UUID id = UUID.fromString((String) u.get("id"));
            assertTrue(teams.stream().anyMatch(t -> t.memberIds().contains(id)),
                    u.get("full_name") + " is in no team, so ForecastRepository would not count them");
        }
    }

    @Test
    void departmentMembersAreTheHeadAndThePeopleWithNoManager() {
        Team ct2 = named(derived(), "PTE / CT2 Calibration & Testing 2");
        assertEquals(List.of(HEAD), ct2.memberIds(),
                "Manager Two and the engineers reach the department through their manager team");
    }

    @Test
    void collidingNamesGetASuffix() {
        Set<String> used = new HashSet<>();
        used.add("Unassigned");
        List<Team> teams = ExportPreparer.deriveTeams(ExportPreparer.members(users()), used, new SeedRandom(ExportPreparer.SEED));
        assertNotNull(named(teams, "Unassigned 2"), "teams.name is UNIQUE, so a taken name gets a suffix");
    }
}
