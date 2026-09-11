package com.workloadhub.forecast.api;

/**
 * Accuracy over a set of rows: {@code scope} is "team", "member" or "lead"; {@code key} the team id, the user id or the lead. NaN when empty.
 * {@code mase} is scored on the {@code maseN} rows whose member has a log on the same weekday seven days earlier, and is NaN when there are none.
 */
public record AccuracyScore(String scope, String key, int n, double mae, double bias, double mase, int maseN, double overloadPrecision,
        double overloadRecall) {
}
