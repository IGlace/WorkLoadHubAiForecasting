package com.workloadhub.forecast.eval;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Everything one evaluation run produced: arrival-level scores, demand-level rows, and what was skipped. */
public record EvalResult(List<ScoreRow> scores, List<DemandRow> demand, Map<String, String> skipped, String truthSource,
        double elapsedSeconds, List<LocalDate> origins) {
}
