package com.workloadhub.forecast.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The accuracy of a team's forecasts between two days: the current-forecast rows compared and the scores by team, member and lead. */
public record AccuracyResult(UUID teamId, LocalDate from, LocalDate to, LocalDate evaluatedAt, List<AccuracyRow> current, List<AccuracyScore> scores) {
}
