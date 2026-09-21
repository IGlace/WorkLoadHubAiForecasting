package com.workloadhub.forecastweb.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecastweb.SeededUsers;
import com.workloadhub.forecastweb.SeededUsers.Team;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The role rules of design 2026-09-11, section 3.2, on the seeded directory, each with its reason. */
class ForecastAccessTest {

    static ForecastAccess access;

    @BeforeAll
    static void setUp() {
        // Read-only (role and team lookups): the JVM-wide shared database is fine here.
        DataSource ds = SeededData.dataSource();
        access = new ForecastAccess(JdbcClient.create(ds));
    }

    @Test
    void adminRunsAndViewsAnyTeam() {
        ActingUser admin = SeededUsers.withRole("ADMIN");
        Team any = SeededUsers.notInvolving(admin.id());
        assertEquals(new ForecastAccess.Decision(true, "ADMIN may run a forecast for any team"), access.run(admin.id(), any.id()));
        assertEquals(new ForecastAccess.Decision(true, "ADMIN may view any team"), access.view(admin.id(), any.id()));
    }

    @Test
    void teamLeaderRunsTheirOwnTeamOnly() {
        ActingUser leader = SeededUsers.withRole("TEAM_LEADER");
        Team own = SeededUsers.managedBy(leader.id());
        Team other = SeededUsers.notInvolving(leader.id());
        assertEquals(new ForecastAccess.Decision(true, "TEAM_LEADER manages this team"), access.run(leader.id(), own.id()));
        assertTrue(access.canView(leader.id(), own.id()));
        assertEquals(new ForecastAccess.Decision(false, "TEAM_LEADER may only run their own team"), access.run(leader.id(), other.id()));
        assertEquals(new ForecastAccess.Decision(false, "TEAM_LEADER may only view their own team or the one they belong to"), access.view(leader.id(), other.id()));
    }

    @Test
    void skillTeamLeaderRunsTheTeamOfALeaderWhoReportsToThem() {
        ActingUser head = SeededUsers.users().stream().filter(u -> u.role().equals("SKILL_TEAM_LEADER") && SeededUsers.leadsLeaders(u.id()))
                .findFirst().orElseThrow(() -> new AssertionError("a SKILL_TEAM_LEADER with a team leader under them"));
        // A skill team leader keys no team of their own; the teams they may run are the ones beneath them.
        Team child = SeededUsers.childrenOf(head.id()).get(0);
        assertEquals(new ForecastAccess.Decision(true, "SKILL_TEAM_LEADER manages this team's leader"), access.run(head.id(), child.id()));
        assertTrue(access.canView(head.id(), child.id()));
        // Their own id keys nothing: a skill team leader leads team leaders, and that is not a team anyone runs.
        assertFalse(access.canRun(head.id(), head.id()));
        assertEquals("SKILL_TEAM_LEADER may only run the team of a leader who reports to them", access.run(head.id(), head.id()).reason());
        assertFalse(access.canView(head.id(), head.id()), "and it is not a team they can view either");
        Team other = SeededUsers.notInvolving(head.id());
        assertEquals("SKILL_TEAM_LEADER may only view the team of a leader who reports to them, or their own", access.view(head.id(), other.id()).reason());
    }

    @Test
    void memberViewsTheirOwnTeamsAndRunsNone() {
        ActingUser member = SeededUsers.withRole("MEMBER");
        UUID own = SeededUsers.teamsOf(member.id()).get(0);
        Team other = SeededUsers.notInvolving(member.id());
        assertEquals(new ForecastAccess.Decision(false, "MEMBER may not run a forecast"), access.run(member.id(), own));
        assertEquals(new ForecastAccess.Decision(true, "a member of this team"), access.view(member.id(), own));
        assertEquals(new ForecastAccess.Decision(false, "MEMBER may only view the teams they belong to"), access.view(member.id(), other.id()));
    }

    @Test
    void centerManagerViewsEverythingAndRunsNothing() {
        ActingUser cm = SeededUsers.withRole("CENTER_MANAGER");
        Team any = SeededUsers.notInvolving(cm.id());
        assertEquals(new ForecastAccess.Decision(false, "CENTER_MANAGER may not run a forecast"), access.run(cm.id(), any.id()));
        assertEquals(new ForecastAccess.Decision(true, "CENTER_MANAGER may view any team"), access.view(cm.id(), any.id()));
    }

    @Test
    void anUnknownUserIsRefused() {
        assertThrows(HostForbidden.class, () -> access.roleOf(UUID.randomUUID()));
    }
}
