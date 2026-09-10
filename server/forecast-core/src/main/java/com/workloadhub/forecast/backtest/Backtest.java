package com.workloadhub.forecast.backtest;

import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.model.ArrivalModel;
import com.workloadhub.forecast.model.ModelUnavailable;
import com.workloadhub.forecast.model.SeasonalNaive;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Rolling-origin tournament: every model against the seasonal-naive floor, MASE per origin and horizon. */
public final class Backtest {

    public static final String FLOOR = SeasonalNaive.NAME;
    public static final int ORIGIN_COUNT = 6;
    public static final int ORIGIN_STEP_WEEKS = 2;
    public static final int MIN_HISTORY_WEEKS = 13;
    static final double LOW_QUANTILE = 0.1;
    static final double HIGH_QUANTILE = 0.9;

    public record Score(String model, LocalDate origin, int horizon, double mae, double mase) {
    }

    public record Champion(String model, double meanMase) {
    }

    public record Result(List<Score> scores, Map<String, Map<Integer, double[]>> residuals, Map<String, String> unavailable,
            Map<String, Double> secondsPerModel) {

        public double meanMase(String model) {
            return scores.stream().filter(s -> s.model().equals(model) && !Double.isNaN(s.mase()))
                    .mapToDouble(Score::mase).average().orElse(Double.NaN);
        }

        public Map<String, Double> meanMaseByModel() {
            Map<String, Double> out = new TreeMap<>();
            for (Score s : scores) {
                out.computeIfAbsent(s.model(), this::meanMase);
            }
            return out;
        }

        /** The pooled residuals of one model at one horizon, or an empty array when either is unknown. */
        public double[] residuals(String model, int h) {
            Map<Integer, double[]> byHorizon = residuals.get(model);
            if (byHorizon == null) {
                return new double[0];
            }
            double[] found = byHorizon.get(h);
            return found == null ? new double[0] : found;
        }
    }

    private Backtest() {
    }

    public static List<LocalDate> origins(LocalDate lastCompleteWeek, LocalDate firstWeek) {
        List<LocalDate> out = new ArrayList<>();
        for (int k = ORIGIN_COUNT; k >= 1; k--) {
            LocalDate origin = lastCompleteWeek.minusWeeks((long) k * ORIGIN_STEP_WEEKS);
            if (ChronoUnit.WEEKS.between(firstWeek, origin) >= MIN_HISTORY_WEEKS) {
                out.add(origin);
            }
        }
        return out;
    }

    public static double mase(double[] y, double[] yHat, double[] yNaive) {
        double num = 0;
        double den = 0;
        for (int i = 0; i < y.length; i++) {
            num += Math.abs(y[i] - yHat[i]);
            den += Math.abs(y[i] - yNaive[i]);
        }
        return den == 0.0 ? Double.NaN : num / den;
    }

    static double mae(double[] y, double[] yHat) {
        double s = 0;
        for (int i = 0; i < y.length; i++) {
            s += Math.abs(y[i] - yHat[i]);
        }
        return y.length == 0 ? Double.NaN : s / y.length;
    }

    public static Result run(FeatureMatrix feat, Map<String, Supplier<ArrivalModel>> factories, List<LocalDate> origins, int[] horizons) {
        // the floor must always be scored and pooled, even when the caller only asked for other models.
        Map<String, Supplier<ArrivalModel>> withFloor = factories;
        if (!factories.containsKey(FLOOR)) {
            withFloor = new LinkedHashMap<>();
            withFloor.put(FLOOR, SeasonalNaive::new);
            withFloor.putAll(factories);
        }
        int maxH = Arrays.stream(horizons).max().orElse(1);
        List<Score> scores = new ArrayList<>();
        Map<String, Map<Integer, List<Double>>> residuals = new LinkedHashMap<>();
        Map<String, String> unavailable = new LinkedHashMap<>();
        Map<String, Double> seconds = new LinkedHashMap<>();
        for (LocalDate origin : origins) {
            FeatureMatrix train = feat.filter(k -> !k.week().isAfter(origin.minusWeeks(maxH)));
            FeatureMatrix test = feat.filter(k -> k.week().equals(origin));
            if (train.rowCount() == 0 || test.rowCount() == 0) {
                continue;
            }
            Map<String, ArrivalModel> fitted = new LinkedHashMap<>();
            for (Map.Entry<String, Supplier<ArrivalModel>> e : withFloor.entrySet()) {
                String name = e.getKey();
                if (unavailable.containsKey(name)) {
                    continue;
                }
                long started = System.nanoTime();
                try {
                    fitted.put(name, e.getValue().get().fit(train, horizons));
                } catch (ModelUnavailable ex) {
                    unavailable.put(name, ex.getMessage());
                    continue;
                }
                seconds.merge(name, (System.nanoTime() - started) / 1e9, Double::sum);
            }
            ArrivalModel naive = new SeasonalNaive().fit(train, horizons);
            for (int h : horizons) {
                double[] y = test.target(h);
                if (Arrays.stream(y).anyMatch(Double::isNaN)) {
                    continue;
                }
                double[] yNaive = naive.predict(test, h);
                for (Map.Entry<String, ArrivalModel> e : fitted.entrySet()) {
                    long started = System.nanoTime();
                    double[] yHat = e.getValue().predict(test, h);
                    seconds.merge(e.getKey(), (System.nanoTime() - started) / 1e9, Double::sum);
                    scores.add(new Score(e.getKey(), origin, h, mae(y, yHat), mase(y, yHat, yNaive)));
                    List<Double> pool = residuals.computeIfAbsent(e.getKey(), k -> new TreeMap<>()).computeIfAbsent(h, k -> new ArrayList<>());
                    for (int i = 0; i < y.length; i++) {
                        pool.add(y[i] - yHat[i]);
                    }
                }
            }
            for (ArrivalModel m : fitted.values()) {
                if (m instanceof AutoCloseable c) {
                    try {
                        c.close();
                    } catch (Exception ignored) {
                        // a booster that fails to dispose leaks a little native memory until the JVM exits
                    }
                }
            }
        }
        scores.removeIf(s -> unavailable.containsKey(s.model()));
        unavailable.keySet().forEach(residuals::remove);
        unavailable.keySet().forEach(seconds::remove);
        Map<String, Map<Integer, double[]>> pooled = new LinkedHashMap<>();
        residuals.forEach((model, byH) -> {
            Map<Integer, double[]> arrays = new TreeMap<>();
            byH.forEach((h, list) -> arrays.put(h, list.stream().mapToDouble(Double::doubleValue).toArray()));
            pooled.put(model, arrays);
        });
        return new Result(List.copyOf(scores), pooled, unavailable, seconds);
    }

    public static Champion selectChampion(List<Score> scores) {
        Map<String, List<Double>> byModel = new TreeMap<>();
        for (Score s : scores) {
            if (!Double.isNaN(s.mase())) {
                byModel.computeIfAbsent(s.model(), k -> new ArrayList<>()).add(s.mase());
            }
        }
        if (byModel.isEmpty()) {
            return new Champion(FLOOR, Double.NaN);
        }
        String best = null;
        double bestMean = Double.POSITIVE_INFINITY;
        Map<String, Double> means = new HashMap<>();
        for (Map.Entry<String, List<Double>> e : byModel.entrySet()) {
            double mean = e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            means.put(e.getKey(), mean);
            if (mean < bestMean) {
                best = e.getKey();
                bestMean = mean;
            }
        }
        if (best.equals(FLOOR) || bestMean >= 1.0) {
            return new Champion(FLOOR, means.getOrDefault(FLOOR, 1.0));
        }
        return new Champion(best, bestMean);
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
