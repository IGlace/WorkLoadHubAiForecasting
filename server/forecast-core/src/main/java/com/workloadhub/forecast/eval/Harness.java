package com.workloadhub.forecast.eval;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.MemberWeekForecast;
import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.features.FeatureBuilder;
import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.Truncation;
import com.workloadhub.forecast.model.ArrivalModel;
import com.workloadhub.forecast.model.ModelUnavailable;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.run.ModelRegistry;
import com.workloadhub.forecast.run.Prepared;
import com.workloadhub.forecast.run.TeamOutcome;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;
import java.util.function.Supplier;

/** Two-level evaluation: arrival accuracy per model, and demand accuracy of the whole pipeline per model. */
public final class Harness {

    public static final int[] HORIZONS = {1, 2};

    private final ForecastRunner runner;
    private final CapacityRule rule;

    public Harness(ForecastRunner runner, CapacityRule rule) {
        this.runner = runner;
        this.rule = rule;
    }

    public EvalResult evaluate(ForecastData data, EvalConfig config) {
        long started = System.nanoTime();
        for (String name : config.models()) {
            if (!ModelRegistry.isKnown(name)) {
                throw ForecastException.invalidRequest("unknown model " + name + "; known: " + ModelRegistry.NAMES);
            }
        }
        Map<String, Supplier<ArrivalModel>> factories = new LinkedHashMap<>();
        ModelRegistry.factories(null).forEach((name, f) -> {
            if (config.models().isEmpty() || config.models().contains(name) || name.equals(Backtest.FLOOR)) {
                factories.put(name, f);
            }
        });
        LocalDate origin = Weeks.lastCompleteWeek(config.asOf());
        Lifecycle lc = Lifecycle.derive(data);
        WorkingCalendar cal = WorkingCalendar.fromHolidays(data.holidays());
        FeatureMatrix features = new FeatureBuilder(data, lc, cal, rule).build(data.members(), origin);
        LocalDate firstWeek = features.keys().stream().map(MemberWeek::week).min(LocalDate::compareTo).orElse(origin);
        List<LocalDate> origins = features.rowCount() == 0 ? List.of() : Backtest.origins(origin, firstWeek, config.origins());
        Backtest.Result bt = Backtest.run(features, factories, origins, HORIZONS);
        List<ScoreRow> scores = arrivalLevel(bt);
        Map<String, String> skipped = new LinkedHashMap<>(bt.unavailable());
        List<DemandRow> demand = demandLevel(data, factories, origins, config.teams(), skipped);
        return new EvalResult(scores, demand, skipped, Truth.SOURCE, (System.nanoTime() - started) / 1e9, origins);
    }

    static List<ScoreRow> arrivalLevel(Backtest.Result bt) {
        List<ScoreRow> rows = new ArrayList<>();
        Map<String, Long> scoredOrigins = new LinkedHashMap<>();
        bt.scores().stream().map(s -> s.model() + "|" + s.origin()).distinct().forEach(k -> scoredOrigins.merge(k.split("\\|")[0], 1L, Long::sum));
        for (Backtest.Score s : bt.scores()) {
            rows.add(new ScoreRow(s.model(), s.horizon(), s.origin(), "mae", s.mae()));
            rows.add(new ScoreRow(s.model(), s.horizon(), s.origin(), "mase", s.mase()));
            rows.add(new ScoreRow(s.model(), s.horizon(), s.origin(), "beats_naive", Double.isNaN(s.mase()) ? Double.NaN : (s.mase() < 1.0 ? 1.0 : 0.0)));
            List<Backtest.Residual> all = bt.residualRows(s.model(), s.horizon());
            double[] others = all.stream().filter(r -> !r.origin().equals(s.origin())).mapToDouble(Backtest.Residual::residual).toArray();
            List<Backtest.Residual> mine = all.stream().filter(r -> r.origin().equals(s.origin())).toList();
            double coverage = Double.NaN;
            double wql = Double.NaN;
            if (others.length > 0 && !mine.isEmpty()) {
                double[] q = Backtest.intervalBounds(others);
                double[] y = mine.stream().mapToDouble(Backtest.Residual::y).toArray();
                double[] point = mine.stream().mapToDouble(r -> r.y() - r.residual()).toArray();
                double[] low = new double[y.length];
                double[] high = new double[y.length];
                for (int i = 0; i < y.length; i++) {
                    low[i] = point[i] + q[0];
                    high[i] = point[i] + q[1];
                }
                coverage = Metrics.coverage(y, low, high);
                wql = Metrics.weightedQuantileLoss(y, Map.of(0.1, low, 0.5, point, 0.9, high));
            }
            rows.add(new ScoreRow(s.model(), s.horizon(), s.origin(), "coverage80", coverage));
            rows.add(new ScoreRow(s.model(), s.horizon(), s.origin(), "wql", wql));
            double seconds = bt.secondsPerModel().getOrDefault(s.model(), Double.NaN) / Math.max(1, scoredOrigins.getOrDefault(s.model(), 1L));
            rows.add(new ScoreRow(s.model(), s.horizon(), s.origin(), "seconds", s.horizon() == HORIZONS[0] ? seconds : Double.NaN));
        }
        return rows;
    }

    private List<DemandRow> demandLevel(ForecastData data, Map<String, Supplier<ArrivalModel>> factories, List<LocalDate> origins, List<UUID> teamsIn,
            Map<String, String> skipped) {
        SortedMap<MemberWeek, Double> truth = Truth.realisedHours(data);
        List<DemandRow> rows = new ArrayList<>();
        List<UUID> teams = teamsIn.isEmpty()
                ? data.teams().stream().map(TeamRow::id).filter(t -> !data.membersOfTeam(t).isEmpty()).sorted((a, b) -> a.toString().compareTo(b.toString())).toList()
                : teamsIn;
        for (LocalDate origin : origins) {
            LocalDate asOf = origin.plusWeeks(1);
            ForecastData replay = Truncation.at(data, asOf);
            for (String model : factories.keySet()) {
                if (skipped.containsKey(model)) {
                    continue;
                }
                Prepared prepared;
                try {
                    prepared = runner.prepare(replay, asOf, model, ForecastRunner.ProgressListener.NONE);
                } catch (ModelUnavailable e) {
                    skipped.put(model, e.getMessage());
                    continue;
                }
                for (UUID team : teams) {
                    TeamOutcome outcome;
                    try {
                        outcome = runner.forTeam(prepared, team, null);
                    } catch (ForecastException e) {
                        if ("TEAM_NOT_FOUND".equals(e.code())) {
                            continue;
                        }
                        throw e;
                    }
                    for (MemberWeekForecast w : outcome.memberWeeks()) {
                        rows.add(new DemandRow(model, origin, team, w.userId(), w.weekStart(), w.demandHrs(),
                                truth.getOrDefault(new MemberWeek(w.userId(), w.weekStart()), 0.0), w.capacityHrs(), w.openHrs(), w.newHrs(), w.plannedHrs()));
                    }
                }
            }
        }
        rows.removeIf(r -> skipped.containsKey(r.model()));
        return rows;
    }
}
