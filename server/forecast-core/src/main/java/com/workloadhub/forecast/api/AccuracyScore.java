package com.workloadhub.forecast.api;

/**
 * Accuracy over a set of rows: {@code scope} is "team", "member" or "lead"; {@code key} the team id, the user
 * id or the lead. A scope with zero rows is not scored at all and produces no entry in
 * {@link com.workloadhub.forecast.api.AccuracyResult#scores()} — the member and lead scopes are always built
 * from non-empty groups, so only the team scope can be absent this way. {@code mase} is scored on the
 * {@code maseN} rows whose member has a log on the same weekday seven days earlier, and is NaN whenever there
 * are none, even for a scope that was otherwise scored ({@code n > 0}).
 */
public record AccuracyScore(String scope, String key, int n, double mae, double bias, double mase, int maseN, double overloadPrecision,
        double overloadRecall) {
}
