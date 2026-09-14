package com.workloadhub.forecast.seed;

import java.time.DayOfWeek;

/**
 * How one member works and records it: the shape of their week, how much of what they work they log, and how
 * often they run long.
 *
 * <p>Drawn once per member and fixed for the whole history, the way {@code ratio} in {@link WorkQueue} is.
 * The three are deliberately independent of the estimate ratio: estimation bias and logging discipline are
 * different causes of the same visible gap, and a forecast of logged hours can only be told apart from a
 * forecast of estimates when they can move separately.
 *
 * @param weekdayWeights Monday to Friday, mean 1, so a full week is still five day-lengths
 * @param discipline the fraction of worked hours the member actually records
 * @param overtimeChance the probability that a given day runs long
 * @param overtimeFactor how much longer such a day runs
 */
public record WorkStyle(double[] weekdayWeights, double discipline, double overtimeChance, double overtimeFactor) {

    /** A quiet Friday is worth about three quarters of a busy Monday; beyond that the shape stops being credible. */
    private static final double WEEKDAY_SIGMA = 0.18;
    private static final double DISCIPLINE_MIN = 0.70;
    private static final double DISCIPLINE_MAX = 1.00;
    /** One member in six runs long often; the rest rarely. */
    private static final double HEAVY_SHARE = 0.17;
    private static final double NORMAL_OVERTIME_FACTOR = 1.20;
    /** The largest factor {@link #draw} ever hands out, for a "heavy" member; the bound a per-day-hours
     * check against presence must use once a day can run long. */
    public static final double MAX_OVERTIME_FACTOR = 1.35;

    public static WorkStyle draw(SeedRandom rnd) {
        double[] w = new double[5];
        double sum = 0;
        for (int i = 0; i < 5; i++) {
            w[i] = Math.max(0.4, 1.0 + WEEKDAY_SIGMA * rnd.gaussian());
            sum += w[i];
        }
        for (int i = 0; i < 5; i++) {
            w[i] = w[i] * 5.0 / sum;
        }
        double discipline = Math.max(DISCIPLINE_MIN, Math.min(DISCIPLINE_MAX, 0.95 + 0.08 * rnd.gaussian()));
        boolean heavy = rnd.chance(HEAVY_SHARE);
        return new WorkStyle(w, discipline, heavy ? 0.30 : 0.06, heavy ? MAX_OVERTIME_FACTOR : NORMAL_OVERTIME_FACTOR);
    }

    /** The hours this member works on one present day, before any discipline is applied. */
    public double hoursOn(DayOfWeek dow, double dayHours, SeedRandom rnd) {
        double base = dayHours * weekdayWeights[dow.getValue() - 1];
        return rnd.chance(overtimeChance) ? base * overtimeFactor : base;
    }

    /** The hours the member records, of the hours they worked. */
    public double logged(double worked) {
        return worked * discipline;
    }
}
