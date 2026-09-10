package com.workloadhub.forecast.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The module's public surface (spec section 11); the host wires it as a bean or calls it from the CLI. */
public interface ForecastService {

    UUID startRun(RunRequest request);

    RunResult getRun(UUID runId);

    List<RunSummary> listRuns(UUID teamId, int limit);

    RunProgress progress(UUID runId);

    NarrativeResult narrate(NarrativeRequest request);

    Optional<NarrativeResult> narrative(UUID runId, String language);

    CopilotStatus copilotStatus(UUID userId);
}
