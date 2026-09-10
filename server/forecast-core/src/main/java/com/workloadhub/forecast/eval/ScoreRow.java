package com.workloadhub.forecast.eval;

import java.time.LocalDate;

/** One arrival-level metric value for one model, horizon and origin. */
public record ScoreRow(String model, int horizon, LocalDate origin, String metric, double value) {
}
