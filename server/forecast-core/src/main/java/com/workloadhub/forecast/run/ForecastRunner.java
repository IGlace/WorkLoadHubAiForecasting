package com.workloadhub.forecast.run;

import com.workloadhub.forecast.Numbers;
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
import com.workloadhub.forecast.model.XgboostHours;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/** One forecast run as pure functions: prepare the global model once, then derive each team's outcome. */
public final class ForecastRunner {

    public interface ProgressListener {
        void phase(String phase, int percent, String message);

        ProgressListener NONE = (phase, percent, message) -> {
        };
    }

    /** A window's predicted hours with the interval the backtest residuals give it, and what it exceeds capacity by. */
    public record Band(double demand, double low, double high, double overload) {
    }

    private final CapacityRule capacityRule;
    private final int windows;

    public ForecastRunner(CapacityRule capacityRule, int windows) {
        this.capacityRule = capacityRule;
        this.windows = windows;
    }

    /** The rule this runner computes capacity with, so an evaluation measures the runner as configured. */
    public CapacityRule capacityRule() {
        return capacityRule;
    }

    /** The window count (whf.forecast.windows) this runner computes every run at. */
    public int windows() {
        return windows;
    }

    public static Band band(double demand, double q10, double q90, double capacity) {
        double d = Numbers.round2(Math.max(0.0, demand));
        return new Band(d, Numbers.round2(Math.max(0.0, d + q10)), Numbers.round2(d + q90),
                Numbers.round2(Math.max(0.0, d - capacity)));
    }

    public Prepared prepare(ForecastData data, LocalDate asOf, ProgressListener progress) {
        Map<String, Double> seconds = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        progress.phase("FEATURES", 5, "deriving lifecycles and the feature matrix");
        Lifecycle lc = Lifecycle.derive(data);
        WorkingCalendar cal = WorkingCalendar.fromHolidays(data.holidays());
        LocalDate origin = Weeks.lastCompleteWeek(asOf);
        List<ForecastWindow> windowList = Horizon.windows(asOf, windows);
        int[] horizons = Horizon.horizons(origin, windowList);
        FeatureMatrix features = new FeatureBuilder(data, lc, cal, capacityRule, windows).build(data.members(), origin);
        LocalDate firstWeek = features.rowCount() == 0 ? origin : features.keys().stream().map(MemberWeek::week).min(LocalDate::compareTo).orElse(origin);
        int historyWeeks = features.rowCount() == 0 ? 0 : (int) Weeks.weeksBetween(firstWeek, origin) + 1;
        List<LocalDate> origins = features.rowCount() == 0 ? List.of() : Backtest.origins(origin, firstWeek, Horizon.maxHorizon(origin, windows));
        seconds.put("features", elapsed(t0));

        long t1 = System.nanoTime();
        progress.phase("BACKTEST", 25, "scoring " + origins.size() + " origins");
        Backtest.Result backtest = Backtest.run(features, origins, horizons);
        Double mae = origins.isEmpty() ? null : backtest.meanMae();
        Double meanActual = origins.isEmpty() ? null : backtest.meanActualHours();
        seconds.put("backtest", elapsed(t1));

        long t2 = System.nanoTime();
        progress.phase("FORECAST", 60, "fitting the booster and predicting");
        Map<Integer, double[]> offsets = new TreeMap<>();
        for (int h : horizons) {
            double[] q = Backtest.intervalBounds(backtest.residuals().getOrDefault(h, new double[0]));
            offsets.put(h, new double[] {Math.min(0.0, q[0]), Math.max(0.0, q[1])});
        }
        Map<MemberWeek, Double> predicted = new TreeMap<>();
        FeatureMatrix atOrigin = features.filter(k -> k.week().equals(origin));
        if (atOrigin.rowCount() > 0) {
            try (XgboostHours model = new XgboostHours()) {
                model.fit(features, horizons);
                for (int i = 0; i < horizons.length; i++) {
                    double[] pred = model.predict(atOrigin, horizons[i]);
                    for (int r = 0; r < atOrigin.rowCount(); r++) {
                        predicted.put(new MemberWeek(atOrigin.key(r).member(), origin.plusWeeks(horizons[i])), Math.max(0.0, pred[r]));
                    }
                }
            }
        }
        seconds.put("forecast", elapsed(t2));
        return new Prepared(data, lc, cal, asOf, origin, windowList, horizons, features, origins, backtest, mae, meanActual,
                offsets, predicted, historyWeeks, seconds);
    }

    public TeamOutcome forTeam(Prepared p, UUID teamId) {
        ForecastData data = p.data();
        List<MemberRow> members = data.membersOfTeam(teamId).stream()
                .filter(m -> m.left() == null || m.left().isAfter(p.origin()))
                .toList();
        if (members.isEmpty()) {
            throw ForecastException.of("TEAM_NOT_FOUND", "team " + teamId + " has no counted member");
        }
        Map<UUID, MemberRow> byId = members.stream().collect(Collectors.toMap(MemberRow::id, m -> m));
        List<ForecastWindow> windows = p.windows();
        LocalDate first = windows.get(0).start();
        LocalDate last = windows.get(windows.size() - 1).end();
        // A predicted week's hours land evenly on that week's working days inside the horizon (design
        // 2026-09-10, section 4); task 9 replaces the even split with the member's weekday shares.
        SortedMap<MemberDay, Double> demandByDay = new TreeMap<>();
        p.predictedHours().forEach((k, v) -> {
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
                    demandByDay.merge(new MemberDay(k.member(), d), v / working, Double::sum);
                }
            }
        });
        List<MemberWindowForecast> windowRows = new ArrayList<>();
        List<MemberDayForecast> dayRows = new ArrayList<>();
        for (MemberRow m : members) {
            for (ForecastWindow w : windows) {
                double demandSum = 0;
                double capacity = 0;
                double absence = 0;
                int workingDays = 0;
                double q10 = 0;
                double q90 = 0;
                for (LocalDate d : w.weekdays()) {
                    MemberDay key = new MemberDay(m.id(), d);
                    double cap = capacityRule.dayCapacity(m, d, data, p.calendar());
                    boolean workingDay = p.calendar().isWorkingDay(d);
                    // The day figure is rounded; the window figure is the rounded raw sum, so a window and the
                    // sum of its days can differ by a few hundredths of an hour.
                    double demand = Numbers.round2(demandByDay.getOrDefault(key, 0.0));
                    dayRows.add(new MemberDayForecast(m.id(), d, w.index(), demand, cap,
                            Numbers.round2(Math.max(0.0, demand - cap)), workingDay));
                    demandSum += demand;
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
                Band b = band(demandSum, q10, q90, Numbers.round2(capacity));
                windowRows.add(new MemberWindowForecast(m.id(), w.index(), w.start(), w.end(), b.demand(), b.low(),
                        b.high(), Numbers.round2(capacity), b.overload(), workingDays, Numbers.round2(absence)));
            }
        }
        windowRows.sort(Comparator.comparing(MemberWindowForecast::userId, Ids.UUID_ORDER).thenComparingInt(MemberWindowForecast::windowIndex));
        dayRows.sort(Comparator.comparing(MemberDayForecast::userId, Ids.UUID_ORDER).thenComparing(MemberDayForecast::day));
        return new TeamOutcome(p, teamId, members, List.copyOf(windowRows), List.copyOf(dayRows));
    }

    private static double elapsed(long since) {
        return (System.nanoTime() - since) / 1e9;
    }
}
