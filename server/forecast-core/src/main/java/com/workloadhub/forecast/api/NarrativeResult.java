package com.workloadhub.forecast.api;

import java.time.LocalDateTime;
import java.util.UUID;

public record NarrativeResult(UUID id, UUID runId, String language, String model, String narrativeJson, String verificationJson, String usageJson, LocalDateTime createdAt) {
}
