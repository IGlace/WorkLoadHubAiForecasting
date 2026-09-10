package com.workloadhub.forecast.api;

import java.time.LocalDate;

/** {@code mae} and {@code mase} are {@code null}, not NaN, when the stored backtest could not score that row. */
public record ModelScore(String model, LocalDate origin, int horizon, Double mae, Double mase) {
}
