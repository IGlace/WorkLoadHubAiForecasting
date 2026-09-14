package com.workloadhub.forecast.eval;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.calendar.ForecastWindow;
import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.features.FeatureBuilder;
import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.MemberDay;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.Truncation;
import com.workloadhub.forecast.model.ModelUnavailable;
import com.workloadhub.forecast.model.XgboostHours;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.run.Prepared;
import com.workloadhub.forecast.run.TeamOutcome;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.UUID;

/**
 * Two-level evaluation: arrival accuracy of the single booster, and demand accuracy of the whole pipeline.
 *
 * <p>The model tournament this class used to run is gone with the tournament itself (design 2026-09-13): there is
 * one model, {@link XgboostHours#NAME}, so the {@code model} column of every row it produces is that one constant.
 * Task 11 finishes the retarget of this class (dropping the model column outright, giving it the configurable
 * window count); until then this is the minimal shape that compiles and measures the one model there is.
 */
public final class Harness {

    public static final int[] HORIZONS = {1, 2};

    private final ForecastRunner runner;
    private final CapacityRule rule;

    public Harness(ForecastRunner runner, CapacityRule rule) {
        this.runner = runner;
        this.rule = rule;
    }

    public EvalResult evaluate(ForecastData data, EvalConfig configIn) {
        long started = System.nanoTime();
        for (String name : configIn.models()) {
            if (!XgboostHours.NAME.equals(name)) {
                throw ForecastException.invalidRequest("unknown model " + name + "; known: [" + XgboostHours.NAME + "]");
            }
        }
        EvalConfig config = configIn.asOf() != null ? configIn
                : new EvalConfig(lastCreated(data).orElseGet(LocalDate::now), configIn.origins(), configIn.models(), configIn.teams());
        LocalDate origin = Weeks.lastCompleteWeek(config.asOf());
        Lifecycle lc = Lifecycle.derive(data);
        WorkingCalendar cal = WorkingCalendar.fromHolidays(data.holidays());
        FeatureMatrix features = new FeatureBuilder(data, lc, cal, rule).build(data.members(), origin);
        LocalDate firstWeek = features.keys().stream().map(MemberWeek::week).min(LocalDate::compareTo).orElse(origin);
        List<LocalDate> origins = features.rowCount() == 0 ? List.of() : Backtest.origins(origin, firstWeek, config.origins());
        Map<String, String> skipped = new LinkedHashMap<>();
        List<ScoreRow> scores;
        Backtest.Result bt;
        try {
            bt = Backtest.run(features, origins, HORIZONS);
            scores = arrivalLevel(bt);
        } catch (ModelUnavailable e) {
            skipped.put(XgboostHours.NAME, e.getMessage());
            scores = List.of();
        }
        List<DemandRow> demand = demandLevel(data, origins, config.teams(), skipped);
        return new EvalResult(scores, demand, skipped, Truth.SOURCE, (System.nanoTime() - started) / 1e9, origins, config, fingerprint(data));
    }

    /** What the evaluation ran against, for the report's reproducibility section. */
    static Map<String, String> fingerprint(ForecastData data) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("members", String.valueOf(data.members().size()));
        f.put("teams", String.valueOf(data.teams().size()));
        f.put("tasks", String.valueOf(data.tasks().size()));
        f.put("time_logs", String.valueOf(data.timeLogs().size()));
        f.put("first_created", data.tasks().stream().map(t -> t.createdDate().toLocalDate()).min(LocalDate::compareTo).map(Object::toString).orElse("none"));
        f.put("last_created", lastCreated(data).map(Object::toString).orElse("none"));
        return f;
    }

    private static Optional<LocalDate> lastCreated(ForecastData data) {
        return data.tasks().stream().map(t -> t.createdDate().toLocalDate()).max(LocalDate::compareTo);
    }

    /** {@code mase} and {@code beats_naive} are gone with the tournament's naive floor (task 5); {@code mae}, the interval metrics and
     * {@code seconds} are what remains scored. */
    static List<ScoreRow> arrivalLevel(Backtest.Result bt) {
        List<ScoreRow> rows = new ArrayList<>();
        for (Backtest.Score s : bt.scores()) {
            rows.add(new ScoreRow(XgboostHours.NAME, s.horizon(), s.origin(), "mae", s.mae()));
            List<Backtest.Residual> all = bt.residualRows(s.horizon());
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
            rows.add(new ScoreRow(XgboostHours.NAME, s.horizon(), s.origin(), "coverage80", coverage));
            rows.add(new ScoreRow(XgboostHours.NAME, s.horizon(), s.origin(), "wql", wql));
            rows.add(new ScoreRow(XgboostHours.NAME, s.horizon(), s.origin(), "seconds", s.horizon() == HORIZONS[0] ? bt.seconds() / Math.max(1, bt.scores().size() / HORIZONS.length) : Double.NaN));
        }
        return rows;
    }

    private List<DemandRow> demandLevel(ForecastData data, List<LocalDate> origins, List<UUID> teamsIn, Map<String, String> skipped) {
        SortedMap<MemberDay, Double> truth = Truth.realisedHoursByDay(data);
        List<DemandRow> rows = new ArrayList<>();
        if (skipped.containsKey(XgboostHours.NAME)) {
            return rows;
        }
        List<UUID> teams = teamsIn.isEmpty()
                ? data.teams().stream().map(TeamRow::id).filter(t -> !data.membersOfTeam(t).isEmpty()).sorted((a, b) -> a.toString().compareTo(b.toString())).toList()
                : teamsIn;
        for (LocalDate origin : origins) {
            LocalDate asOf = origin.plusWeeks(1);
            ForecastData replay = Truncation.at(data, asOf);
            Prepared prepared;
            try {
                prepared = runner.prepare(replay, asOf, ForecastRunner.ProgressListener.NONE);
            } catch (ModelUnavailable e) {
                skipped.put(XgboostHours.NAME, e.getMessage());
                continue;
            }
            for (UUID team : teams) {
                TeamOutcome outcome;
                try {
                    outcome = runner.forTeam(prepared, team);
                } catch (ForecastException e) {
                    if ("TEAM_NOT_FOUND".equals(e.code())) {
                        continue;
                    }
                    throw e;
                }
                for (MemberWindowForecast w : outcome.memberWindows()) {
                    ForecastWindow window = prepared.windows().get(w.windowIndex() - 1);
                    double realised = 0;
                    for (LocalDate d : window.weekdays()) {
                        realised += truth.getOrDefault(new MemberDay(w.userId(), d), 0.0);
                    }
                    // openHours/newHours/plannedHours no longer exist (design 2026-09-13): the demand forecast is
                    // one figure now. Kept as zero placeholders until task 10 drops the columns from this record.
                    rows.add(new DemandRow(XgboostHours.NAME, origin, team, w.userId(), w.windowIndex(), w.windowStart(), w.windowEnd(), w.demandHrs(),
                            Math.round(realised * 1e6) / 1e6, w.capacityHrs(), 0.0, 0.0, 0.0));
                }
            }
        }
        if (skipped.containsKey(XgboostHours.NAME)) {
            rows.clear();
        }
        return rows;
    }
}
