package com.workloadhub.forecast.eval;

/** Pure metric functions over equal-length arrays; NaN on empty input. */
public final class Metrics {

    private Metrics() {
    }

    public static double mae(double[] y, double[] p) {
        return com.workloadhub.forecast.Numbers.mae(y, p);
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
