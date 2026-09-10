package com.workloadhub.forecast.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record RunSummary(UUID id, UUID teamId, UUID requestedBy, LocalDate asOf, RunStatus status, String forcedModel, String championModel, Double championMase, String error, LocalDateTime createdAt, LocalDateTime finishedAt) {
}
