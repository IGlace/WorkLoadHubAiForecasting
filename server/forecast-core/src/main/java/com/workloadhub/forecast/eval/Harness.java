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
import java.util.stream.IntStream;

/**
 * Two-level evaluation: arrival accuracy of the single booster, and demand accuracy of the whole pipeline.
 *
 * <p>The model tournament this class used to run is gone with the tournament itself (design 2026-09-13): there is
 * one model, so no row this class produces carries a model column any more, and a caller cannot ask to evaluate one
 * model over another — {@code ModelUnavailable} (the native library missing, or too few training rows) is no
 * longer caught and reported as "skipped"; it propagates, because there is nothing left to fall back to
 * (design section 19.6, an accepted cost).
 */
public final class Harness {

    private final ForecastRunner runner;
    private final CapacityRule rule;

    public Harness(ForecastRunner runner, CapacityRule rule) {
        this.runner = runner;
        this.rule = rule;
    }

    public EvalResult evaluate(ForecastData data, EvalConfig configIn) {
        long started = System.nanoTime();
        EvalConfig config = configIn.asOf() != null ? configIn
                : new EvalConfig(lastCreated(data).orElseGet(LocalDate::now), configIn.origins(), configIn.teams(), configIn.windows());
        // Both levels must measure the same forecast: Level A takes its horizons from config.windows() while
        // Level B is the injected runner, which computes every run at its own whf.forecast.windows. A caller
        // asking for a count the runner does not have would get a report whose header says one number and whose
        // demand rows stop at another; the driver escapes by construction, a host through the public API does not.
        if (config.windows() != runner.windows()) {
            throw ForecastException.of("INVALID_REQUEST", "evaluation asks for " + config.windows()
                    + " windows but the runner forecasts " + runner.windows());
        }
        LocalDate origin = Weeks.lastCompleteWeek(config.asOf());
        Lifecycle lc = Lifecycle.derive(data);
        WorkingCalendar cal = WorkingCalendar.fromHolidays(data.holidays());
        int windows = config.windows();
        // The horizons this evaluation scores: one per window (1..windows), the shape HORIZONS = {1, 2} always
        // had at the old fixed window count of two. This is narrower than Features.horizons(windows), which pads
        // to windows + 1 for the feature matrix a live run needs; the evaluation harness has no such run-day
        // edge case to cover, and padding it here starves leave-one-out coverage at the extra horizon with few
        // origins.
        int[] horizons = IntStream.rangeClosed(1, windows).toArray();
        int maxHorizon = windows;
        FeatureMatrix features = new FeatureBuilder(data, lc, cal, rule, windows).build(data.members(), origin);
        LocalDate firstWeek = features.keys().stream().map(MemberWeek::week).min(LocalDate::compareTo).orElse(origin);
        List<LocalDate> origins = features.rowCount() == 0 ? List.of() : Backtest.origins(origin, firstWeek, config.origins(), maxHorizon);
        Backtest.Result bt = Backtest.run(features, origins, horizons);
        List<ScoreRow> scores = arrivalLevel(bt, horizons);
        List<DemandRow> demand = demandLevel(data, origins, config.teams());
        return new EvalResult(scores, demand, Truth.SOURCE, (System.nanoTime() - started) / 1e9, origins, config, fingerprint(data));
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
    static List<ScoreRow> arrivalLevel(Backtest.Result bt, int[] horizons) {
        List<ScoreRow> rows = new ArrayList<>();
        for (Backtest.Score s : bt.scores()) {
            rows.add(new ScoreRow(s.horizon(), s.origin(), "mae", s.mae()));
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
            rows.add(new ScoreRow(s.horizon(), s.origin(), "coverage80", coverage));
            rows.add(new ScoreRow(s.horizon(), s.origin(), "wql", wql));
            rows.add(new ScoreRow(s.horizon(), s.origin(), "seconds", s.horizon() == horizons[0] ? bt.seconds() / Math.max(1, bt.scores().size() / horizons.length) : Double.NaN));
        }
        return rows;
    }

    private List<DemandRow> demandLevel(ForecastData data, List<LocalDate> origins, List<UUID> teamsIn) {
        SortedMap<MemberDay, Double> truth = Truth.realisedHoursByDay(data);
        List<DemandRow> rows = new ArrayList<>();
        List<UUID> teams = teamsIn.isEmpty()
                ? data.teams().stream().map(TeamRow::id).filter(t -> !data.membersOfTeam(t).isEmpty()).sorted((a, b) -> a.toString().compareTo(b.toString())).toList()
                : teamsIn;
        for (LocalDate origin : origins) {
            LocalDate asOf = origin.plusWeeks(1);
            ForecastData replay = Truncation.at(data, asOf);
            Prepared prepared = runner.prepare(replay, asOf, ForecastRunner.ProgressListener.NONE);
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
                    rows.add(new DemandRow(origin, team, w.userId(), w.windowIndex(), w.windowStart(), w.windowEnd(), w.demandHrs(),
                            Math.round(realised * 1e6) / 1e6, w.capacityHrs(), w.backlogExcessHrs(), w.dueExcessHrs()));
                }
            }
        }
        return rows;
    }
}
