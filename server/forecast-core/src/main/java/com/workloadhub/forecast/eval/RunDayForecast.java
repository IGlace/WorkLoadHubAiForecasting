package com.workloadhub.forecast.eval;

import com.workloadhub.forecast.api.MemberDayForecast;
import java.time.LocalDate;
import java.util.UUID;

/** One day row of one run, with the run day it was made on. */
public record RunDayForecast(UUID runId, LocalDate asOf, MemberDayForecast day) {
}
