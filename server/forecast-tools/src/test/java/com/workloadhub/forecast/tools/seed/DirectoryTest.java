package com.workloadhub.forecast.tools.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        return new SeedConfig(20, LocalDate.of(2026, 9, 6), 42L, true, 0);
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
    void derivesDepartmentsAndTheTwoLeaderRoles() {
        Directory.Result r = Directory.derive(users(), cfg(), new SeedRandom(1));
        // The CT2 department, and the "" one for the person with no department at all.
        assertEquals(2, r.departments().size());
        Department ct2 = r.departments().stream().filter(d -> "CT2".equals(d.code())).findFirst().orElseThrow();
        assertEquals(HEAD, ct2.headId(), "the head is the skill team leader of the department");
        assertTrue(ct2.memberIds().containsAll(List.of(HEAD, MGR, ENG1, ENG2, ORPHAN)));
        Department unassigned = r.departments().stream().filter(d -> d.code().isEmpty()).findFirst().orElseThrow();
        assertEquals("Unassigned", unassigned.label());
        assertTrue(unassigned.memberIds().contains(LOST));

        // Head One manages Manager Two, who manages people, so Head One does no technical work and is not counted.
        assertEquals("SKILL_TEAM_LEADER", role(r, HEAD));
        assertEquals("TEAM_LEADER", role(r, MGR));
        assertEquals("MEMBER", role(r, ENG1));
        assertEquals("MEMBER", role(r, ORPHAN), "managing nobody is not a role change");
    }

    @Test
    void aTeamIsAManagerAndTheirDirectReports() {
        Directory.Result r = Directory.derive(users(), cfg(), new SeedRandom(1));
        Map<UUID, Person> people = new java.util.HashMap<>();
        r.people().forEach(p -> people.put(p.id(), p));
        assertEquals(MGR, Directory.teamKey(people.get(ENG1), people), "an engineer is forecast in their manager's team");
        assertEquals(MGR, Directory.teamKey(people.get(ENG2), people));
        assertEquals(HEAD, Directory.teamKey(people.get(MGR), people), "a leader is also a member of their own manager's team");
        assertEquals(ORPHAN, Directory.teamKey(people.get(ORPHAN), people), "nobody above them, so they key their own");
    }

    @Test
    void writesNoTeamRowsAtAll() {
        // teams and team_members hold project teams; ProjectPlanner writes those, from the work it plans.
        Directory.Result r = Directory.derive(users(), cfg(), new SeedRandom(1));
        assertEquals(3, Directory.Result.class.getRecordComponents().length,
                "people, departments and userRows: no team rows on this result");
        long active = r.userRows().stream().filter(u -> Boolean.TRUE.equals(u.get("active"))).count();
        assertTrue(active >= 5, "3% leave at most; got " + active);
        assertTrue(r.people().stream().allMatch(p -> !p.joined().isAfter(cfg().lastDay())));
    }

    @Test
    void realModeLeavesTheUserRowsAlone() {
        SeedConfig real = new SeedConfig(20, LocalDate.of(2026, 9, 6), 42L, false, 0);
        Directory.Result r = Directory.derive(users(), real, new SeedRandom(1));
        assertEquals(users(), r.userRows(), "real mode writes the five work tables and nothing else");
        assertTrue(r.departments().size() >= 1, "the structure is still derived; the work is shaped by it");
        assertEquals("SKILL_TEAM_LEADER", role(r, HEAD), "and the roles it implies are still known");
    }

    @Test
    void isDeterministic() {
        Directory.Result a = Directory.derive(users(), cfg(), new SeedRandom(7));
        Directory.Result b = Directory.derive(users(), cfg(), new SeedRandom(7));
        assertEquals(a.userRows(), b.userRows());
        assertEquals(a.departments(), b.departments());
    }

    static String role(Directory.Result r, UUID id) {
        return r.people().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow().role();
    }
}
