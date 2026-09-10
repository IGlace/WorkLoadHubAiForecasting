package com.workloadhub.forecast.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.ForecastMigrations;
import com.workloadhub.forecast.store.JdbcRunStore;
import com.workloadhub.forecast.testing.SeededData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class DefaultForecastServiceTest {

    static DefaultForecastService service;
    static UUID team;

    @BeforeAll
    static void boot() {
        DataSource ds = SeededData.dataSource();
        ForecastMigrations.run(ds);
        Dialect dialect = Dialect.of(ds);
        service = new DefaultForecastService(ds, dialect, new ForecastRunner(new CapacityRule(40), true), new JdbcRunStore(ds, dialect),
                new RunProgressTracker(), 1, true);
        ForecastData data = SeededData.data();
        team = data.teams().stream().filter(t -> !data.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).findFirst().orElseThrow();
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
        assertEquals("COPILOT_UNAVAILABLE", assertThrows(ForecastException.class, () -> service.copilotStatus(UUID.randomUUID())).code());
        assertEquals("COPILOT_UNAVAILABLE", assertThrows(ForecastException.class, () -> service.narrative(UUID.randomUUID(), "en")).code());
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
        DefaultForecastService svc = new DefaultForecastService(SeededData.dataSource(), dialect,
                new ForecastRunner(new CapacityRule(40), true), raw, tracker, 1, true);
        try {
            UUID id = raw.create(new RunRequest(team, null, SeededData.asOf(), null, null), LocalDateTime.now());
            raw.finish(id, "seasonal_naive", 0.9, "{}", List.of(), "{}", LocalDateTime.now());
            tracker.start(id);
            // MAX_TRACKED further, distinct runs push the id above out of the 256-entry bound.
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
