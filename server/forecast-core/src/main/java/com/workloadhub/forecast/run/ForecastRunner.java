package com.workloadhub.forecast.run;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.MemberWeekForecast;
import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.features.FeatureBuilder;
import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import com.workloadhub.forecast.model.ArrivalModel;
import com.workloadhub.forecast.model.EffortModel;
import com.workloadhub.forecast.model.ModelUnavailable;
import com.workloadhub.forecast.planned.PlannedWork;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** One forecast run as pure functions: prepare the global model once, then derive each team's outcome. */
public final class ForecastRunner {

    public interface ProgressListener {
        void phase(String phase, int percent, String message);

        ProgressListener NONE = (phase, percent, message) -> {
        };
    }

    /** The demand arithmetic of one member-week, isolated so a property test can pin it. */
    public record Band(double open, double fresh, double planned, double demand, double low, double high, double overload) {
    }

    private final CapacityRule capacityRule;
    private final boolean plannedWorkDefault;

    public ForecastRunner(CapacityRule capacityRule, boolean plannedWorkDefault) {
        this.capacityRule = capacityRule;
        this.plannedWorkDefault = plannedWorkDefault;
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static Band band(double open, double fresh, double planned, double q10, double q90, double ratio, double capacity) {
        double o = round2(open);
        double n = round2(fresh);
        double p = round2(planned);
        double demand = round2(o + n + p);
        double low = round2(Math.min(demand, o + p + Math.max(0.0, n + Math.min(0.0, q10) * ratio)));
        double high = round2(Math.max(demand, o + p + n + Math.max(0.0, q90) * ratio));
        double overload = round2(Math.max(0.0, demand - capacity));
        return new Band(o, n, p, demand, low, high, overload);
    }

    public Prepared prepare(ForecastData data, LocalDate asOf, String forcedModel, ProgressListener progress) {
        Map<String, Double> seconds = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        progress.phase("FEATURES", 5, "deriving lifecycles and the feature matrix");
        Lifecycle lc = Lifecycle.derive(data);
        WorkingCalendar cal = WorkingCalendar.fromHolidays(data.holidays());
        LocalDate origin = Weeks.lastCompleteWeek(asOf);
        LocalDate[] weeks = Weeks.forecastWeeks(asOf);
        int h1 = (int) Weeks.weeksBetween(origin, weeks[0]);
        int[] horizons = {h1, h1 + 1};
        FeatureMatrix features = new FeatureBuilder(data, lc, cal, capacityRule).build(data.members(), origin);
        LocalDate firstWeek = features.rowCount() == 0 ? origin : features.keys().stream().map(MemberWeek::week).min(LocalDate::compareTo).orElse(origin);
        int historyWeeks = features.rowCount() == 0 ? 0 : (int) Weeks.weeksBetween(firstWeek, origin) + 1;
        List<LocalDate> origins = features.rowCount() == 0 ? List.of() : Backtest.origins(origin, firstWeek);
        seconds.put("features", elapsed(t0));

        long t1 = System.nanoTime();
        progress.phase("BACKTEST", 25, "scoring " + origins.size() + " origins");
        Map<String, Supplier<ArrivalModel>> factories = ModelRegistry.factories(forcedModel);
        Backtest.Result backtest = Backtest.run(features, factories, origins, horizons);
        String champion;
        double championMase;
        if (forcedModel != null) {
            if (backtest.unavailable().containsKey(forcedModel)) {
                throw new ModelUnavailable(backtest.unavailable().get(forcedModel));
            }
            champion = forcedModel;
            championMase = backtest.meanMase(forcedModel);
        } else {
            Backtest.Champion c = Backtest.selectChampion(backtest.scores());
            champion = c.model();
            championMase = c.meanMase();
        }
        seconds.put("backtest", elapsed(t1));

        long t2 = System.nanoTime();
        progress.phase("FORECAST", 60, "fitting " + champion + " and predicting");
        Map<Integer, double[]> offsets = new TreeMap<>();
        for (int h : horizons) {
            double[] q = Backtest.intervalBounds(backtest.residuals(champion, h));
            offsets.put(h, new double[] {Math.min(0.0, q[0]), Math.max(0.0, q[1])});
        }
        Map<MemberWeek, Double> predicted = new TreeMap<>();
        FeatureMatrix atOrigin = features.filter(k -> k.week().equals(origin));
        if (atOrigin.rowCount() > 0) {
            ArrivalModel model = ModelRegistry.create(champion);
            try {
                model.fit(features, horizons);
                for (int i = 0; i < horizons.length; i++) {
                    double[] pred = model.predict(atOrigin, horizons[i]);
                    for (int r = 0; r < atOrigin.rowCount(); r++) {
                        predicted.put(new MemberWeek(atOrigin.key(r).member(), weeks[i]), Math.max(0.0, pred[r]));
                    }
                }
            } finally {
                if (model instanceof AutoCloseable c) {
                    try {
                        c.close();
                    } catch (Exception ignored) {
                        // a booster that fails to dispose leaks a little native memory until the JVM exits
                    }
                }
            }
        }
        EffortModel effort = EffortModel.fit(lc, data);
        seconds.put("forecast", elapsed(t2));
        return new Prepared(data, lc, cal, asOf, origin, weeks, horizons, features, origins, backtest, champion, championMase,
                forcedModel, offsets, predicted, effort, historyWeeks, seconds);
    }

    public TeamOutcome forTeam(Prepared p, UUID teamId, Boolean plannedWork) {
        ForecastData data = p.data();
        List<MemberRow> members = data.membersOfTeam(teamId).stream()
                .filter(m -> m.left() == null || m.left().isAfter(p.origin()))
                .toList();
        if (members.isEmpty()) {
            throw ForecastException.of("TEAM_NOT_FOUND", "team " + teamId + " has no counted member");
        }
        boolean planEnabled = plannedWork == null ? plannedWorkDefault : plannedWork;
        Map<UUID, MemberRow> byId = members.stream().collect(Collectors.toMap(MemberRow::id, m -> m));
        Function<UUID, UUID> teamOf = id -> byId.containsKey(id) ? byId.get(id).primaryTeamId() : teamId;
        Function<UUID, Set<LocalDate>> offDaysOf = id -> CapacityRule.offDays(id, data);
        List<TaskFacts> open = p.lifecycle().all().stream()
                .filter(f -> f.isAssigned() && !f.done() && byId.containsKey(f.assignee()))
                .toList();
        LocalDate f1 = p.forecastWeeks()[0];
        SortedMap<MemberWeek, Double> openHours = EffortModel.placeOpenTasks(open, p.effort(), f1, teamOf, offDaysOf, p.calendar());
        Map<MemberWeek, Double> teamPredicted = new TreeMap<>();
        p.predictedEst().forEach((k, v) -> {
            if (byId.containsKey(k.member())) {
                teamPredicted.put(k, v);
            }
        });
        SortedMap<MemberWeek, Double> newHours = EffortModel.placeNewArrivals(teamPredicted, p.effort(), teamOf, offDaysOf, p.calendar());
        PlannedWork.Allocation planned = planEnabled
                ? PlannedWork.allocate(new PlannedWork.Request(teamId, members, p.asOf(), p.forecastWeeks()), p.lifecycle(), data, p.effort(),
                        p.calendar(), offDaysOf)
                : PlannedWork.Allocation.empty();
        List<MemberWeekForecast> rows = new ArrayList<>();
        for (MemberRow m : members) {
            double ratio = p.effort().estimateRatio(m.id(), null, m.primaryTeamId());
            for (int i = 0; i < p.forecastWeeks().length; i++) {
                LocalDate week = p.forecastWeeks()[i];
                MemberWeek key = new MemberWeek(m.id(), week);
                double[] q = p.bandOffsets().get(p.horizons()[i]);
                double capacity = capacityRule.capacity(m, week, data, p.calendar());
                Band b = band(openHours.getOrDefault(key, 0.0), newHours.getOrDefault(key, 0.0), planned.hours().getOrDefault(key, 0.0),
                        q[0], q[1], ratio, capacity);
                rows.add(new MemberWeekForecast(m.id(), week, b.open(), b.fresh(), b.planned(), b.demand(), b.low(), b.high(), capacity,
                        b.overload(), p.calendar().workingDaysInWeek(week), capacityRule.absenceHours(m.id(), week, data, p.calendar())));
            }
        }
        rows.sort((a, b) -> new MemberWeek(a.userId(), a.weekStart()).compareTo(new MemberWeek(b.userId(), b.weekStart())));
        return new TeamOutcome(p, teamId, members, planEnabled, openHours, newHours, planned, List.copyOf(rows));
    }

    private static double elapsed(long since) {
        return (System.nanoTime() - since) / 1e9;
    }
}
