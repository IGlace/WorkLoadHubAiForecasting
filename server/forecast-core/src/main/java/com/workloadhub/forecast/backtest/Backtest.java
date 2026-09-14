package com.workloadhub.forecast.backtest;

import com.workloadhub.forecast.Numbers;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.Features;
import com.workloadhub.forecast.model.XgboostHours;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Rolling-origin measurement: one booster fitted per origin, MAE in hours per origin and horizon. */
public final class Backtest {

    public static final int ORIGIN_COUNT = 6;
    public static final int ORIGIN_STEP_WEEKS = 2;
    static final double LOW_QUANTILE = 0.1;
    static final double HIGH_QUANTILE = 0.9;

    public record Score(LocalDate origin, int horizon, double mae) {
    }

    public record Residual(LocalDate origin, double y, double residual) {
    }

    public record Result(List<Score> scores, Map<Integer, double[]> residuals, Map<Integer, List<Residual>> residualRows,
            double seconds, double meanActualHours) {

        public double meanMae() {
            double s = 0;
            int n = 0;
            for (Score sc : scores) {
                if (!Double.isNaN(sc.mae())) {
                    s += sc.mae();
                    n++;
                }
            }
            return n == 0 ? Double.NaN : s / n;
        }

        /** The pooled residuals at one horizon, or an empty array when unknown. */
        public double[] residuals(int h) {
            double[] found = residuals.get(h);
            return found == null ? new double[0] : found;
        }

        /** The per-origin residual rows at one horizon, or an empty list when unknown. */
        public List<Residual> residualRows(int h) {
            List<Residual> found = residualRows.get(h);
            return found == null ? List.of() : found;
        }
    }

    private Backtest() {
    }

    /**
     * The history an origin needs behind it, ruling 18.1 of the 2026-09-13 design: 13 weeks at two windows,
     * exactly the old fixed value, and 17 at six. Scaling it keeps the usable training span constant, because
     * {@code run} trains on weeks up to {@code origin - maxHorizon} so a training row's target cannot overlap
     * the test week.
     */
    public static int minHistoryWeeks(int maxHorizon) {
        return 10 + maxHorizon;
    }

    public static List<LocalDate> origins(LocalDate lastCompleteWeek, LocalDate firstWeek, int maxHorizon) {
        return origins(lastCompleteWeek, firstWeek, ORIGIN_COUNT, maxHorizon);
    }

    public static List<LocalDate> origins(LocalDate lastCompleteWeek, LocalDate firstWeek, int count, int maxHorizon) {
        List<LocalDate> out = new ArrayList<>();
        for (int k = count; k >= 1; k--) {
            LocalDate origin = lastCompleteWeek.minusWeeks((long) k * ORIGIN_STEP_WEEKS);
            if (ChronoUnit.WEEKS.between(firstWeek, origin) >= minHistoryWeeks(maxHorizon)) {
                out.add(origin);
            }
        }
        return out;
    }

    public static Result run(FeatureMatrix feat, List<LocalDate> origins, int[] horizons) {
        for (int h : horizons) {
            if (!feat.columns().contains(Features.target(h))) {
                throw ForecastException.of("INVALID_REQUEST",
                        "the feature matrix has no " + Features.target(h) + ": it was built for a different window count");
            }
        }
        int maxH = Arrays.stream(horizons).max().orElse(1);
        List<Score> scores = new ArrayList<>();
        Map<Integer, List<Double>> residuals = new TreeMap<>();
        Map<Integer, List<Residual>> residualRows = new TreeMap<>();
        double seconds = 0;
        double actualSum = 0;
        int actualCount = 0;
        for (LocalDate origin : origins) {
            FeatureMatrix train = feat.filter(k -> !k.week().isAfter(origin.minusWeeks(maxH)));
            FeatureMatrix test = feat.filter(k -> k.week().equals(origin));
            if (train.rowCount() == 0 || test.rowCount() == 0) {
                continue;
            }
            long started = System.nanoTime();
            try (XgboostHours model = new XgboostHours()) {
                model.fit(train, horizons);
                seconds += (System.nanoTime() - started) / 1e9;
                for (int h : horizons) {
                    double[] y = test.target(h);
                    if (Arrays.stream(y).anyMatch(Double::isNaN)) {
                        continue;
                    }
                    long predictStarted = System.nanoTime();
                    double[] yHat = model.predict(test, h);
                    seconds += (System.nanoTime() - predictStarted) / 1e9;
                    scores.add(new Score(origin, h, Numbers.mae(y, yHat)));
                    List<Double> pool = residuals.computeIfAbsent(h, k -> new ArrayList<>());
                    List<Residual> rowPool = residualRows.computeIfAbsent(h, k -> new ArrayList<>());
                    for (int i = 0; i < y.length; i++) {
                        pool.add(y[i] - yHat[i]);
                        rowPool.add(new Residual(origin, y[i], y[i] - yHat[i]));
                        actualSum += y[i];
                        actualCount++;
                    }
                }
            }
        }
        Map<Integer, double[]> pooled = new TreeMap<>();
        residuals.forEach((h, list) -> pooled.put(h, list.stream().mapToDouble(Double::doubleValue).toArray()));
        Map<Integer, List<Residual>> frozenRows = new LinkedHashMap<>();
        residualRows.forEach((h, list) -> frozenRows.put(h, List.copyOf(list)));
        double meanActual = actualCount == 0 ? Double.NaN : actualSum / actualCount;
        return new Result(List.copyOf(scores), pooled, frozenRows, seconds, meanActual);
    }

    /** NumPy's default (linear) quantiles at 0.1 and 0.9. */
    public static double[] intervalBounds(double[] residuals) {
        if (residuals.length == 0) {
            return new double[] {0.0, 0.0};
        }
        double[] s = residuals.clone();
        Arrays.sort(s);
        return new double[] {quantile(s, LOW_QUANTILE), quantile(s, HIGH_QUANTILE)};
    }

    static double quantile(double[] sorted, double q) {
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(lo + 1, sorted.length - 1);
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
    }
}
