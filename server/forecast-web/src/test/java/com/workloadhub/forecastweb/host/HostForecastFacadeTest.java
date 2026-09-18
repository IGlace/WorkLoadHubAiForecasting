package com.workloadhub.forecastweb.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.CopilotStatus;
import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.api.NarrativeRequest;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.ProgressLabel;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunSummary;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecastweb.SeededUsers;
import com.workloadhub.forecastweb.SeededUsers.Team;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The host's own rules, against a stub service: which phases count as in progress, and one team at a time for a skill team leader. */
class HostForecastFacadeTest {

    /**
     * A service that starts runs instantly and reports the phase the test sets for each. Tracks each run's team,
     * requester and each requester's latest run, since {@link HostForecastFacade} now reads {@code findRun} and
     * {@code latestRunOf} straight from the service (the facade no longer keeps its own registry).
     */
    static final class StubService implements ForecastService {
        final Map<UUID, String> phases = new HashMap<>();
        final Map<UUID, UUID> teamOfRun = new HashMap<>();
        final Map<UUID, UUID> requesterOfRun = new HashMap<>();
        final Map<UUID, UUID> latestRunOfUser = new HashMap<>();
        String nextPhase = "FEATURES";

        @Override
        public UUID startRun(RunRequest request) {
            UUID id = UUID.randomUUID();
            phases.put(id, nextPhase);
            teamOfRun.put(id, request.teamId());
            requesterOfRun.put(id, request.requestedBy());
            latestRunOfUser.put(request.requestedBy(), id);
            return id;
        }

        @Override
        public RunProgress progress(UUID runId) {
            return new RunProgress(runId, phases.get(runId), 50, "stub", new ProgressLabel("stub", "stub"));
        }

        @Override public RunResult getRun(UUID runId) { throw ForecastException.of("RUN_NOT_DONE", "stub"); }
        @Override public List<RunSummary> listRuns(UUID teamId, int limit) { return List.of(); }

        @Override
        public Optional<RunSummary> findRun(UUID runId) {
            UUID team = teamOfRun.get(runId);
            if (team == null) {
                return Optional.empty();
            }
            return Optional.of(new RunSummary(runId, team, requesterOfRun.get(runId), null, null, null, null, null, null));
        }

        @Override
        public Optional<RunSummary> latestRunOf(UUID userId) {
            UUID runId = latestRunOfUser.get(userId);
            if (runId == null) {
                return Optional.empty();
            }
            return Optional.of(new RunSummary(runId, teamOfRun.get(runId), userId, null, null, null, null, null, null));
        }

        @Override public List<CurrentDayForecast> currentForecast(UUID teamId, LocalDate from, LocalDate to) { return List.of(); }
        @Override public AccuracyResult accuracy(UUID teamId, LocalDate from, LocalDate to) { return null; }
        @Override public NarrativeResult narrate(NarrativeRequest request) { return null; }
        @Override public Optional<NarrativeResult> narrative(UUID runId, String language) { return Optional.empty(); }
        @Override public CopilotStatus copilotStatus(UUID userId) { return null; }
    }

    static final class NoTokens implements GitHubTokenStore {
        @Override public void save(UUID userId, String token) { }
        @Override public Optional<String> load(UUID userId) { return Optional.empty(); }
        @Override public boolean has(UUID userId) { return false; }
        @Override public void clear(UUID userId) { }
    }

    @Test
    void runInProgressEndsAtDoneFailedAndEveryNarrationPhase() {
        for (String phase : List.of("QUEUED", "LOADING", "FEATURES", "BACKTEST", "FORECAST", "FACTS", "PERSIST", "RUNNING")) {
            assertTrue(HostForecastFacade.runInProgress(phase), phase);
        }
        for (String phase : List.of("DONE", "FAILED", "NARRATING", "NARRATED", "NARRATION_FAILED")) {
            assertFalse(HostForecastFacade.runInProgress(phase), phase);
        }
        assertFalse(HostForecastFacade.runInProgress(null));
    }

    @Test
    void aSkillTeamLeaderRunsOneTeamAtATimeAndATeamLeaderIsNotLimited() throws Exception {
        // Read-only (role and team lookups); the runs themselves live only in the stub, never touch the database.
        DataSource ds = SeededData.dataSource();
        JdbcClient jdbc = JdbcClient.create(ds);
        StubService service = new StubService();
        HostForecastFacade facade = new HostForecastFacade(service, new ForecastAccess(jdbc), new NoTokens(), Clock.systemUTC());

        ActingUser acting = SeededUsers.users().stream().filter(u -> u.role().equals("SKILL_TEAM_LEADER") && SeededUsers.headsADepartment(u.id()))
                .findFirst().orElseThrow();
        Team department = SeededUsers.departmentOf(acting.id());
        List<Team> children = SeededUsers.childrenOf(department.id());

        UUID first = facade.startRun(acting, children.get(0).id());
        HostForbidden refused = assertThrows(HostForbidden.class, () -> facade.startRun(acting, children.get(children.size() > 1 ? 1 : 0).id()));
        assertEquals("one team at a time: run " + first + " is still in progress", refused.getMessage());
        service.phases.put(first, "DONE");
        UUID second = facade.startRun(acting, children.get(0).id());
        assertEquals(children.get(0).id(), facade.teamOf(second));

        ActingUser leading = SeededUsers.withRole("TEAM_LEADER");
        Team own = SeededUsers.managedBy(leading.id());
        facade.startRun(leading, own.id());
        facade.startRun(leading, own.id()); // still in progress by the stub, and allowed

        ForecastException unknown = assertThrows(ForecastException.class, () -> facade.teamOf(UUID.randomUUID()));
        assertEquals("RUN_NOT_FOUND", unknown.code());
        facade.close();
    }
}
