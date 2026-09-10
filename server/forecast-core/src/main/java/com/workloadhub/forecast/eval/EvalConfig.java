package com.workloadhub.forecast.eval;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What to evaluate: the as-of date, how many backtest origins, and which models and teams (empty = all). */
public record EvalConfig(LocalDate asOf, int origins, List<String> models, List<UUID> teams) {

    public EvalConfig {
        models = models == null ? List.of() : models;
        teams = teams == null ? List.of() : teams;
    }
}
