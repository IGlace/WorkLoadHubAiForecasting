package com.workloadhub.forecast.api;

import java.util.UUID;

/** What a caller asks for: a team. The run day is the server's today. */
public record RunRequest(UUID teamId, UUID requestedBy) {

    public RunRequest {
        if (teamId == null) {
            throw ForecastException.invalidRequest("teamId is required");
        }
    }
}
