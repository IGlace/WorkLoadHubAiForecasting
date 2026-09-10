package com.workloadhub.forecast.api;

import java.util.UUID;

/**
 * Whether this user can narrate: a stored token, a runtime the SDK can load, and, when both hold, whether the token is
 * accepted and how much quota is left. {@code authenticated} and {@code login} are null when no token is stored or the
 * runtime is unavailable; {@code quotaJson} is null when the quota could not be read and {@code message} says why.
 */
public record CopilotStatus(UUID userId, boolean hasToken, boolean runtimeAvailable, String runtimePath, String runtimeVersion, Boolean authenticated,
        String login, String quotaJson, String message) {
}
