package com.workloadhub.forecast.api;

import java.util.UUID;

public record RunProgress(UUID runId, String phase, int percent, String message, String thinking, String answer) {
}
