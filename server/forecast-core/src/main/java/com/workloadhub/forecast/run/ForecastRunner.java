package com.workloadhub.forecast.run;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.MemberDayForecast;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.calendar.ForecastWindow;
import com.workloadhub.forecast.calendar.Horizon;
import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.Ids;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.features.FeatureBuilder;
import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.MemberDay;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import com.workloadhub.forecast.model.ArrivalModel;
import com.workloadhub.forecast.model.EffortModel;
import com.workloadhub.forecast.model.ModelUnavailable;
import com.workloadhub.forecast.planned.PlannedWork;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** The demand arithmetic of one member-window, isolated so a property test can pin it. */
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
        List<ForecastWindow> windows = Horizon.windows(asOf);
        int[] horizons = Horizon.horizons(origin, windows);
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
                        predicted.put(new MemberWeek(atOrigin.key(r).member(), origin.plusWeeks(horizons[i])), Math.max(0.0, pred[r]));
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
        return new Prepared(data, lc, cal, asOf, origin, windows, horizons, features, origins, backtest, champion, championMase,
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
        List<ForecastWindow> windows = p.windows();
        LocalDate first = windows.get(0).start();
        LocalDate last = windows.get(windows.size() - 1).end();
        SortedMap<MemberDay, Double> openHours = EffortModel.placeOpenTasksByDay(open, p.effort(), first, teamOf, offDaysOf, p.calendar());
        // A predicted week's fresh hours land evenly on that week's working days inside the horizon (design 2026-09-10, section 4).
        SortedMap<MemberDay, Double> arrivals = new TreeMap<>();
        p.predictedEst().forEach((k, v) -> {
            if (!byId.containsKey(k.member())) {
                return;
            }
            LocalDate monday = k.week();
            int working = p.calendar().workingDaysInWeek(monday);
            if (working == 0) {
                return;
            }
            for (LocalDate d = monday; !d.isAfter(monday.plusDays(6)); d = d.plusDays(1)) {
                if (p.calendar().isWorkingDay(d) && !d.isBefore(first) && !d.isAfter(last)) {
                    arrivals.merge(new MemberDay(k.member(), d), v / working, Double::sum);
                }
            }
        });
        SortedMap<MemberDay, Double> newHours = EffortModel.placeNewArrivalsByDay(arrivals, p.effort(), teamOf, offDaysOf, p.calendar());
        PlannedWork.Allocation planned = planEnabled
                ? PlannedWork.allocate(new PlannedWork.Request(teamId, members, p.asOf(), windows), p.lifecycle(), data, p.effort(), p.calendar(), offDaysOf)
                : PlannedWork.Allocation.empty();
        List<MemberWindowForecast> windowRows = new ArrayList<>();
        List<MemberDayForecast> dayRows = new ArrayList<>();
        for (MemberRow m : members) {
            double ratio = p.effort().estimateRatio(m.id(), null, m.primaryTeamId());
            for (ForecastWindow w : windows) {
                double openSum = 0;
                double freshSum = 0;
                double plannedSum = 0;
                double capacity = 0;
                double absence = 0;
                int workingDays = 0;
                double q10 = 0;
                double q90 = 0;
                for (LocalDate d : w.weekdays()) {
                    MemberDay key = new MemberDay(m.id(), d);
                    double o = openHours.getOrDefault(key, 0.0);
                    double n = newHours.getOrDefault(key, 0.0);
                    double pl = planned.hours().getOrDefault(key, 0.0);
                    double cap = capacityRule.dayCapacity(m, d, data, p.calendar());
                    boolean workingDay = p.calendar().isWorkingDay(d);
                    // Day rows round each component so the stored day figures add up exactly; the window band rounds the raw sums, so a window and the sum of its days can differ by a few hundredths of an hour.
                    double demand = round2(round2(o) + round2(n) + round2(pl));
                    dayRows.add(new MemberDayForecast(m.id(), d, w.index(), round2(o), round2(n), round2(pl), demand, cap,
                            round2(Math.max(0.0, demand - cap)), workingDay));
                    openSum += o;
                    freshSum += n;
                    plannedSum += pl;
                    capacity += cap;
                    absence += capacityRule.dayAbsenceHours(m.id(), d, data);
                    if (workingDay) {
                        workingDays++;
                    }
                    // The band offsets of the horizon week this day belongs to, weighted by the day's share of the window.
                    double[] q = p.bandOffsets().get((int) Weeks.weeksBetween(p.origin(), d));
                    if (q != null) {
                        q10 += q[0] / w.weekdays().size();
                        q90 += q[1] / w.weekdays().size();
                    }
                }
                Band b = band(openSum, freshSum, plannedSum, q10, q90, ratio, round2(capacity));
                windowRows.add(new MemberWindowForecast(m.id(), w.index(), w.start(), w.end(), b.open(), b.fresh(), b.planned(), b.demand(), b.low(),
                        b.high(), round2(capacity), b.overload(), workingDays, round2(absence)));
            }
        }
        windowRows.sort(Comparator.comparing(MemberWindowForecast::userId, Ids.UUID_ORDER).thenComparingInt(MemberWindowForecast::windowIndex));
        dayRows.sort(Comparator.comparing(MemberDayForecast::userId, Ids.UUID_ORDER).thenComparing(MemberDayForecast::day));
        return new TeamOutcome(p, teamId, members, planEnabled, openHours, newHours, planned, List.copyOf(windowRows), List.copyOf(dayRows));
    }

    private static double elapsed(long since) {
        return (System.nanoTime() - since) / 1e9;
    }
}
