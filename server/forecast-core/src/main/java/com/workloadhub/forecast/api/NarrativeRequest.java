package com.workloadhub.forecast.api;

import java.util.UUID;

public record NarrativeRequest(UUID runId, UUID requestedBy, String language, String model) {
}
