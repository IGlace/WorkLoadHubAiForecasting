package com.workloadhub.forecast.api;

/** Accuracy over a set of rows: {@code scope} is "team", "member" or "lead"; {@code key} the team id, the user id or the lead. NaN when empty. */
public record AccuracyScore(String scope, String key, int n, double mae, double bias, double mase, double overloadPrecision, double overloadRecall) {
}
