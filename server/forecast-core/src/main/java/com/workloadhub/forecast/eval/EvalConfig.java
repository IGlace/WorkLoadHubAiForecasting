package com.workloadhub.forecast.eval;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.calendar.Horizon;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What to evaluate: the as-of date, how many backtest origins, which teams (empty = all), and how many
 * windows to score.
 *
 * <p>{@code windows} exists because {@link Harness} builds its own arrival-level feature matrix independently
 * of whatever {@link com.workloadhub.forecast.run.ForecastRunner} it is given, and {@code Harness} is not a
 * Spring bean, so it has no other way to learn {@code whf.forecast.windows}; passing the count here is the
 * only way to evaluate a horizon other than the default. Callers that want the default pass {@code 2}: there
 * is no coercion of an unset value, because a public entry point ({@link
 * com.workloadhub.forecast.api.ForecastService#evaluate}) should refuse exactly what the auto-configuration
 * refuses rather than silently defaulting an invalid one.
 *
 * <p>A null {@code asOf} means "the latest task creation date in the data", which only the harness can see;
 * {@link EvalResult#resolved()} reports the date it chose.
 */
public record EvalConfig(LocalDate asOf, int origins, List<UUID> teams, int windows) {

    public EvalConfig {
        teams = teams == null ? List.of() : teams;
        if (windows < Horizon.MIN_WINDOWS || windows > Horizon.MAX_WINDOWS) {
            throw ForecastException.invalidRequest("windows must be between " + Horizon.MIN_WINDOWS
                    + " and " + Horizon.MAX_WINDOWS + ", but was " + windows);
        }
    }
}
