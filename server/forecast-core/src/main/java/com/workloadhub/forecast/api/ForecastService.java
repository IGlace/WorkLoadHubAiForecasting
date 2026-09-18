package com.workloadhub.forecast.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The module's public surface (spec section 11); the host wires it as a bean. */
public interface ForecastService {

    UUID startRun(RunRequest request);

    RunResult getRun(UUID runId);

    List<RunSummary> listRuns(UUID teamId, int limit);

    /**
     * The run's summary whatever its status, for a host that must decide whether a caller may poll a run
     * that is still computing: {@link #getRun} refuses anything but DONE and {@link #progress} carries
     * neither the team nor the requester. Empty when there is no such run.
     */
    Optional<RunSummary> findRun(UUID runId);

    /**
     * The most recent run this user requested, across every team and whatever its status, for a host
     * enforcing one run at a time. Empty for a user with no run.
     */
    Optional<RunSummary> latestRunOf(UUID userId);

    /** The team's current forecast per member and day between two days inclusive: the latest run that covered each day. */
    List<CurrentDayForecast> currentForecast(UUID teamId, LocalDate from, LocalDate to);

    /** How the forecasts made before each weekday between two days compared with the hours logged on it (design 2026-09-11). */
    AccuracyResult accuracy(UUID teamId, LocalDate from, LocalDate to);

    RunProgress progress(UUID runId);

    NarrativeResult narrate(NarrativeRequest request);

    Optional<NarrativeResult> narrative(UUID runId, String language);

    CopilotStatus copilotStatus(UUID userId);
}
