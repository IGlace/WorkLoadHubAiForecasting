package com.workloadhub.forecast;

/**
 * The arithmetic every part of the module shares: one rounding, one error measure, one scaled error measure.
 *
 * <p>It lives in the root package rather than in {@code eval/Metrics}, the obvious home, because {@code eval}
 * already depends on {@code backtest}: moving {@code Backtest.mae} into {@code Metrics} would close a package
 * cycle. Everything already imports the root package.
 */
public final class Numbers {

    private Numbers() {
    }

    /** Two decimals, half away from zero, as every stored and reported figure is rounded. */
    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Mean absolute error in the unit of the inputs; NaN on empty input. */
    public static double mae(double[] y, double[] yHat) {
        if (y.length == 0) {
            return Double.NaN;
        }
        double s = 0;
        for (int i = 0; i < y.length; i++) {
            s += Math.abs(y[i] - yHat[i]);
        }
        return s / y.length;
    }

    /** Mean absolute error over the naive's mean absolute error; NaN when the naive is exact. */
    public static double mase(double[] y, double[] yHat, double[] yNaive) {
        double num = 0;
        double den = 0;
        for (int i = 0; i < y.length; i++) {
            num += Math.abs(y[i] - yHat[i]);
            den += Math.abs(y[i] - yNaive[i]);
        }
        return den == 0.0 ? Double.NaN : num / den;
    }
}
