package com.workloadhub.forecast.ai;

public record AuthStatus(boolean authenticated, String login, String message) {
}
