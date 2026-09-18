package com.workloadhub.forecastweb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.workloadhub.forecast.Json;
import com.workloadhub.forecast.ai.CopilotGateway;
import com.workloadhub.forecast.ai.FakeGateway;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecastweb.SeededUsers.Team;
import com.workloadhub.forecastweb.host.ActingUser;
import com.workloadhub.forecastweb.host.ActingUserException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * The whole REST surface (design 2026-09-18, section 5), on the seeded in-memory database with the scripted Copilot
 * gateway: the acting user, the role matrix, a run through to its facts, the narration, the tokens, the demo clock
 * and the accuracy that a moved clock makes possible. Ordered because the runs are expensive and later tests read them.
 */
@SpringBootTest(classes = {ForecastWebApplication.class, TestBeans.class}, properties = {
        "forecast-web.database=", "forecast-web.token-key-file=", "forecast-web.ui-dir=target/no-ui",
        "forecast-web.clock.today=2026-09-06", "whf.run-threads=1", "whf.token-key=" + TestBeans.KEY, "logging.level.root=WARN"})
// forecast-web.clock.today above is pinned to SeededData.asOf() (2026-09-06), the fixture's last day: the
// property must be a compile-time constant for the annotation, so the literal is asserted against the fixture
// in pick() below rather than interpolated.
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ForecastWebIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CopilotGateway gateway;

    @Autowired
    JdbcClient jdbc;

    static List<ActingUser> users;
    static List<Team> teams;
    static ActingUser leader;
    static Team team;
    static ActingUser member;
    static ActingUser outsider;
    static ActingUser admin;
    static UUID run;
    static JsonNode runBody;

    @BeforeAll
    static void pick() {
        assertEquals(LocalDate.of(2026, 9, 6), SeededData.asOf(), "forecast-web.clock.today above must match the fixture's last day");
        users = SeededUsers.users();
        teams = SeededUsers.teams();
        leader = SeededUsers.withRole("TEAM_LEADER");
        team = SeededUsers.managedBy(leader.id());
        List<UUID> members = SeededUsers.membersOf(team.id());
        member = users.stream().filter(u -> u.role().equals("MEMBER") && members.contains(u.id())).findFirst().orElseThrow();
        outsider = users.stream().filter(u -> u.role().equals("MEMBER") && !members.contains(u.id())
                && SeededUsers.teamsOf(u.id()).stream().noneMatch(t -> t.equals(team.id()))).findFirst().orElseThrow();
        admin = SeededUsers.withRole("ADMIN");
    }

    static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, ActingUser user) {
        return b.header(ActingUserException.HEADER, user.id().toString());
    }

    static JsonNode json(MvcResult r) throws Exception {
        return Json.mapper().readTree(r.getResponse().getContentAsString());
    }

    @Test
    @Order(1)
    void systemNeedsNoActingUserAndEverythingElseDoes() throws Exception {
        // $.dialect, $.database and $.seededAtStart are gone from SystemView (PostgreSQL is the only engine now).
        mvc.perform(get("/api/system")).andExpect(status().isOk()).andExpect(jsonPath("$.today").value("2026-09-06"))
                .andExpect(jsonPath("$.windows").value(2)).andExpect(jsonPath("$.defaultWeeklyHours").value(44.0))
                .andExpect(jsonPath("$.actingUserHeader").value("X-Acting-User"))
                .andExpect(jsonPath("$.bootstrapUserId").isEmpty());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACTING_USER_MISSING"));
        mvc.perform(get("/api/me").header(ActingUserException.HEADER, "not-a-uuid")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACTING_USER_MISSING"));
        mvc.perform(get("/api/me").header(ActingUserException.HEADER, UUID.randomUUID().toString())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACTING_USER_UNKNOWN"));
        mvc.perform(get("/api/directory/teams")).andExpect(status().isUnauthorized());
        // The module's own controller is not mounted (whf.web.enabled stays false, and WebSurfaceGuard
        // refuses to start with it on): it trusts requestedBy as given, so its routes must not exist here.
        mvc.perform(get("/api/forecast/runs/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(post("/api/forecast/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\": \"" + UUID.randomUUID() + "\", \"requestedBy\": \"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(2)
    void theDirectoryAndTheRoleMatrix() throws Exception {
        JsonNode userRows = json(mvc.perform(as(get("/api/directory/users"), member)).andExpect(status().isOk()).andReturn());
        assertEquals(users.size(), userRows.size());
        JsonNode leaderRow = null;
        for (JsonNode u : userRows) {
            if (u.path("id").asText().equals(leader.id().toString())) {
                leaderRow = u;
            }
        }
        assertTrue(leaderRow.path("teams").toString().contains("\"manages\""), "the leader's relation to the team they manage");

        JsonNode teamRows = json(mvc.perform(as(get("/api/directory/teams"), member)).andExpect(status().isOk()).andReturn());
        assertEquals(teams.size(), teamRows.size());
        mvc.perform(as(get("/api/directory/teams/" + team.id()), member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.managerName").value(leader.fullName())).andExpect(jsonPath("$.members.length()").value(SeededUsers.membersOf(team.id()).size()));
        mvc.perform(as(get("/api/directory/teams/" + UUID.randomUUID()), member)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));

        JsonNode me = json(mvc.perform(as(get("/api/me"), member)).andExpect(status().isOk()).andReturn());
        assertEquals("MEMBER", me.path("user").path("role").asText());
        assertFalse(me.path("hasToken").asBoolean());
        for (JsonNode t : me.path("teams")) {
            assertFalse(t.path("canRun").asBoolean(), "a member never runs");
            boolean own = SeededUsers.teamsOf(member.id()).contains(UUID.fromString(t.path("teamId").asText()));
            assertEquals(own, t.path("canView").asBoolean(), t.path("name").asText());
        }
        JsonNode adminMe = json(mvc.perform(as(get("/api/me"), admin)).andExpect(status().isOk()).andReturn());
        for (JsonNode t : adminMe.path("teams")) {
            assertTrue(t.path("canRun").asBoolean() && t.path("canView").asBoolean());
        }

        mvc.perform(as(get("/api/permissions?userId=" + member.id() + "&teamId=" + team.id()), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MEMBER")).andExpect(jsonPath("$.canRun").value(false)).andExpect(jsonPath("$.canView").value(true))
                .andExpect(jsonPath("$.runReason").value("MEMBER may not run a forecast")).andExpect(jsonPath("$.viewReason").value("a member of this team"));
        mvc.perform(as(get("/api/permissions?userId=" + member.id()), admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(as(get("/api/permissions?userId=" + UUID.randomUUID() + "&teamId=" + team.id()), admin)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        // A VIEWER, which the synthetic seed has none of: views everything, runs nothing.
        jdbc.sql("UPDATE users SET role = 'VIEWER' WHERE id = ?").param(outsider.id().toString()).update();
        try {
            JsonNode viewer = json(mvc.perform(as(get("/api/me"), outsider)).andExpect(status().isOk()).andReturn());
            for (JsonNode t : viewer.path("teams")) {
                assertTrue(t.path("canView").asBoolean());
                assertFalse(t.path("canRun").asBoolean());
            }
            mvc.perform(as(post("/api/teams/" + team.id() + "/forecast-runs"), outsider)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN")).andExpect(jsonPath("$.message").value("VIEWER may not run a forecast"));
        } finally {
            jdbc.sql("UPDATE users SET role = 'MEMBER' WHERE id = ?").param(outsider.id().toString()).update();
        }
        mvc.perform(as(post("/api/teams/" + team.id() + "/forecast-runs"), member)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("MEMBER may not run a forecast"));
        mvc.perform(as(post("/api/teams/" + UUID.randomUUID() + "/forecast-runs"), admin)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
    }

    @Test
    @Order(3)
    void aRunThroughProgressToItsFactsAndTheCurrentForecast() throws Exception {
        run = UUID.fromString(json(mvc.perform(as(post("/api/teams/" + team.id() + "/forecast-runs"), leader)).andExpect(status().isAccepted()).andReturn())
                .path("id").asText());
        mvc.perform(as(get("/api/forecast-runs/" + run), leader)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RUN_NOT_DONE"));
        assertEquals("DONE", waitFor(leader, run, "DONE", "FAILED"));

        runBody = json(mvc.perform(as(get("/api/forecast-runs/" + run), leader)).andExpect(status().isOk()).andReturn());
        assertEquals("DONE", runBody.path("run").path("status").asText());
        assertEquals("2026-09-06", runBody.path("run").path("asOf").asText(), "the demo clock's day");
        assertTrue(runBody.path("memberWindows").size() > 0);
        assertTrue(runBody.path("memberDays").size() > 0);
        assertTrue(runBody.path("facts").isObject(), "the facts are an object, not a string");
        assertTrue(runBody.path("facts").path("members").size() > 0);
        assertEquals(runBody.path("facts").path("members").size(), runBody.path("members").size(), "every member named");
        assertTrue(runBody.path("members").get(0).path("fullName").asText().length() > 0);

        // The member views their own team's run; an outsider does not; a run nobody started is not found.
        mvc.perform(as(get("/api/forecast-runs/" + run), member)).andExpect(status().isOk());
        mvc.perform(as(get("/api/forecast-runs/" + run), outsider)).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/forecast-runs/" + UUID.randomUUID()), leader)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));

        JsonNode current = json(mvc.perform(as(get("/api/teams/" + team.id() + "/forecast?from=2026-09-07&to=2026-09-18"), member)).andExpect(status().isOk()).andReturn());
        assertEquals(runBody.path("memberDays").size(), current.size());
        assertEquals(run.toString(), current.get(0).path("runId").asText());
        JsonNode defaults = json(mvc.perform(as(get("/api/teams/" + team.id() + "/forecast"), member)).andExpect(status().isOk()).andReturn());
        assertTrue(defaults.size() > 0, "today to today + 20 days covers the horizon");
        mvc.perform(as(get("/api/teams/" + team.id() + "/forecast?from=nope"), member)).andExpect(status().isBadRequest());

        JsonNode runs = json(mvc.perform(as(get("/api/teams/" + team.id() + "/forecast-runs?limit=5"), member)).andExpect(status().isOk()).andReturn());
        assertEquals(run.toString(), runs.get(0).path("id").asText());
        mvc.perform(as(get("/api/teams/" + team.id() + "/forecast-runs"), outsider)).andExpect(status().isForbidden());

        JsonNode accuracy = json(mvc.perform(as(get("/api/teams/" + team.id() + "/accuracy"), member)).andExpect(status().isOk()).andReturn());
        assertEquals("2026-08-09", accuracy.path("from").asText());
        assertEquals("2026-09-05", accuracy.path("to").asText());
        assertEquals(0, accuracy.path("current").size(), "nothing forecast before these days arrived");
    }

    @Test
    @Order(4)
    void tokensAndTheNarration() throws Exception {
        FakeGateway fake = (FakeGateway) gateway;
        mvc.perform(as(get("/api/me/github-token"), leader)).andExpect(status().isOk()).andExpect(jsonPath("$.hasToken").value(false));
        mvc.perform(as(post("/api/forecast-runs/" + run + "/narratives"), leader).contentType(MediaType.APPLICATION_JSON).content("{\"language\": \"en\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOKEN_MISSING"));
        mvc.perform(as(put("/api/me/github-token"), leader).contentType(MediaType.APPLICATION_JSON).content("{\"token\": \"ghp_classic\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(as(put("/api/me/github-token"), leader).contentType(MediaType.APPLICATION_JSON).content("{\"token\": \"gho_sample\"}")).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/me/github-token"), leader)).andExpect(status().isOk()).andExpect(jsonPath("$.hasToken").value(true));
        mvc.perform(as(get("/api/me"), leader)).andExpect(status().isOk()).andExpect(jsonPath("$.hasToken").value(true));
        mvc.perform(as(get("/api/me/github-token"), member)).andExpect(status().isOk()).andExpect(jsonPath("$.hasToken").value(false));
        mvc.perform(as(get("/api/me/copilot"), leader)).andExpect(status().isOk()).andExpect(jsonPath("$.hasToken").value(true))
                .andExpect(jsonPath("$.authenticated").value(true)).andExpect(jsonPath("$.login").value("sara"));
        assertEquals("gho_sample", fake.tokenSeen, "the stored token reached the gateway");

        mvc.perform(as(post("/api/forecast-runs/" + run + "/narratives"), leader).contentType(MediaType.APPLICATION_JSON).content("{\"language\": \"de\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(as(post("/api/forecast-runs/" + run + "/narratives"), leader).contentType(MediaType.APPLICATION_JSON)
                .content("{\"language\": \"en\", \"model\": \"gpt-4o; rm -rf /\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(as(get("/api/forecast-runs/" + run + "/narratives/en"), leader)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NARRATIVE_NOT_FOUND"));

        // Held in flight: a second request for the same run and language is refused.
        fake.replies.clear();
        fake.replies.add(FakeGateway.goodNarrative(runBody.path("facts")));
        fake.askStarted = new CountDownLatch(1);
        fake.askGate = new CountDownLatch(1);
        mvc.perform(as(post("/api/forecast-runs/" + run + "/narratives"), leader).contentType(MediaType.APPLICATION_JSON).content("{\"language\": \"en\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.phase").value("NARRATING")).andExpect(jsonPath("$.language").value("en"));
        assertTrue(fake.askStarted.await(30, TimeUnit.SECONDS), "the narration reached the gateway");
        mvc.perform(as(post("/api/forecast-runs/" + run + "/narratives"), leader).contentType(MediaType.APPLICATION_JSON).content("{\"language\": \"EN\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NARRATION_IN_PROGRESS"));
        mvc.perform(as(get("/api/forecast-runs/" + run + "/progress"), member)).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("NARRATING"));
        fake.askGate.countDown();
        assertEquals("NARRATED", waitFor(leader, run, "NARRATED", "NARRATION_FAILED"));
        fake.askGate = null;
        fake.askStarted = null;

        JsonNode narrative = json(mvc.perform(as(get("/api/forecast-runs/" + run + "/narratives/en"), member)).andExpect(status().isOk()).andReturn());
        assertEquals("OK", narrative.path("status").asText());
        assertTrue(narrative.path("narrative").isObject(), "the narrative is an object");
        assertEquals("All members within capacity.", narrative.path("narrative").path("run_summary").asText());
        assertTrue(narrative.path("verification").isObject());
        assertTrue(narrative.path("verification").path("checked").asInt() > 0);
        assertEquals(0, narrative.path("verification").path("unverified").size());
        assertTrue(narrative.path("toolCalls").asInt() >= 0);
        mvc.perform(as(get("/api/forecast-runs/" + run + "/narratives/fr"), member)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/forecast-runs/" + run + "/narratives/en"), outsider)).andExpect(status().isForbidden());

        mvc.perform(as(delete("/api/me/github-token"), leader)).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/me/github-token"), leader)).andExpect(status().isOk()).andExpect(jsonPath("$.hasToken").value(false));
    }

    @Test
    @Order(5)
    void theDemoClockAndTheAccuracyItMakesPossible() throws Exception {
        mvc.perform(as(post("/api/system/clock"), member).contentType(MediaType.APPLICATION_JSON).content("{\"today\": \"2026-08-24\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("only an ADMIN may move the demo clock"));
        mvc.perform(as(post("/api/system/clock"), admin).contentType(MediaType.APPLICATION_JSON).content("{\"today\": \"2026-08-24\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.today").value("2026-08-24")).andExpect(jsonPath("$.clockPinned").value(true));
        try {
            UUID earlier = UUID.fromString(json(mvc.perform(as(post("/api/teams/" + team.id() + "/forecast-runs"), leader)).andExpect(status().isAccepted()).andReturn())
                    .path("id").asText());
            assertEquals("DONE", waitFor(leader, earlier, "DONE", "FAILED"));
            mvc.perform(as(get("/api/forecast-runs/" + earlier), leader)).andExpect(status().isOk()).andExpect(jsonPath("$.run.asOf").value("2026-08-24"));

            mvc.perform(as(post("/api/system/clock"), admin).contentType(MediaType.APPLICATION_JSON).content("{\"today\": \"2026-09-06\"}")).andExpect(status().isOk());
            JsonNode accuracy = json(mvc.perform(as(get("/api/teams/" + team.id() + "/accuracy?from=2026-08-25&to=2026-09-05"), member))
                    .andExpect(status().isOk()).andReturn());
            assertTrue(accuracy.path("current").size() > 0, "weekdays forecast on 2026-08-24, before they arrived, are scored");
            boolean teamScope = false;
            for (JsonNode s : accuracy.path("scores")) {
                teamScope |= s.path("scope").asText().equals("team");
            }
            assertTrue(teamScope, "a team score");
            mvc.perform(as(get("/api/teams/" + team.id() + "/accuracy?from=2026-08-25&to=2026-09-05"), outsider)).andExpect(status().isForbidden());
        } finally {
            mvc.perform(as(post("/api/system/clock"), admin).contentType(MediaType.APPLICATION_JSON).content("{\"today\": \"2026-09-06\"}")).andExpect(status().isOk());
        }
        mvc.perform(as(post("/api/system/clock"), admin).contentType(MediaType.APPLICATION_JSON).content("{\"today\": null}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.clockPinned").value(false));
        mvc.perform(as(post("/api/system/clock"), admin).contentType(MediaType.APPLICATION_JSON).content("{\"today\": \"2026-09-06\"}")).andExpect(status().isOk());
    }

    private String waitFor(ActingUser user, UUID runId, String... phases) throws Exception {
        long deadline = System.currentTimeMillis() + 180_000;
        String phase = "";
        while (System.currentTimeMillis() < deadline) {
            phase = json(mvc.perform(as(get("/api/forecast-runs/" + runId + "/progress"), user)).andExpect(status().isOk()).andReturn()).path("phase").asText();
            for (String p : phases) {
                if (p.equals(phase)) {
                    return phase;
                }
            }
            Thread.sleep(200);
        }
        return phase;
    }
}
