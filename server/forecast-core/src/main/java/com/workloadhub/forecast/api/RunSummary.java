package com.workloadhub.forecast.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** {@code mae} is {@code null}, not NaN, when the run had no scored backtest origin. */
public record RunSummary(UUID id, UUID teamId, UUID requestedBy, LocalDate asOf, RunStatus status, Double mae, String error, LocalDateTime createdAt,
        LocalDateTime finishedAt) {
}
