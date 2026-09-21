package com.workloadhub.forecast.tools.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.tools.export.ExportEnvelope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What {@code prepare} does to a real export, now that the forecast reads the hierarchy directly: it makes
 * every user active and writes each one's effective role — the one their job title gives them, which is the
 * role the module derives for itself — back into {@code users.role}. It decides nothing of its own. It
 * derives no teams either — that was the 2026-09-19 design, withdrawn on 2026-09-21 — so `teams` and
 * `team_members` must come through untouched.
 */
class ExportPreparerTest {

    static final UUID HEAD = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final UUID MGR = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final UUID ENG1 = UUID.fromString("30000000-0000-0000-0000-000000000003");
    static final UUID ENG2 = UUID.fromString("30000000-0000-0000-0000-000000000004");
    static final UUID ORPHAN = UUID.fromString("30000000-0000-0000-0000-000000000005");
    static final UUID LOST = UUID.fromString("30000000-0000-0000-0000-000000000006");
    static final UUID BOSS = UUID.fromString("30000000-0000-0000-0000-000000000007");

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

    /** Seven people: a head over a manager over two engineers, plus two loose people and a centre manager. */
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
        return ExportPreparer.prepare(envelope(users(), new ArrayList<>(), new ArrayList<>()));
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
    void theTitleDecidesTheRoleAndALeaderWithReportsKeepsIt() {
        ExportPreparer.Result result = prepared();
        List<LinkedHashMap<String, Object>> out = result.envelope().rows("users");
        assertEquals("SKILL_TEAM_LEADER", row(out, HEAD).get("role"), "Head One's title says skill team leader");
        assertEquals("TEAM_LEADER", row(out, MGR).get("role"), "Manager Two leads, and two engineers report to them");
        assertEquals("MEMBER", row(out, ENG1).get("role"), "an engineer manages nobody");
        assertEquals("MEMBER", row(out, ORPHAN).get("role"));
        assertEquals("MEMBER", row(out, LOST).get("role"));
        assertEquals("CENTER_MANAGER", row(out, BOSS).get("role"), "the centre manager keeps their role");
        assertEquals(1, result.teamLeaders(), "Manager Two, and nobody else leads a team");
        assertEquals(1, result.skillTeamLeaders(), "Head One");
        assertEquals(5, result.countedMembers(), "everyone but Head One and Boss Seven");
    }

    @Test
    void aLeaderNobodyReportsToComesOutAMember() {
        // The owner's ruling of 2026-09-21: a team leader or lead engineer with nobody under them does the
        // work of a member and is forecast as one.
        List<LinkedHashMap<String, Object>> users = users();
        users.set(2, user(ENG1, "Eng Three", "SW Lead Engineer", "PTE / CT2", MGR, "MEMBER", false));
        users.set(4, user(ORPHAN, "Orphan Five", "Lead Engineer Battery", "PTE / SIM Simulation", null, "MEMBER", true));
        ExportPreparer.Result result = ExportPreparer.prepare(envelope(users, new ArrayList<>(), new ArrayList<>()));
        List<LinkedHashMap<String, Object>> out = result.envelope().rows("users");
        assertEquals("MEMBER", row(out, ENG1).get("role"), "a lead engineer with no reports leads nothing");
        assertEquals("MEMBER", row(out, ORPHAN).get("role"), "and so does one with no manager either");
        assertEquals(1, result.teamLeaders(), "still only Manager Two");
    }

    @Test
    void aLeaderRoleInTheColumnIsNotBelieved() {
        // WorkloadHub's own role column says MEMBER for 260 of the owner's 264 people and TEAM_LEADER for two
        // accounts that lead nobody, so only the title is evidence. ADMIN and CENTER_MANAGER are different:
        // no title implies them, and ProjectPlanner.fallbackOwner looks for exactly those two.
        List<LinkedHashMap<String, Object>> users = users();
        users.set(4, user(ORPHAN, "Orphan Five", "Simulation Engineer", "PTE / SIM Simulation", null, "SKILL_TEAM_LEADER", true));
        ExportPreparer.Result result = ExportPreparer.prepare(envelope(users, new ArrayList<>(), new ArrayList<>()));
        assertEquals("MEMBER", row(result.envelope().rows("users"), ORPHAN).get("role"), "a simulation engineer, whatever the column says");
        assertEquals("CENTER_MANAGER", row(result.envelope().rows("users"), BOSS).get("role"), "but this one is kept");
        assertEquals(1, result.skillTeamLeaders(), "Head One, whose title says so");
    }

    @Test
    void preparingATwicePreparedExportChangesNothing() {
        ExportEnvelope once = prepared().envelope();
        assertEquals(once.rows("users"), ExportPreparer.prepare(once).envelope().rows("users"));
    }

    @Test
    void aManagerWhoIsNotInTheExportGrantsNobodyAnything() {
        UUID absent = UUID.fromString("90000000-0000-0000-0000-000000000001");
        List<LinkedHashMap<String, Object>> users = new ArrayList<>(List.of(
                user(ENG1, "Eng Three", "Calibration Engineer", "PTE / CT2", absent, "MEMBER", false)));
        ExportPreparer.Result result = ExportPreparer.prepare(envelope(users, new ArrayList<>(), new ArrayList<>()));
        assertEquals("MEMBER", row(result.envelope().rows("users"), ENG1).get("role"));
        assertEquals(0, result.teamLeaders());
        assertEquals(0, result.skillTeamLeaders());
    }

    @Test
    void teamsAndTeamMembersAreCopiedThroughUntouched() {
        LinkedHashMap<String, Object> team = new LinkedHashMap<>();
        team.put("id", UUID.fromString("40000000-0000-0000-0000-000000000001").toString());
        team.put("name", "Backend Team");
        team.put("manager_id", MGR.toString());
        team.put("parent_team_id", null);
        LinkedHashMap<String, Object> membership = new LinkedHashMap<>();
        membership.put("id", UUID.fromString("50000000-0000-0000-0000-000000000001").toString());
        membership.put("team_id", team.get("id"));
        membership.put("user_id", ENG1.toString());
        List<LinkedHashMap<String, Object>> teams = new ArrayList<>(List.of(team));
        List<LinkedHashMap<String, Object>> members = new ArrayList<>(List.of(membership));

        ExportPreparer.Result result = ExportPreparer.prepare(envelope(users(), teams, members));

        // Project teams are the application's own and none of this step's business (design 2026-09-21).
        assertSame(teams, result.envelope().rows("teams"));
        assertSame(members, result.envelope().rows("team_members"));
        assertEquals(List.of(team), result.envelope().rows("teams"));
        assertEquals(List.of(membership), result.envelope().rows("team_members"));
    }

    @Test
    void theEnvelopeMetadataSurvives() {
        ExportEnvelope out = prepared().envelope();
        assertEquals("workloadhub", out.database());
        assertEquals("task_service", out.schema());
        assertEquals("2026-09-19T10:00:00", out.exportedAt());
        assertTrue(out.excludedTables().isEmpty());
    }

    @Test
    void anExportWithNoUsersIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ExportPreparer.prepare(envelope(new ArrayList<>(), new ArrayList<>(), new ArrayList<>())));
        assertTrue(e.getMessage().contains("no users"));
    }

    @Test
    void twoRunsOverOneExportAgree() {
        assertEquals(prepared().envelope().rows("users"), prepared().envelope().rows("users"));
    }
}
