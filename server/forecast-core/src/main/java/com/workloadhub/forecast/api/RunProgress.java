package com.workloadhub.forecast.api;

import java.util.UUID;

/** Where a run stands: its phase and percent, a technical message for logs, and a label for people. */
public record RunProgress(UUID runId, String phase, int percent, String message, ProgressLabel label) {
}
