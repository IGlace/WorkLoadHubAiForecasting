package com.workloadhub.forecast.api;

import com.workloadhub.forecast.eval.EvalConfig;
import com.workloadhub.forecast.eval.EvalResult;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The module's public surface (spec section 11); the host wires it as a bean. */
public interface ForecastService {

    UUID startRun(RunRequest request);

    RunResult getRun(UUID runId);

    List<RunSummary> listRuns(UUID teamId, int limit);

    /** The team's current forecast per member and day between two days inclusive: the latest run that covered each day. */
    List<CurrentDayForecast> currentForecast(UUID teamId, LocalDate from, LocalDate to);

    /** How the forecasts made before each weekday between two days compared with the hours logged on it (design 2026-09-11). */
    AccuracyResult accuracy(UUID teamId, LocalDate from, LocalDate to);

    /**
     * Backtests the models against history: arrival accuracy per model and horizon, and demand accuracy of whole
     * replayed runs per team (spec section 13). This reads history and writes nothing, so it never touches a run.
     * A null {@link EvalConfig#asOf()} means the latest task creation date; {@link EvalResult#resolved()} says which.
     */
    EvalResult evaluate(EvalConfig config);

    RunProgress progress(UUID runId);

    NarrativeResult narrate(NarrativeRequest request);

    Optional<NarrativeResult> narrative(UUID runId, String language);

    CopilotStatus copilotStatus(UUID userId);
}
