package com.workloadhub.forecast.ai;

/** The module's seam around the Copilot SDK: one connection per token. Tests use a scripted fake. */
public interface CopilotGateway {

    /** Starts a client for this token; throws {@code ForecastException} COPILOT_UNAVAILABLE when the runtime cannot start. */
    CopilotConnection open(String token);

    /** Where the runtime is and which SDK version, without starting anything. */
    RuntimeInfo runtime();
}
