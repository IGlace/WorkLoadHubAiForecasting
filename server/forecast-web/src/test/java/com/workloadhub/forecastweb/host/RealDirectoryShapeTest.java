package com.workloadhub.forecastweb.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.store.DatabaseTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The showcase against a directory shaped like a real one rather than like the synthetic seed: no ADMIN row,
 * every {@code users.role} left at MEMBER, leaders identified only by their job title, a leader reporting to
 * another leader, and an inactive report. Every one of these was a defect the seeded fixture could not show,
 * because the seed writes roles the showcase then agreed with by accident.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealDirectoryShapeTest {

    static final UUID SKILL = UUID.fromString("70000000-0000-0000-0000-000000000001");
    static final UUID LEAD_A = UUID.fromString("70000000-0000-0000-0000-000000000002");
    static final UUID LEAD_B = UUID.fromString("70000000-0000-0000-0000-000000000003");
    static final UUID ENG_A = UUID.fromString("70000000-0000-0000-0000-000000000004");
    static final UUID ENG_B = UUID.fromString("70000000-0000-0000-0000-000000000005");
    /** Reports to LEAD_A and is a leader in their own right: a chain of leaders, which free-text titles allow. */
    static final UUID SUB_LEAD = UUID.fromString("70000000-0000-0000-0000-000000000006");
    static final UUID SUB_ENG = UUID.fromString("70000000-0000-0000-0000-000000000007");
    /** Reports to LEAD_B but has left: counted by nobody, forecast by nobody. */
    static final UUID GONE = UUID.fromString("70000000-0000-0000-0000-000000000008");

    Directory directory;
    ForecastAccess access;
    JdbcClient jdbc;

    @BeforeAll
    void directory() {
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        jdbc = JdbcClient.create(ds);
        directory = new Directory(jdbc);
        access = new ForecastAccess(jdbc);
        insert(SKILL, "Skill One", "Skill Team Leader", null, true);
        insert(LEAD_A, "Lead Ay", "Team Leader Calibration", SKILL, true);
        insert(LEAD_B, "Lead Bee", "Team Leader Design", SKILL, true);
        insert(ENG_A, "Eng Ay", "Calibration Engineer", LEAD_A, true);
        insert(ENG_B, "Eng Bee", "Design Engineer", LEAD_B, true);
        insert(SUB_LEAD, "Sub Lead", "SW Lead Engineer", LEAD_A, true);
        insert(SUB_ENG, "Sub Eng", "Software & Functions Engineer", SUB_LEAD, true);
        insert(GONE, "Gone Away", "Design Engineer", LEAD_B, false);
    }

    void insert(UUID id, String name, String jobTitle, UUID manager, boolean active) {
        String now = LocalDateTime.now().withNano(0).toString();
        jdbc.sql("INSERT INTO users (id, role, job_title, email, active, username, full_name, created_at, updated_at, manager_id)"
                        + " VALUES (?, 'MEMBER', ?, ?, ?, ?, ?, ?::timestamp, ?::timestamp, ?)")
                .param(id).param(jobTitle).param(id + "@example.test").param(active).param("u-" + id).param(name)
                .param(now).param(now).param(manager)
                .update();
    }

    @Test
    void theTeamsAreTheLeadersWithReports() {
        List<Directory.Team> teams = directory.teams();
        assertEquals(List.of(LEAD_A, LEAD_B, SUB_LEAD), teams.stream().map(Directory.Team::id).sorted(
                java.util.Comparator.comparing(UUID::toString)).toList(), "three leaders have counted reports");
        Directory.Team a = teams.stream().filter(t -> t.id().equals(LEAD_A)).findFirst().orElseThrow();
        assertEquals(3, a.memberCount(), "Lead Ay, plus Eng Ay and Sub Lead who report to them");
        Directory.Team b = teams.stream().filter(t -> t.id().equals(LEAD_B)).findFirst().orElseThrow();
        assertEquals(2, b.memberCount(), "Lead Bee and Eng Bee; the one who left is not counted");
    }

    @Test
    void aTeamNamesTheManagerItsLeaderReportsTo() {
        // The field used to be called parentTeamId and was read as a team id, so the page said "nobody" for
        // every leader whose manager is a skill team leader -- which is every leader in a real directory.
        Directory.Team a = directory.team(LEAD_A).orElseThrow();
        assertEquals(SKILL, a.reportsToId(), "the leader's own manager, whether or not they key a team");
        assertEquals("Skill One", a.reportsToName(), "and their name, so the page can print it");
        Directory.Team sub = directory.team(SUB_LEAD).orElseThrow();
        assertEquals(LEAD_A, sub.reportsToId());
        assertEquals("Lead Ay", sub.reportsToName());
    }

    @Test
    void onlyASkillTeamLeaderMayActForALeaderBeneathThem() {
        // SUB_LEAD reports to LEAD_A, who is a TEAM_LEADER, not a skill team leader: ruling 3 gives a team
        // leader one team, their own. The directory must not advertise a relation the rules refuse.
        assertTrue(access.canRun(SKILL, LEAD_A), "the skill team leader runs a team beneath them");
        assertFalse(access.canRun(LEAD_A, SUB_LEAD), "a team leader never runs the team of a leader under them");
        assertEquals("TEAM_LEADER may only run their own team", access.run(LEAD_A, SUB_LEAD).reason());
    }

    @Test
    void someoneWhoHasLeftIsNotAMemberOfTheTeamTheyReportedTo() {
        // Directory.membersOf and memberships both require a counted, active user; the view rule must agree,
        // or /api/me and /api/directory/users contradict each other for that person.
        assertFalse(directory.membersOf(LEAD_B).contains(GONE), "they are in no member list");
        assertFalse(access.canView(GONE, LEAD_B), "so they are not 'a member of this team' either");
    }

    @Test
    void aSkillTeamLeaderIsNotAMemberOfTheTeamBeneathThem() {
        // They report to nobody here, but the same rule matters for a skill team leader under a team leader:
        // they are never counted, so they are never a member.
        assertFalse(directory.membersOf(LEAD_A).contains(SKILL));
    }

    @Test
    void aSnapshotAnswersEveryQuestionFromOneRead() {
        // Every Directory method used to reload and reclassify the whole directory: one team page of the real
        // export cost five reads of 264 rows, and the run-progress poll paid one per poll. A snapshot is the
        // fix, and the read count is the guard -- it is the only thing the change is for.
        int before = directory.reads();
        Directory.Snapshot d = directory.snapshot();
        d.teams();
        d.usersById();
        d.membersOf(LEAD_A);
        d.memberships();
        assertEquals(before + 1, directory.reads(), "four questions, one read");
    }

    @Test
    void theAccessRulesCanReuseAResolvedDirectory() {
        // MeController asks run and view for every team; each call used to re-query the acting user's role and
        // then query again to ask whether the team exists. With the snapshot in hand it asks nothing.
        Directory.Snapshot d = directory.snapshot();
        int before = directory.reads();
        assertTrue(access.canRun(SKILL, LEAD_A, d));
        assertFalse(access.canRun(LEAD_A, LEAD_B, d));
        assertTrue(access.canView(ENG_A, LEAD_A, d));
        assertEquals(before, directory.reads(), "no further read, and no further query");
    }
}
