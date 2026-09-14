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
    /** The largest weekday weight {@link #draw} ever hands out. Renormalising five weights to sum to 5
     * does not, by itself, stop any single one of them running away with most of the week's weight (the
     * other four can sit at their 0.4 floor); a day that inherits an unbounded weight on top of overtime
     * is not credible data (see the class comment) and would blunt the very overload signal this seed
     * exists to produce. Enforced by {@link #capAndRedistribute} AFTER renormalising, not before: clamping
     * the raw draws first does not work, because renormalising can push an already-clamped weight back
     * above the cap. */
    public static final double MAX_WEEKDAY_WEIGHT = 1.4;

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
        capAndRedistribute(w);
        double discipline = Math.max(DISCIPLINE_MIN, Math.min(DISCIPLINE_MAX, 0.95 + 0.08 * rnd.gaussian()));
        boolean heavy = rnd.chance(HEAVY_SHARE);
        return new WorkStyle(w, discipline, heavy ? 0.30 : 0.06, heavy ? MAX_OVERTIME_FACTOR : NORMAL_OVERTIME_FACTOR);
    }

    /**
     * Clip-and-redistribute, in place: any weight over {@link #MAX_WEEKDAY_WEIGHT} is pinned to the cap
     * and the excess it gives up is shared out, proportionally, across the weights still under the cap.
     * That can push one of those over the cap in turn, so the pass repeats until none is left over; it
     * always terminates (at most four weights can ever be pinned to 1.4 and still leave the fifth summing
     * with them to 5) and it keeps the sum at exactly 5, since every unit of excess removed from a capped
     * weight is added straight back to an uncapped one.
     */
    private static void capAndRedistribute(double[] w) {
        boolean[] capped = new boolean[w.length];
        for (int pass = 0; pass < w.length; pass++) {
            double excess = 0;
            boolean anyNewlyCapped = false;
            for (int i = 0; i < w.length; i++) {
                if (!capped[i] && w[i] > MAX_WEEKDAY_WEIGHT) {
                    excess += w[i] - MAX_WEEKDAY_WEIGHT;
                    w[i] = MAX_WEEKDAY_WEIGHT;
                    capped[i] = true;
                    anyNewlyCapped = true;
                }
            }
            if (!anyNewlyCapped) {
                return;
            }
            double remaining = 0;
            for (int i = 0; i < w.length; i++) {
                if (!capped[i]) {
                    remaining += w[i];
                }
            }
            for (int i = 0; i < w.length; i++) {
                if (!capped[i]) {
                    w[i] += excess * (w[i] / remaining);
                }
            }
        }
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
