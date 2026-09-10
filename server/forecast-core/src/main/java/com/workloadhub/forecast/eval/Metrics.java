package com.workloadhub.forecast.eval;

import java.util.Map;

/** Pure metric functions over equal-length arrays; NaN on empty input. */
public final class Metrics {

    private Metrics() {
    }

    public static double mae(double[] y, double[] p) {
        if (y.length == 0) {
            return Double.NaN;
        }
        double s = 0;
        for (int i = 0; i < y.length; i++) {
            s += Math.abs(y[i] - p[i]);
        }
        return s / y.length;
    }

    /** Mean of forecast minus truth: positive means the forecast runs high. */
    public static double bias(double[] y, double[] p) {
        if (y.length == 0) {
            return Double.NaN;
        }
        double s = 0;
        for (int i = 0; i < y.length; i++) {
            s += p[i] - y[i];
        }
        return s / y.length;
    }

    public static double coverage(double[] y, double[] low, double[] high) {
        if (y.length == 0) {
            return Double.NaN;
        }
        int in = 0;
        for (int i = 0; i < y.length; i++) {
            if (low[i] <= y[i] && y[i] <= high[i]) {
                in++;
            }
        }
        return (double) in / y.length;
    }

    /** Mean over quantiles of the scaled pinball loss, as the GIFT-Eval benchmark defines it. */
    public static double weightedQuantileLoss(double[] y, Map<Double, double[]> quantiles) {
        if (y.length == 0 || quantiles.isEmpty()) {
            return Double.NaN;
        }
        double scale = 0;
        for (double v : y) {
            scale += Math.abs(v) / y.length;
        }
        if (scale == 0) {
            return Double.NaN;
        }
        double total = 0;
        for (Map.Entry<Double, double[]> e : quantiles.entrySet()) {
            double q = e.getKey();
            double[] p = e.getValue();
            double loss = 0;
            for (int i = 0; i < y.length; i++) {
                double d = y[i] - p[i];
                loss += Math.max(q * d, (q - 1.0) * d) / y.length;
            }
            total += 2.0 * loss;
        }
        return total / quantiles.size() / scale;
    }

    public static double[] overloadPrecisionRecall(boolean[] trueOver, boolean[] predOver) {
        int tp = 0;
        int predicted = 0;
        int actual = 0;
        for (int i = 0; i < trueOver.length; i++) {
            if (predOver[i]) {
                predicted++;
            }
            if (trueOver[i]) {
                actual++;
            }
            if (trueOver[i] && predOver[i]) {
                tp++;
            }
        }
        return new double[] {predicted == 0 ? Double.NaN : (double) tp / predicted, actual == 0 ? Double.NaN : (double) tp / actual};
    }
}
