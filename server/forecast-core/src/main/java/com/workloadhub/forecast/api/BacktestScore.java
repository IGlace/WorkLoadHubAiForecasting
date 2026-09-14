package com.workloadhub.forecast.api;

import java.time.LocalDate;

/** One backtest measurement: MAE in hours for one origin and horizon. {@code mae} is {@code null}, not NaN, when that row could not be scored. */
public record BacktestScore(LocalDate origin, int horizon, Double mae) {
}
