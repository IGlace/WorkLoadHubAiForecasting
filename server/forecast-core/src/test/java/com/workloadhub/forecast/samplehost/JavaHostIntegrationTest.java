package com.workloadhub.forecast.samplehost;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.ai.CopilotGateway;
import com.workloadhub.forecast.ai.FakeGateway;
import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.NarrativeStatus;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.testing.SeededData;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The WorkloadHub server calling the module from its own code: the role rules, the run, the polling, the narration (design 2026-09-11). */
@SpringBootTest(classes = SampleHostApplication.class, properties = {"whf.run-threads=1", "whf.token-key=" + JavaHostIntegrationTest.KEY})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JavaHostIntegrationTest {

    static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Autowired ForecastService service;
    @Autowired GitHubTokenStore tokens;
    @Autowired CopilotGateway gateway;
    @Autowired DataSource dataSource;

    HostForecastFacade host;
    ForecastAccess access;
    JdbcClient jdbc;
    UUID team;          // a team with members whose parent team has a manager
    UUID leader;        // teams.manager_id of team
    UUID head;          // the parent team's manager: a SKILL_TEAM_LEADER
    UUID member;        // a MEMBER of team
    UUID viewer;        // a user who may read every team and run none: a VIEWER, or the CENTER_MANAGER when the seed has no VIEWER
    Optional<UUID> admin;       // any ADMIN, when the seed has one
    Optional<UUID> otherTeam;   // a team with members under a different parent, when the seed has one
    Optional<UUID> outsider;    // a MEMBER of otherTeam

    @BeforeAll
    void boot() {
        jdbc = JdbcClient.create(dataSource);
        access = new ForecastAccess(jdbc, Dialect.of(dataSource));
        host = new HostForecastFacade(service, access);
        List<Map<String, Object>> teams = jdbc.sql("SELECT t.id AS id, t.manager_id AS leader, t.parent_team_id AS parent, p.manager_id AS head"
                + " FROM teams t JOIN teams p ON p.id = t.parent_team_id"
                + " JOIN users lu ON lu.id = t.manager_id JOIN users hu ON hu.id = p.manager_id"
                + " WHERE lu.role = 'TEAM_LEADER' AND hu.role = 'SKILL_TEAM_LEADER'"
                + " AND EXISTS (SELECT 1 FROM team_members m WHERE m.team_id = t.id) ORDER BY t.id").query().listOfRows();
        assertFalse(teams.isEmpty(), "the seed has teams under a department with a head");
        Map<String, Object> first = teams.get(0);
        team = UUID.fromString(first.get("id").toString());
        leader = UUID.fromString(first.get("leader").toString());
        head = UUID.fromString(first.get("head").toString());
        assertEquals("TEAM_LEADER", role(leader));
        assertEquals("SKILL_TEAM_LEADER", role(head));
        member = SeededData.data().membersOfTeam(team).stream().map(m -> m.id()).filter(id -> role(id).equals("MEMBER")).findFirst().orElseThrow();
        viewer = userWithRole("VIEWER").or(() -> userWithRole("CENTER_MANAGER")).orElseThrow();
        admin = userWithRole("ADMIN");
        otherTeam = teams.stream().filter(t -> !t.get("head").toString().equals(head.toString())).findFirst().map(t -> UUID.fromString(t.get("id").toString()));
        outsider = otherTeam.flatMap(t -> SeededData.data().membersOfTeam(t).stream().map(m -> m.id()).filter(id -> role(id).equals("MEMBER")).findFirst());
    }

    Optional<UUID> userWithRole(String role) {
        return jdbc.sql("SELECT id FROM users WHERE role = ? ORDER BY id").param(role).query().listOfRows().stream().findFirst()
                .map(r -> UUID.fromString(r.get("id").toString()));
    }

    @AfterAll
    void shutdown() throws Exception {
        host.close();
    }

    String role(UUID userId) {
        return jdbc.sql("SELECT role FROM users WHERE id = ?").param(userId.toString()).query().listOfRows().get(0).get("role").toString();
    }

    @Test
    void theRolesDecideWhoRunsAndWhoViews() {
        assertThrows(HostForbidden.class, () -> host.startRun(member, team), "a member never runs");
        assertThrows(HostForbidden.class, () -> host.startRun(viewer, team), "a viewer never runs");
        otherTeam.ifPresent(t -> assertThrows(HostForbidden.class, () -> host.startRun(leader, t), "a leader runs only the teams they manage"));
        otherTeam.ifPresent(t -> assertThrows(HostForbidden.class, () -> host.startRun(head, t), "a skill team leader runs only the teams under them"));
        outsider.ifPresent(u -> assertThrows(HostForbidden.class, () -> host.currentForecast(u, team, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 18)),
                "a member of another team views nothing here"));
        assertDoesNotThrow(() -> host.currentForecast(viewer, team, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 18)), "a viewer may read");
        admin.ifPresent(a -> {
            assertTrue(access.canRun(a, team), "an admin runs any team");
            otherTeam.ifPresent(t -> assertTrue(access.canRun(a, t), "an admin runs any team"));
            assertDoesNotThrow(() -> host.currentForecast(a, team, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 18)), "an admin may read any team");
        });
    }

    @Test
    void aLeaderRunsTheirTeamThePagePollsTheLabelsAndReadsTheForecast() throws Exception {
        UUID run = host.startRun(leader, team);
        List<RunProgress> seen = host.waitFor(member, run, Set.of("DONE", "FAILED"), Duration.ofMinutes(2));
        RunProgress last = seen.get(seen.size() - 1);
        assertEquals("DONE", last.phase(), last.message());
        assertEquals("forecast ready", last.label().en());
        assertEquals("prévision prête", last.label().fr());
        assertTrue(seen.stream().allMatch(p -> p.label() != null && !p.label().en().isBlank() && !p.label().fr().isBlank()), "every state shown had a label");
        List<CurrentDayForecast> current = host.currentForecast(member, team, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 18));
        assertFalse(current.isEmpty(), "the member reads their own team's current forecast");
        assertTrue(current.stream().allMatch(c -> c.runId().equals(run)));
        assertEquals(service.getRun(run).memberDays().size(), current.size());
    }

    @Test
    void aSkillTeamLeaderRunsOneTeamAtATime() throws Exception {
        UUID first = host.startRun(head, team);
        assertThrows(HostForbidden.class, () -> host.startRun(head, team), "a second start while the first is in progress");
        UUID leaderRun = assertDoesNotThrow(() -> host.startRun(leader, team), "a team leader is not limited this way");
        host.waitFor(head, first, Set.of("DONE", "FAILED"), Duration.ofMinutes(2));
        host.waitFor(leader, leaderRun, Set.of("DONE", "FAILED"), Duration.ofMinutes(2));
        UUID second = host.startRun(head, team);
        host.waitFor(head, second, Set.of("DONE", "FAILED"), Duration.ofMinutes(2));
        assertEquals("DONE", host.progress(head, second).phase());
    }

    @Test
    void narrationRunsOnTheHostExecutorAndTheNarrativeIsReadableByThoseWhoMayView() throws Exception {
        UUID run = host.startRun(leader, team);
        host.waitFor(leader, run, Set.of("DONE", "FAILED"), Duration.ofMinutes(2));
        assertEquals("TOKEN_MISSING", assertThrows(ForecastException.class, () -> host.narrate(leader, run, "fr")).code());
        tokens.save(leader, "gho_host_sample");
        FakeGateway fake = (FakeGateway) gateway;
        fake.replies.clear();
        fake.replies.add(FakeGateway.goodNarrative(ExportFiles.mapper().readTree(service.getRun(run).factsJson())));
        NarrativeResult result = host.narrate(leader, run, "fr").get(2, TimeUnit.MINUTES);
        assertEquals(NarrativeStatus.OK, result.status());
        assertEquals("fr", result.language());
        RunProgress after = host.progress(leader, run);
        assertEquals("NARRATED", after.phase());
        assertEquals("rapport prêt", after.label().fr());
        assertEquals(result, host.narrative(viewer, run, "fr").orElseThrow(), "a viewer reads the narrative");
        outsider.ifPresent(u -> assertThrows(HostForbidden.class, () -> host.narrative(u, run, "fr")));
        assertTrue(host.narrative(leader, run, "en").isEmpty());
        UUID queued = host.startRun(leader, team); // the run status is checked before anything is submitted (section 3.4)
        assertEquals("RUN_NOT_DONE", assertThrows(ForecastException.class, () -> host.narrate(leader, queued, "fr")).code());
        host.waitFor(leader, queued, Set.of("DONE", "FAILED"), Duration.ofMinutes(2));
        tokens.clear(leader);
    }
}
