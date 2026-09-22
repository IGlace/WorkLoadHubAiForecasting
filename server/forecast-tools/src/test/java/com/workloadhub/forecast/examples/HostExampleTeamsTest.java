package com.workloadhub.forecast.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.tools.testing.DatabaseTestSupport;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * How the sample host finds the teams it may run. It is the file a server developer copies, so what it
 * demonstrates has to be what the module does.
 *
 * <p>The regression these hold: it used to ask SQL for {@code users.role = 'TEAM_LEADER'}. That reads as
 * correct and passes against any database {@code prepare} has written roles into — and returns **nothing**
 * against a real WorkloadHub, where the column is MEMBER for almost everyone (design 2026-09-21, section 16).
 * So every directory here leaves the column at MEMBER, exactly as the owner's does.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostExampleTeamsTest {

    static final UUID HEAD = UUID.fromString("60000000-0000-0000-0000-000000000001");
    static final UUID LEAD = UUID.fromString("60000000-0000-0000-0000-000000000002");
    static final UUID ENG = UUID.fromString("60000000-0000-0000-0000-000000000003");
    static final UUID LONELY = UUID.fromString("60000000-0000-0000-0000-000000000004");

    JdbcClient jdbc;

    @BeforeAll
    void directory() {
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        jdbc = JdbcClient.create(ds);
        // A skill team leader over a team leader over one engineer, plus a lead engineer who leads nobody.
        insert(HEAD, "Head One", "Skill Team Leader", null);
        insert(LEAD, "Lead Two", "Team Leader Calibration", HEAD);
        insert(ENG, "Eng Three", "Calibration Engineer", LEAD);
        insert(LONELY, "Solo Four", "SW Lead Engineer", HEAD);
    }

    void insert(UUID id, String name, String jobTitle, UUID manager) {
        String now = LocalDateTime.now().withNano(0).toString();
        jdbc.sql("INSERT INTO users (id, role, job_title, email, active, username, full_name, created_at, updated_at, manager_id)"
                        + " VALUES (?, 'MEMBER', ?, ?, TRUE, ?, ?, ?::timestamp, ?::timestamp, ?)")
                .param(id).param(jobTitle).param(id + "@example.test").param("u-" + id).param(name)
                .param(now).param(now).param(manager)
                .update();
    }

    @Test
    void theRoleComesFromTheTitleWhileTheColumnSaysMember() {
        Map<UUID, String> roles = HostExample.effectiveRoles(jdbc);
        assertEquals("SKILL_TEAM_LEADER", roles.get(HEAD));
        assertEquals("TEAM_LEADER", roles.get(LEAD), "their title says leader and an engineer reports to them");
        assertEquals("MEMBER", roles.get(ENG));
        assertEquals("MEMBER", roles.get(LONELY), "a lead engineer with nobody under them is a member");
        assertTrue(jdbc.sql("SELECT count(*) FROM users WHERE role <> 'MEMBER'").query(Integer.class).single() == 0,
                "the column is untouched: this is the shape a real export arrives in");
    }

    @Test
    void onlyATeamLeaderWithReportsKeysATeam() {
        assertEquals(LEAD, HostExample.leaderOf(jdbc, LEAD), "the team is its leader, so the id is the leader's own");
        assertNull(HostExample.leaderOf(jdbc, HEAD), "a skill team leader keys no team of their own");
        assertNull(HostExample.leaderOf(jdbc, ENG), "and neither does a member");
        assertNull(HostExample.leaderOf(jdbc, LONELY), "nor a leader by title whom nobody reports to");
        assertNull(HostExample.leaderOf(jdbc, UUID.randomUUID()), "nor a user who does not exist");
    }
}
