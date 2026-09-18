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
import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.AccuracyScore;
import com.workloadhub.forecast.api.CopilotStatus;
import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.NarrativeRequest;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.NarrativeStatus;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.api.RunSummary;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.Json;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.eval.Truth;
import com.workloadhub.forecast.features.MemberDay;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.store.AesGcmCipher;
import com.workloadhub.forecast.store.ForecastMigrations;
import com.workloadhub.forecast.store.JdbcGitHubTokenStore;
import com.workloadhub.forecast.store.JdbcNarrativeStore;
import com.workloadhub.forecast.store.JdbcRunStore;
import com.workloadhub.forecast.testing.SeededData;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
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
        JdbcGitHubTokenStore t = new JdbcGitHubTokenStore(JdbcClient.create(ds), AesGcmCipher.fromBase64Key(KEY));
        return new DefaultForecastService(ds, new ForecastRunner(new CapacityRule(40), 2), runs, tracker, 1, t,
                new JdbcNarrativeStore(ds), new Narrator(g, Prompts.load(), Duration.ofSeconds(5), ""), g,
                Clock.fixed(SeededData.asOf().atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    @BeforeAll
    static void boot() {
        DataSource ds = SeededData.dataSource();
        ForecastMigrations.run(ds);
        gateway = new FakeGateway();
        tokens = new JdbcGitHubTokenStore(JdbcClient.create(ds), AesGcmCipher.fromBase64Key(KEY));
        service = build(ds, gateway, new RunProgressTracker(), new JdbcRunStore(ds));
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
        RunResult r = service.runNow(new RunRequest(team, null));
        assertEquals(RunStatus.DONE, r.run().status());
        assertFalse(r.memberWindows().isEmpty());
        assertEquals(r.memberWindows().size() * 5, r.memberDays().size());
        assertTrue(r.factsJson().startsWith("{"));
        assertFalse(r.scores().isEmpty());
        RunResult again = service.getRun(r.run().id());
        assertEquals(r.memberWindows(), again.memberWindows());
        assertEquals(r.factsJson(), again.factsJson());
        assertEquals(r.scores(), again.scores());
        assertEquals(100, service.progress(r.run().id()).percent());
        assertEquals(r.run().id(), service.listRuns(team, 5).get(0).id());
    }

    @Test
    void theRunDayComesFromTheClockUnlessAnExperimentOverridesIt() {
        RunResult r = service.runNow(new RunRequest(team, null));
        assertEquals(SeededData.asOf(), r.run().asOf(), "the fixed clock's day");
        assertEquals(LocalDate.of(2026, 9, 7), r.memberWindows().get(0).windowStart());
        RunResult wednesday = service.runNow(new RunRequest(team, null), LocalDate.of(2026, 9, 2));
        assertEquals(LocalDate.of(2026, 9, 2), wednesday.run().asOf());
        assertEquals(LocalDate.of(2026, 9, 3), wednesday.memberWindows().get(0).windowStart());
    }

    @Test
    void theCurrentForecastIsTheLatestRunsDays() {
        RunResult r = service.runNow(new RunRequest(team, null));
        List<CurrentDayForecast> current = service.currentForecast(team, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 18));
        assertEquals(r.memberDays().size(), current.size());
        assertTrue(current.stream().allMatch(c -> c.runId().equals(r.run().id())), "the newest run wins every day it covers");
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class,
                () -> service.currentForecast(team, LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 7))).code());
        assertEquals("TEAM_NOT_FOUND", assertThrows(ForecastException.class,
                () -> service.currentForecast(UUID.randomUUID(), LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 18))).code());
    }

    @Test
    void startRunReturnsImmediatelyAndFinishesInTheBackground() throws Exception {
        UUID id = service.startRun(new RunRequest(team, null));
        RunProgress first = service.progress(id);
        assertTrue(List.of("QUEUED", "LOADING", "FEATURES", "BACKTEST", "FORECAST", "FACTS", "PERSIST", "DONE").contains(first.phase()));
        long deadline = System.currentTimeMillis() + 120_000;
        while (service.progress(id).percent() < 100 && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertEquals("DONE", service.progress(id).phase());
        RunResult r = service.getRun(id);
        assertEquals(RunStatus.DONE, r.run().status());
        assertFalse(r.memberWindows().isEmpty());
    }

    @Test
    void errorsCarryTheSpecCodes() {
        assertEquals("TEAM_NOT_FOUND", assertThrows(ForecastException.class,
                () -> service.startRun(new RunRequest(UUID.randomUUID(), null))).code());
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
        RunResult r = service.runNow(new RunRequest(team, member));
        tokens.save(member, "gho_test_token");
        gateway.replies.clear();
        gateway.replies.add(FakeGateway.goodNarrative(Json.mapper().readTree(r.factsJson())));
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
        assertEquals("report ready", p.label().en());
        assertEquals("rapport prêt", p.label().fr());
    }

    @Test
    void aFailedNarrationIsStoredAndReturnedNotThrown() {
        RunResult r = service.runNow(new RunRequest(team, member));
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
        RunResult r = service.runNow(new RunRequest(team, member));
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
        JdbcRunStore raw = new JdbcRunStore(ds);
        UUID queued = raw.create(new RunRequest(team, member), SeededData.asOf(), LocalDateTime.now());
        tokens.save(member, "gho_test_token");
        assertEquals("RUN_NOT_DONE", assertThrows(ForecastException.class, () -> service.narrate(new NarrativeRequest(queued, member, "en", null))).code());
    }

    @Test
    void aRejectedTokenIsThrownAndProgressSaysSo() {
        RunResult r = service.runNow(new RunRequest(team, member));
        tokens.save(member, "gho_test_token");
        FakeGateway g = new FakeGateway();
        g.authenticated = false;
        RunProgressTracker tracker = new RunProgressTracker();
        DataSource ds = SeededData.dataSource();
        DefaultForecastService svc = build(ds, g, tracker, new JdbcRunStore(ds));
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
    void getRunPreservesAStoredNullMaeAsNullNotNaN() {
        JdbcRunStore raw = new JdbcRunStore(SeededData.dataSource());
        UUID id = raw.create(new RunRequest(team, null), SeededData.asOf(), LocalDateTime.now());
        String backtest = "{\"scores\":[{\"origin\":\"2026-08-24\",\"horizon\":1,\"mae\":null}],\"mean_mae\":null}";
        raw.finish(id, Double.NaN, backtest, List.of(), List.of(), "{}", LocalDateTime.now());
        RunResult r = service.getRun(id);
        assertNull(r.scores().get(0).mae(), "stored null mae stays null, not NaN");
        assertNull(r.run().mae(), "NaN is stored as null");
    }

    @Test
    void progressOfARunEvictedFromTheTrackerFallsBackToTheStoredRow() throws Exception {
        JdbcRunStore raw = new JdbcRunStore(SeededData.dataSource());
        RunProgressTracker tracker = new RunProgressTracker();
        DefaultForecastService svc = build(SeededData.dataSource(), new FakeGateway(), tracker, raw);
        try {
            UUID id = raw.create(new RunRequest(team, null), SeededData.asOf(), LocalDateTime.now());
            raw.finish(id, 0.9, "{}", List.of(), List.of(), "{}", LocalDateTime.now());
            tracker.start(id);
            for (int i = 0; i < RunProgressTracker.MAX_TRACKED; i++) {
                tracker.start(UUID.randomUUID());
            }
            assertTrue(tracker.get(id).isEmpty(), "the run's tracker entry was evicted by the later starts");
            RunProgress progress = svc.progress(id);
            assertEquals("DONE", progress.phase());
            assertEquals(100, progress.percent());

            UUID failedId = raw.create(new RunRequest(team, null), SeededData.asOf(), LocalDateTime.now());
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
                () -> service.runNow(new RunRequest(emptyTeam, null)));
        assertEquals("TEAM_NOT_FOUND", ex.code());
        assertEquals(RunStatus.FAILED, service.listRuns(emptyTeam, 1).get(0).status());
        assertEquals("RUN_NOT_DONE", assertThrows(ForecastException.class, () -> service.getRun(service.listRuns(emptyTeam, 1).get(0).id())).code());
    }

    @Test
    void interruptedRunsAreFailedWhenTheModuleStarts() {
        JdbcRunStore raw = new JdbcRunStore(SeededData.dataSource());
        UUID left = raw.create(new RunRequest(team, null), SeededData.asOf(), LocalDateTime.now());
        raw.markRunning(left);
        assertTrue(service.recoverInterruptedRuns() >= 1);
        RunSummary r = service.listRuns(team, 50).stream().filter(s -> s.id().equals(left)).findFirst().orElseThrow();
        assertEquals(RunStatus.FAILED, r.status());
        assertEquals("interrupted by a restart", r.error());
        assertEquals("FAILED", service.progress(left).phase());
        assertEquals("forecast failed", service.progress(left).label().en());
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

    /** The first team (in {@code data.teams()} order) with a member who logged hours on a weekday in the range: not necessarily the shared
     * {@code team}, whose members happen to have stopped logging before the range; every other test in the class runs the shared {@code team}
     * on different days, so any team works here without colliding with them. */
    private static UUID teamWithLoggedHoursBetween(LocalDate from, LocalDate to) {
        ForecastData data = SeededData.data();
        SortedMap<MemberDay, Double> logged = Truth.realisedHoursByDay(data);
        for (TeamRow t : data.teams()) {
            for (MemberRow m : data.membersOfTeam(t.id())) {
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY
                            && logged.getOrDefault(new MemberDay(m.id(), d), 0.0) > 0) {
                        return t.id();
                    }
                }
            }
        }
        return null;
    }

    @Test
    void accuracyComparesTheForecastsMadeBeforeEachWeekdayWithTheLoggedHours() {
        LocalDate from = LocalDate.of(2026, 8, 20);
        LocalDate to = LocalDate.of(2026, 9, 2);
        UUID activeTeam = teamWithLoggedHoursBetween(from, to);
        assertNotNull(activeTeam, "a team in the seed logged hours on a weekday between " + from + " and " + to);
        LocalDate wednesday = LocalDate.of(2026, 8, 19);
        RunResult r = service.runNow(new RunRequest(activeTeam, null), wednesday);
        assertEquals(LocalDate.of(2026, 8, 20), r.memberDays().get(0).day());
        AccuracyResult acc = service.accuracy(activeTeam, from, to);
        assertEquals(SeededData.asOf(), acc.evaluatedAt(), "the clock's day");
        assertEquals(to, acc.to());
        assertEquals(from, acc.from());
        assertEquals(LocalDate.of(2026, 9, 5), service.accuracy(activeTeam, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).to(), "clamped to yesterday");
        AccuracyResult future = service.accuracy(activeTeam, LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 30));
        assertTrue(future.current().isEmpty(), "nothing has passed yet");
        assertTrue(future.scores().isEmpty(), "nothing was scored, so there is no team row either");
        int members = (int) r.memberDays().stream().map(d -> d.userId()).distinct().count();
        assertTrue(acc.nonWorkingDays() > 0, "the seed puts a public holiday in those two weeks");
        assertEquals(members * 10, acc.current().size() + acc.nonWorkingDays(),
                "ten weekdays per member, 2026-08-20 to 2026-09-02, all in the past; the public holidays are counted, not scored");
        assertTrue(acc.current().stream().allMatch(row -> row.runId().equals(r.run().id())));
        assertTrue(acc.current().stream().allMatch(row -> row.lead() >= 1 && row.lead() <= 10));
        assertTrue(acc.current().stream().anyMatch(row -> row.loggedHrs() > 0), "the seed logged hours in those weeks");
        SortedMap<MemberDay, Double> logged = Truth.realisedHoursByDay(SeededData.data());
        assertTrue(acc.current().stream().allMatch(row -> row.loggedHrs() == logged.getOrDefault(new MemberDay(row.userId(), row.day()), 0.0)),
                "each row's logged hours match the truth computed directly from the seed's time logs");
        AccuracyScore teamScore = acc.scores().get(0);
        assertEquals("team", teamScore.scope());
        assertEquals(acc.current().size(), teamScore.n());
        assertTrue(teamScore.mae() >= 0);
        assertEquals(members, acc.scores().stream().filter(s -> s.scope().equals("member")).count());
        List<AccuracyScore> leads = acc.scores().stream().filter(s -> s.scope().equals("lead")).toList();
        assertEquals(8, leads.size(),
                () -> "two of the ten weekdays are non-working for every member: " + acc.nonWorkingDays() + " skipped rows over " + members + " members");
        assertEquals(acc.nonWorkingDays(), members * 2, "both holidays fall on all three members");
        assertTrue(leads.stream().allMatch(s -> s.n() >= members), "every lead of the run in the range has every member scored");
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> service.accuracy(activeTeam, LocalDate.of(2026, 9, 2), LocalDate.of(2026, 8, 20))).code());
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> service.accuracy(activeTeam, null, LocalDate.of(2026, 8, 20))).code());
        assertEquals("TEAM_NOT_FOUND", assertThrows(ForecastException.class, () -> service.accuracy(UUID.randomUUID(), LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 2))).code());
    }

    @Test
    void findRunAnswersForARunThatIsNotDoneWhileGetRunStillRefuses() {
        // Isolated on its own database: SeededData.dataSource() is shared by every other method in this class,
        // whose runs for `member` are stamped with the real clock (DefaultForecastService.enqueue), all newer
        // than the 2026-09-06 timestamp this test creates its run with, which would make latestRunOf(member)
        // answer with a sibling's run instead of this one.
        DataSource ds = SeededData.freshDataSource();
        JdbcRunStore isolated = new JdbcRunStore(ds);
        DefaultForecastService svc = build(ds, new FakeGateway(), new RunProgressTracker(), isolated);
        try {
            UUID id = isolated.create(new RunRequest(team, member), SeededData.asOf(), LocalDateTime.of(2026, 9, 6, 8, 0));
            isolated.markRunning(id);

            RunSummary summary = svc.findRun(id).orElseThrow();
            assertEquals(team, summary.teamId());
            assertEquals(member, summary.requestedBy());
            assertEquals(RunStatus.RUNNING, summary.status());

            ForecastException e = assertThrows(ForecastException.class, () -> svc.getRun(id));
            assertEquals("RUN_NOT_DONE", e.code());

            assertFalse(svc.findRun(UUID.randomUUID()).isPresent());
            assertEquals(id, svc.latestRunOf(member).orElseThrow().id());
            assertFalse(svc.latestRunOf(UUID.randomUUID()).isPresent());
        } finally {
            try {
                svc.close();
            } catch (Exception ignored) {
                // test teardown
            }
        }
    }

}
