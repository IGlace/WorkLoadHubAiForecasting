package com.workloadhub.forecast.api;

import java.time.LocalDate;

public record ModelScore(String model, LocalDate origin, int horizon, double mae, double mase) {
}
