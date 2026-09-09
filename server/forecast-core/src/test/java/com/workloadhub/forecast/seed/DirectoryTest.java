package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DirectoryTest {

    static final UUID HEAD = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final UUID MGR = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final UUID ENG1 = UUID.fromString("30000000-0000-0000-0000-000000000003");
    static final UUID ENG2 = UUID.fromString("30000000-0000-0000-0000-000000000004");
    static final UUID ORPHAN = UUID.fromString("30000000-0000-0000-0000-000000000005");
    static final UUID LOST = UUID.fromString("30000000-0000-0000-0000-000000000006");

    static LinkedHashMap<String, Object> user(UUID id, String name, String title, String dept, UUID manager, String role, boolean active) {
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

    static List<LinkedHashMap<String, Object>> users() {
        return List.of(
                user(HEAD, "Head One", "Skill Team Leader", "PTE / CT2 Calibration & Testing 2", null, "MEMBER", false),
                user(MGR, "Manager Two", "Team Leader Calibration", "PTE / CT2 Calibration & Testing 2", HEAD, "MEMBER", false),
                user(ENG1, "Eng Three", "Calibration Engineer", "PTE / CT2", MGR, "MEMBER", false),
                user(ENG2, "Eng Four", "Calibration Engineer", "PTE / CT2 Calibration & Testing 2", MGR, "MEMBER", false),
                user(ORPHAN, "Orphan Five", "Calibration Engineer", "PTE / CT2 Calibration & Testing 2", null, "MEMBER", false),
                user(LOST, "Lost Six", null, null, null, "MEMBER", false));
    }

    static SeedConfig cfg() {
        return new SeedConfig(20, LocalDate.of(2026, 9, 6), 42L, false, 0);
    }

    @Test
    void parsesDepartmentCodes() {
        assertEquals("CT2", Directory.deptCode("PTE / CT2 Calibration & Testing 2"));
        assertEquals("CT2", Directory.deptCode("PTE / CT2"));
        assertEquals("CSC", Directory.deptCode("PTE / CSC- CETIEV Customer Site Coordination - Cetiev"));
        assertEquals("CSC", Directory.deptCode("PTE / CSC - OEZ Customer Site Coordination OEZ"));
        assertEquals("HR", Directory.deptCode("ZEN / HR & WKP DEP HR & Workplace Services Department"));
        assertEquals("CD&I", Directory.deptCode("PTE / CD&I Component Design & Industrialization"));
        assertNull(Directory.deptCode(null));
        assertNull(Directory.deptCode("  "));
    }

    @Test
    void derivesManagerTeamsDepartmentsAndRoles() {
        Directory.Result r = Directory.derive(users(), List.of(), List.of(), cfg(), new SeedRandom(1));
        // manager teams for MGR and for HEAD (whose report is MGR), the CT2 department team, the "Unassigned" department team
        assertEquals(4, r.teams().size());
        Team mgrTeam = r.teams().stream().filter(t -> MGR.equals(t.managerId()) && !t.department()).findFirst().orElseThrow();
        assertTrue(mgrTeam.memberIds().containsAll(List.of(MGR, ENG1, ENG2)), "leader counted with reports");
        Team dept = r.teams().stream().filter(t -> t.department() && "CT2".equals(t.deptCode())).findFirst().orElseThrow();
        assertEquals(dept.id(), mgrTeam.parentId());
        assertEquals(HEAD, dept.managerId());
        assertTrue(dept.memberIds().contains(ORPHAN), "user without manager joins the department team");
        Team unassigned = r.teams().stream().filter(t -> t.department() && t.deptCode() == null).findFirst().orElseThrow();
        assertTrue(unassigned.memberIds().contains(LOST));
        assertEquals("SKILL_TEAM_LEADER", role(r, HEAD));
        assertEquals("TEAM_LEADER", role(r, MGR));
        assertEquals("MEMBER", role(r, ENG1));
        assertEquals("CT2 · Manager Two", mgrTeam.name());
    }

    @Test
    void activatesEveryoneAndKeepsExistingTeams() {
        LinkedHashMap<String, Object> existingTeam = new LinkedHashMap<>();
        existingTeam.put("id", "40000000-0000-0000-0000-000000000001");
        existingTeam.put("name", "Backend Team");
        existingTeam.put("active", true);
        existingTeam.put("version", 0L);
        existingTeam.put("manager_id", MGR.toString());
        existingTeam.put("parent_team_id", null);
        existingTeam.put("created_at", "2026-09-03T13:59:58");
        existingTeam.put("updated_at", "2026-09-03T13:59:58");
        LinkedHashMap<String, Object> existingMember = new LinkedHashMap<>();
        existingMember.put("id", "50000000-0000-0000-0000-000000000001");
        existingMember.put("team_id", "40000000-0000-0000-0000-000000000001");
        existingMember.put("user_id", ENG1.toString());
        existingMember.put("joined_at", "2026-09-03T13:59:58");
        existingMember.put("created_at", "2026-09-03T13:59:58");
        existingMember.put("updated_at", "2026-09-03T13:59:58");
        Directory.Result r = Directory.derive(users(), List.of(existingTeam), List.of(existingMember), cfg(), new SeedRandom(1));
        assertEquals(5, r.teams().size());
        assertTrue(r.teamRows().stream().anyMatch(t -> "Backend Team".equals(t.get("name"))));
        long active = r.userRows().stream().filter(u -> Boolean.TRUE.equals(u.get("active"))).count();
        assertTrue(active >= 5, "3% leave at most; got " + active);
        assertTrue(r.people().stream().allMatch(p -> !p.joined().isAfter(cfg().lastDay())));
        assertTrue(r.teamMemberRows().stream().allMatch(m -> m.get("joined_at") != null));
    }

    @Test
    void isDeterministic() {
        Directory.Result a = Directory.derive(users(), List.of(), List.of(), cfg(), new SeedRandom(7));
        Directory.Result b = Directory.derive(users(), List.of(), List.of(), cfg(), new SeedRandom(7));
        assertEquals(a.teamRows(), b.teamRows());
        assertEquals(a.teamMemberRows(), b.teamMemberRows());
    }

    static String role(Directory.Result r, UUID id) {
        return r.people().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow().role();
    }
}
