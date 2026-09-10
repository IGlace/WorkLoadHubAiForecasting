package com.workloadhub.forecast.api;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One stored narration. {@code narrativeJson} is null when {@code status} is FAILED, and {@code rawText} then holds
 * the last answer; {@code error} is "<reason>: <detail>" with reason in timeout, model_error, invalid_output.
 */
public record NarrativeResult(UUID id, UUID runId, String language, NarrativeStatus status, String model, String narrativeJson, String rawText,
        String verificationJson, String usageJson, String error, int attempts, int toolCalls, LocalDateTime createdAt) {
}
