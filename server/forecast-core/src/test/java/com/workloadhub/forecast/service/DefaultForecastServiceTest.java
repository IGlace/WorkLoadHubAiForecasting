package com.workloadhub.forecast.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.ai.FakeGateway;
import com.workloadhub.forecast.ai.Narrator;
import com.workloadhub.forecast.ai.Prompts;
import com.workloadhub.forecast.api.CopilotStatus;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.NarrativeRequest;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.NarrativeStatus;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.store.AesGcmCipher;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.ForecastMigrations;
import com.workloadhub.forecast.store.JdbcGitHubTokenStore;
import com.workloadhub.forecast.store.JdbcNarrativeStore;
import com.workloadhub.forecast.store.JdbcRunStore;
import com.workloadhub.forecast.testing.SeededData;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class DefaultForecastServiceTest {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static DefaultForecastService service;
    static FakeGateway gateway;
    static JdbcGitHubTokenStore tokens;
    static UUID team;
    static UUID member;

    static DefaultForecastService build(DataSource ds, FakeGateway g, RunProgressTracker tracker, JdbcRunStore runs) {
        Dialect dialect = Dialect.of(ds);
        JdbcGitHubTokenStore t = new JdbcGitHubTokenStore(JdbcClient.create(ds), dialect, AesGcmCipher.fromBase64Key(KEY));
        return new DefaultForecastService(ds, dialect, new ForecastRunner(new CapacityRule(40), true), runs, tracker, 1, true, t,
                new JdbcNarrativeStore(ds, dialect), new Narrator(g, Prompts.load(), Duration.ofSeconds(5), ""), g);
    }

    @BeforeAll
    static void boot() {
        DataSource ds = SeededData.dataSource();
        ForecastMigrations.run(ds);
        Dialect dialect = Dialect.of(ds);
        gateway = new FakeGateway();
        tokens = new JdbcGitHubTokenStore(JdbcClient.create(ds), dialect, AesGcmCipher.fromBase64Key(KEY));
        service = build(ds, gateway, new RunProgressTracker(), new JdbcRunStore(ds, dialect));
        ForecastData data = SeededData.data();
        team = data.teams().stream().filter(t -> !data.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).findFirst().orElseThrow();
        member = data.membersOfTeam(team).get(0).id();
    }

    @AfterAll
    static void stop() throws Exception {
        service.close();
    }

    @Test
    void runNowPersistsAndReturnsTheWholeResult() {
        RunResult r = service.runNow(new RunRequest(team, null, SeededData.asOf(), null, null));
        assertEquals(RunStatus.DONE, r.run().status());
        assertFalse(r.memberWeeks().isEmpty());
        assertTrue(r.factsJson().startsWith("{"));
        assertTrue(r.maseByModel().containsKey("seasonal_naive"));
        assertFalse(r.scores().isEmpty());
        RunResult again = service.getRun(r.run().id());
        assertEquals(r.memberWeeks(), again.memberWeeks());
        assertEquals(r.factsJson(), again.factsJson());
        assertEquals(r.scores(), again.scores());
        assertEquals(100, service.progress(r.run().id()).percent());
        assertEquals(r.run().id(), service.listRuns(team, 5).get(0).id());
    }

    @Test
    void startRunReturnsImmediatelyAndFinishesInTheBackground() throws Exception {
        UUID id = service.startRun(new RunRequest(team, null, SeededData.asOf(), "seasonal_naive", false));
        RunProgress first = service.progress(id);
        assertTrue(List.of("QUEUED", "LOADING", "FEATURES", "BACKTEST", "FORECAST", "FACTS", "PERSIST", "DONE").contains(first.phase()));
        long deadline = System.currentTimeMillis() + 120_000;
        while (service.progress(id).percent() < 100 && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertEquals("DONE", service.progress(id).phase());
        RunResult r = service.getRun(id);
        assertEquals("seasonal_naive", r.run().championModel());
        assertTrue(r.memberWeeks().stream().allMatch(w -> w.plannedHrs() == 0.0));
    }

    @Test
    void errorsCarryTheSpecCodes() {
        assertEquals("TEAM_NOT_FOUND", assertThrows(ForecastException.class,
                () -> service.startRun(new RunRequest(UUID.randomUUID(), null, SeededData.asOf(), null, null))).code());
        assertEquals("RUN_NOT_FOUND", assertThrows(ForecastException.class, () -> service.getRun(UUID.randomUUID())).code());
        assertEquals("RUN_NOT_FOUND", assertThrows(ForecastException.class, () -> service.progress(UUID.randomUUID())).code());
        assertEquals("RUN_NOT_FOUND", assertThrows(ForecastException.class, () -> service.narrative(UUID.randomUUID(), "en")).code());
        assertEquals("RUN_NOT_FOUND", assertThrows(ForecastException.class,
                () -> service.narrate(new NarrativeRequest(UUID.randomUUID(), member, "en", null))).code());
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class,
                () -> service.narrate(new NarrativeRequest(UUID.randomUUID(), member, "de", null))).code());
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class,
                () -> service.narrate(new NarrativeRequest(UUID.randomUUID(), null, "en", null))).code());
        assertFalse(service.copilotStatus(UUID.randomUUID()).hasToken(), "an unknown user has no token");
    }

    @Test
    void narrateStoresAndReturnsTheOutcomeAndTracksProgress() {
        RunResult r = service.runNow(new RunRequest(team, member, SeededData.asOf(), "seasonal_naive", null));
        tokens.save(member, "gho_test_token");
        gateway.replies.clear();
        gateway.replies.add(FakeGateway.goodNarrative(ExportFiles.mapper().readTree(r.factsJson())));
        NarrativeResult result = service.narrate(new NarrativeRequest(r.run().id(), member, "EN", null));
        assertEquals(NarrativeStatus.OK, result.status());
        assertEquals("en", result.language());
        assertEquals(r.run().id(), result.runId());
        assertEquals("gpt-5", result.model());
        assertEquals("gho_test_token", gateway.tokenSeen);
        assertTrue(result.narrativeJson().contains("All members within capacity."));
        assertEquals(result, service.narrative(r.run().id(), "en").orElseThrow());
        assertTrue(service.narrative(r.run().id(), "fr").isEmpty());
        RunProgress p = service.progress(r.run().id());
        assertEquals("NARRATED", p.phase());
        assertEquals(100, p.percent());
        assertNotNull(p.answer());
    }

    @Test
    void aFailedNarrationIsStoredAndReturnedNotThrown() {
        RunResult r = service.runNow(new RunRequest(team, member, SeededData.asOf(), "seasonal_naive", null));
        tokens.save(member, "gho_test_token");
        gateway.replies.clear();
        gateway.replies.addAll(List.of("nope", "still nope"));
        NarrativeResult result = service.narrate(new NarrativeRequest(r.run().id(), member, "fr", "gpt-5-mini"));
        assertEquals(NarrativeStatus.FAILED, result.status());
        assertTrue(result.error().startsWith("invalid_output:"));
        assertEquals("still nope", result.rawText());
        assertNull(result.narrativeJson());
        assertEquals(result, service.narrative(r.run().id(), "fr").orElseThrow());
        assertEquals("NARRATION_FAILED", service.progress(r.run().id()).phase());
        assertEquals("gpt-5-mini", gateway.spec.model());
    }

    @Test
    void narrateWithoutATokenThrowsTokenMissingAndStoresNothing() {
        RunResult r = service.runNow(new RunRequest(team, member, SeededData.asOf(), "seasonal_naive", null));
        tokens.clear(member);
        gateway.replies.clear();
        ForecastException e = assertThrows(ForecastException.class, () -> service.narrate(new NarrativeRequest(r.run().id(), member, "en", null)));
        assertEquals("TOKEN_MISSING", e.code());
        assertTrue(service.narrative(r.run().id(), "en").isEmpty());
        assertFalse(gateway.opened, "no client is started without a token");
    }

    @Test
    void narrateRefusesARunThatIsNotDone() {
        DataSource ds = SeededData.dataSource();
        JdbcRunStore raw = new JdbcRunStore(ds, Dialect.of(ds));
        UUID queued = raw.create(new RunRequest(team, member, SeededData.asOf(), null, null), LocalDateTime.now());
        tokens.save(member, "gho_test_token");
        assertEquals("RUN_NOT_DONE", assertThrows(ForecastException.class, () -> service.narrate(new NarrativeRequest(queued, member, "en", null))).code());
    }

    @Test
    void aRejectedTokenIsThrownAndProgressSaysSo() {
        RunResult r = service.runNow(new RunRequest(team, member, SeededData.asOf(), "seasonal_naive", null));
        tokens.save(member, "gho_test_token");
        FakeGateway g = new FakeGateway();
        g.authenticated = false;
        RunProgressTracker tracker = new RunProgressTracker();
        DataSource ds = SeededData.dataSource();
        DefaultForecastService svc = build(ds, g, tracker, new JdbcRunStore(ds, Dialect.of(ds)));
        try {
            assertEquals("TOKEN_REJECTED", assertThrows(ForecastException.class, () -> svc.narrate(new NarrativeRequest(r.run().id(), member, "en", null))).code());
            assertEquals("NARRATION_FAILED", tracker.get(r.run().id()).orElseThrow().phase());
            assertTrue(tracker.get(r.run().id()).orElseThrow().message().startsWith("TOKEN_REJECTED"));
        } finally {
            try {
                svc.close();
            } catch (Exception ignored) {
                // test teardown
            }
        }
    }

    @Test
    void copilotStatusWithoutATokenDoesNotStartAClient() {
        tokens.clear(member);
        gateway.opened = false;
        CopilotStatus s = service.copilotStatus(member);
        assertFalse(s.hasToken());
        assertTrue(s.runtimeAvailable());
        assertEquals("1.0.13-preview.6", s.runtimeVersion());
        assertNull(s.authenticated());
        assertTrue(s.message().contains("token"));
        assertFalse(gateway.opened);
    }

    @Test
    void copilotStatusWithATokenReportsTheLoginAndTheQuota() {
        tokens.save(member, "gho_test_token");
        gateway.quota = Map.of("premium_interactions", Map.of("used", 12, "entitlement", 300));
        try {
            CopilotStatus s = service.copilotStatus(member);
            assertTrue(s.hasToken() && s.runtimeAvailable());
            assertEquals(Boolean.TRUE, s.authenticated());
            assertEquals("sara", s.login());
            assertTrue(s.quotaJson().contains("premium_interactions"));
            assertTrue(s.message().contains("sara"));
            assertTrue(gateway.closed);
            gateway.authenticated = false;
            CopilotStatus rejected = service.copilotStatus(member);
            assertEquals(Boolean.FALSE, rejected.authenticated());
            assertNull(rejected.quotaJson());
        } finally {
            // the gateway is shared with every other case in this class: restore it even when an assertion fails
            gateway.authenticated = true;
            gateway.quota = null;
        }
    }

    @Test
    void getRunPreservesAStoredNullMaseAsNullNotNaN() {
        Dialect dialect = Dialect.of(SeededData.dataSource());
        JdbcRunStore raw = new JdbcRunStore(SeededData.dataSource(), dialect);
        UUID id = raw.create(new RunRequest(team, null, SeededData.asOf(), null, null), LocalDateTime.now());
        String backtest = "{\"scores\":[{\"model\":\"xgboost\",\"origin\":\"2026-08-24\",\"horizon\":1,\"mae\":null,\"mase\":null}],"
                + "\"mase_by_model\":{\"xgboost\":null},\"unavailable\":{}}";
        raw.finish(id, "seasonal_naive", Double.NaN, backtest, List.of(), "{}", LocalDateTime.now());
        RunResult r = service.getRun(id);
        assertNull(r.scores().get(0).mase(), "stored null mase stays null, not NaN");
        assertNull(r.scores().get(0).mae());
        assertTrue(r.maseByModel().containsKey("xgboost"));
        assertNull(r.maseByModel().get("xgboost"), "stored null mase_by_model entry stays null, not NaN");
    }

    @Test
    void progressOfARunEvictedFromTheTrackerFallsBackToTheStoredRow() throws Exception {
        Dialect dialect = Dialect.of(SeededData.dataSource());
        JdbcRunStore raw = new JdbcRunStore(SeededData.dataSource(), dialect);
        RunProgressTracker tracker = new RunProgressTracker();
        DefaultForecastService svc = build(SeededData.dataSource(), new FakeGateway(), tracker, raw);
        try {
            UUID id = raw.create(new RunRequest(team, null, SeededData.asOf(), null, null), LocalDateTime.now());
            raw.finish(id, "seasonal_naive", 0.9, "{}", List.of(), "{}", LocalDateTime.now());
            tracker.start(id);
            for (int i = 0; i < RunProgressTracker.MAX_TRACKED; i++) {
                tracker.start(UUID.randomUUID());
            }
            assertTrue(tracker.get(id).isEmpty(), "the run's tracker entry was evicted by the later starts");
            RunProgress progress = svc.progress(id);
            assertEquals("DONE", progress.phase());
            assertEquals(100, progress.percent());

            UUID failedId = raw.create(new RunRequest(team, null, SeededData.asOf(), null, null), LocalDateTime.now());
            raw.fail(failedId, "boom", LocalDateTime.now());
            tracker.start(failedId);
            for (int i = 0; i < RunProgressTracker.MAX_TRACKED; i++) {
                tracker.start(UUID.randomUUID());
            }
            assertTrue(tracker.get(failedId).isEmpty());
            RunProgress failedProgress = svc.progress(failedId);
            assertEquals("FAILED", failedProgress.phase());
            assertEquals(100, failedProgress.percent());
            assertEquals("boom", failedProgress.message());
        } finally {
            svc.close();
        }
    }

    @Test
    void aFailedRunIsRecordedNotSwallowed() {
        UUID emptyTeam = SeededData.data().teams().stream().filter(t -> SeededData.data().membersOfTeam(t.id()).isEmpty()).map(TeamRow::id)
                .findFirst().orElseGet(DefaultForecastServiceTest::insertEmptyTeam);
        ForecastException ex = assertThrows(ForecastException.class,
                () -> service.runNow(new RunRequest(emptyTeam, null, LocalDate.of(2026, 9, 6), null, null)));
        assertEquals("TEAM_NOT_FOUND", ex.code());
        assertEquals(RunStatus.FAILED, service.listRuns(emptyTeam, 1).get(0).status());
        assertEquals("RUN_NOT_DONE", assertThrows(ForecastException.class, () -> service.getRun(service.listRuns(emptyTeam, 1).get(0).id())).code());
    }

    /** The seed is expected to carry a member-less "Unassigned" team; this is the fallback if it ever doesn't. */
    private static UUID insertEmptyTeam() {
        UUID id = UUID.randomUUID();
        DataSource ds = SeededData.dataSource();
        String now = LocalDateTime.now().withNano(0).toString();
        JdbcClient.create(ds)
                .sql("INSERT INTO teams (active, created_at, updated_at, version, id, manager_id, parent_team_id, name)"
                        + " VALUES (1, ?, ?, 1, ?, NULL, NULL, ?)")
                .param(now).param(now).param(id.toString()).param("Empty Team " + id)
                .update();
        return id;
    }
}
