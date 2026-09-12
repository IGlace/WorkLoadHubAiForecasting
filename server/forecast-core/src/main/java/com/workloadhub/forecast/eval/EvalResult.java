package com.workloadhub.forecast.eval;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Everything one evaluation run produced: arrival-level scores, demand-level rows, and what was skipped.
 *
 * <p>{@code resolved} is the request as the harness actually ran it — the same {@link EvalConfig} the caller passed,
 * except that a null {@code asOf} has been replaced by the date the harness chose. {@code fingerprint} describes the
 * data it ran against, so a report can be reproduced without the caller holding the data itself.
 */
public record EvalResult(List<ScoreRow> scores, List<DemandRow> demand, Map<String, String> skipped, String truthSource,
        double elapsedSeconds, List<LocalDate> origins, EvalConfig resolved, Map<String, String> fingerprint) {
}
