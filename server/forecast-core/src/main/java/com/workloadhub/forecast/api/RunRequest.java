package com.workloadhub.forecast.api;

import com.workloadhub.forecast.run.ModelRegistry;
import java.time.LocalDate;
import java.util.UUID;

/** What a caller asks for: a team, an as-of date, optionally a forced model and the planned-work switch. */
public record RunRequest(UUID teamId, UUID requestedBy, LocalDate asOf, String forcedModel, Boolean plannedWork) {

    public RunRequest {
        if (teamId == null) {
            throw ForecastException.invalidRequest("teamId is required");
        }
        if (asOf == null) {
            throw ForecastException.invalidRequest("asOf is required");
        }
        forcedModel = forcedModel == null || forcedModel.isBlank() ? null : forcedModel.trim();
        if (forcedModel != null && !ModelRegistry.isKnown(forcedModel)) {
            throw ForecastException.invalidRequest("unknown model " + forcedModel + "; known: " + ModelRegistry.NAMES);
        }
    }

    public boolean plannedWorkOr(boolean defaultValue) {
        return plannedWork == null ? defaultValue : plannedWork;
    }
}
