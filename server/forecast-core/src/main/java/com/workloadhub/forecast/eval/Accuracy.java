package com.workloadhub.forecast.eval;

import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.AccuracyRow;
import com.workloadhub.forecast.api.AccuracyScore;
import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.MemberDayForecast;
import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.data.Ids;
import com.workloadhub.forecast.features.MemberDay;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/** Forecast versus logged hours per member and weekday that has passed (design 2026-09-11). Pure: no JDBC, no clock. */
public final class Accuracy {

    public static final String TEAM = "team";
    public static final String MEMBER = "member";
    public static final String LEAD = "lead";

    private Accuracy() {
    }

    /** Weekdays strictly after {@code asOf} up to and including {@code day}: the first weekday after the run day is lead 1. */
    public static int lead(LocalDate asOf, LocalDate day) {
        int lead = 0;
        for (LocalDate d = asOf.plusDays(1); !d.isAfter(day); d = d.plusDays(1)) {
            if (isWeekday(d)) {
                lead++;
            }
        }
        return lead;
    }

    static boolean isWeekday(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    public static AccuracyResult evaluate(UUID teamId, LocalDate from, LocalDate to, LocalDate evaluatedAt, List<CurrentDayForecast> current,
            List<RunDayForecast> runDays, SortedMap<MemberDay, Double> logged) {
        List<AccuracyRow> rows = new ArrayList<>();
        for (CurrentDayForecast c : current) {
            if (inRange(c.day(), from, to)) {
                rows.add(row(c.userId(), c.day(), c.runId(), lead(asOfOf(c, runDays), c.day()), c.demandHrs(), c.capacityHrs(), c.overloadHrs(), logged));
            }
        }
        rows.sort((a, b) -> a.userId().equals(b.userId()) ? a.day().compareTo(b.day()) : Ids.UUID_ORDER.compare(a.userId(), b.userId()));
        List<AccuracyScore> scores = new ArrayList<>();
        scores.add(score(TEAM, teamId.toString(), rows, logged));
        SortedMap<UUID, List<AccuracyRow>> byMember = new TreeMap<>(Ids.UUID_ORDER);
        for (AccuracyRow r : rows) {
            byMember.computeIfAbsent(r.userId(), k -> new ArrayList<>()).add(r);
        }
        byMember.forEach((user, rs) -> scores.add(score(MEMBER, user.toString(), rs, logged)));
        SortedMap<Integer, List<AccuracyRow>> byLead = new TreeMap<>();
        for (RunDayForecast rd : runDays) {
            MemberDayForecast d = rd.day();
            if (inRange(d.day(), from, to)) {
                int lead = lead(rd.asOf(), d.day());
                byLead.computeIfAbsent(lead, k -> new ArrayList<>())
                        .add(row(d.userId(), d.day(), rd.runId(), lead, d.demandHrs(), d.capacityHrs(), d.overloadHrs(), logged));
            }
        }
        byLead.forEach((lead, rs) -> scores.add(score(LEAD, Integer.toString(lead), rs, logged)));
        return new AccuracyResult(teamId, from, to, evaluatedAt, List.copyOf(rows), List.copyOf(scores));
    }

    private static boolean inRange(LocalDate day, LocalDate from, LocalDate to) {
        return isWeekday(day) && !day.isBefore(from) && !day.isAfter(to);
    }

    /** The run day of the current row's run; the current row carries only the run id, so it is looked up among the run days (else lead 0). */
    private static LocalDate asOfOf(CurrentDayForecast c, List<RunDayForecast> runDays) {
        for (RunDayForecast rd : runDays) {
            if (rd.runId().equals(c.runId())) {
                return rd.asOf();
            }
        }
        return c.day();
    }

    private static AccuracyRow row(UUID user, LocalDate day, UUID runId, int lead, double forecast, double capacity, double overload,
            SortedMap<MemberDay, Double> logged) {
        double actual = logged.getOrDefault(new MemberDay(user, day), 0.0);
        return new AccuracyRow(user, day, runId, lead, forecast, actual, capacity, overload > 0, actual > capacity);
    }

    /**
     * MASE is scored on the rows whose member has a log on the same weekday seven days earlier; a row without one is left out of MASE alone and
     * still counts in {@code n}, {@code mae}, {@code bias} and the overload rates. Substituting zero for a missing log made MASE depend on how
     * sparse the logs are, which is not what the metric is for.
     */
    static AccuracyScore score(String scope, String key, List<AccuracyRow> rows, SortedMap<MemberDay, Double> logged) {
        int n = rows.size();
        double[] y = new double[n];
        double[] p = new double[n];
        boolean[] actualOver = new boolean[n];
        boolean[] forecastOver = new boolean[n];
        List<double[]> scorable = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            AccuracyRow r = rows.get(i);
            y[i] = r.loggedHrs();
            p[i] = r.forecastHrs();
            actualOver[i] = r.actualOverload();
            forecastOver[i] = r.forecastOverload();
            Double naive = logged.get(new MemberDay(r.userId(), r.day().minusDays(7)));
            if (naive != null) {
                scorable.add(new double[] {y[i], p[i], naive});
            }
        }
        int maseN = scorable.size();
        double[] my = new double[maseN];
        double[] mp = new double[maseN];
        double[] mNaive = new double[maseN];
        for (int i = 0; i < maseN; i++) {
            my[i] = scorable.get(i)[0];
            mp[i] = scorable.get(i)[1];
            mNaive[i] = scorable.get(i)[2];
        }
        double[] pr = Metrics.overloadPrecisionRecall(actualOver, forecastOver);
        double mase = maseN == 0 ? Double.NaN : Backtest.mase(my, mp, mNaive);
        return new AccuracyScore(scope, key, n, Metrics.mae(y, p), Metrics.bias(y, p), mase, maseN, pr[0], pr[1]);
    }
}
