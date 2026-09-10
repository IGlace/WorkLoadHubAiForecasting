package com.workloadhub.forecast.api;

import java.util.UUID;

public record CopilotStatus(UUID userId, boolean hasToken, boolean runtimeAvailable, String message) {
}
