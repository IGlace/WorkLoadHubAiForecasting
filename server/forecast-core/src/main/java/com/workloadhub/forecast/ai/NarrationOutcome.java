package com.workloadhub.forecast.ai;

import com.workloadhub.forecast.api.NarrativeStatus;
import java.util.List;

/**
 * What one narration produced, before it is stored. {@code reason} is null unless FAILED (then timeout, model_error or
 * invalid_output); {@code narrativeJson} is null when FAILED; {@code rawText} is the last answer when FAILED, else null.
 */
public record NarrationOutcome(NarrativeStatus status, String reason, String narrativeJson, String rawText, String verificationJson,
        String usageJson, String model, int attempts, List<String> toolCalls, String error) {

    public NarrationOutcome {
        toolCalls = List.copyOf(toolCalls);
    }
}
