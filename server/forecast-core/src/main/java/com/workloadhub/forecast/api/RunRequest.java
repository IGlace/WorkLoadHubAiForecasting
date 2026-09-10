package com.workloadhub.forecast.api;

import com.workloadhub.forecast.run.ModelRegistry;
import java.util.UUID;

/** What a caller asks for: a team, optionally a forced model and the planned-work switch. The run day is the server's today. */
public record RunRequest(UUID teamId, UUID requestedBy, String forcedModel, Boolean plannedWork) {

    public RunRequest {
        if (teamId == null) {
            throw ForecastException.invalidRequest("teamId is required");
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
