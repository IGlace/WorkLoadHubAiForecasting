package com.workloadhub.forecast.eval;

import java.time.LocalDate;

/** One arrival-level metric value for one horizon and origin. There is one model, so no model column. */
public record ScoreRow(int horizon, LocalDate origin, String metric, double value) {
}
