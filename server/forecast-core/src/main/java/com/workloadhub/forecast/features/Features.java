package com.workloadhub.forecast.features;

import java.util.ArrayList;
import java.util.List;

/** Column names and constants of the feature matrix (spec section 6). */
public final class Features {

    /**
     * Note the off-by-one, which is deliberate and load-bearing: {@code ownHistory} computes
     * {@code j = i - (lag - 1)}, so {@code lag1} is the row's OWN week, not the week before it. It is
     * leakage-safe because every target is a strictly later week. {@code arrival_hrs_lag1..4} follow the same
     * convention so that one meaning of "lag 1" holds across the whole matrix.
     */
    public static final int[] LAGS = {1, 2, 3, 4, 8, 13};
    public static final int[] ROLL_WINDOWS = {4, 8, 13};
    public static final int WINDOW_13 = 13;
    /** Weeks of history a run loads before the origin: a year for the floor plus the longest window. */
    public static final int HISTORY_WEEKS = 65;
    public static final List<String> CATEGORICAL = List.of("member_id", "team_id", "role", "job_title");

    private static final List<String> SHARED = build();

    private Features() {
    }

    private static List<String> build() {
        List<String> c = new ArrayList<>();
        for (int lag : LAGS) {
            c.add("lag" + lag);
        }
        for (int w : ROLL_WINDOWS) {
            c.add("roll_mean_" + w);
            c.add("roll_std_" + w);
        }
        c.add("weeks_since_last_arrival");
        c.add("arrivals_13w");
        c.add("share_defect_13w");
        c.add("share_delivery_13w");
        c.add("share_support_13w");
        c.add("share_high_priority_13w");
        c.add("share_self_picked_13w");
        c.add("share_manual_13w");
        c.add("share_project_13w");
        c.add("reopen_rate_13w");
        for (int k = 1; k <= 4; k++) {
            c.add("arrival_hrs_lag" + k);
        }
        c.add("open_tasks");
        c.add("open_remaining_hrs");
        c.add("overdue_open");
        c.add("in_progress_tasks");
        c.add("estimate_ratio_13w");
        c.add("cycle_days_13w");
        c.add("team_backlog_unassigned_hrs");
        c.add("proj_active");
        c.add("proj_planning");
        c.add("proj_first_due_weeks");
        c.addAll(CATEGORICAL);
        c.add("tenure_weeks");
        c.add("week_of_year");
        return List.copyOf(c);
    }

    public static String target(int h) {
        return "target_h" + h;
    }

    public static List<String> horizonColumns(int h) {
        return List.of("due_hrs_h" + h, "working_days_h" + h, "absence_hrs_h" + h, "available_hrs_h" + h);
    }

    /** The 46 features of one horizon: 42 shared columns, then the four `_h{h}` columns. */
    public static List<String> featureColumns(int h) {
        List<String> c = new ArrayList<>(SHARED);
        c.addAll(horizonColumns(h));
        return List.copyOf(c);
    }

    /** The horizons a run of this many windows fits: 1 to windows + 1. */
    public static int[] horizons(int windows) {
        int[] out = new int[windows + 1];
        for (int i = 0; i < out.length; i++) {
            out[i] = i + 1;
        }
        return out;
    }

    /** Every stored column for a matrix built at this window count: shared features, per-horizon features and targets. */
    public static List<String> allColumns(int windows) {
        List<String> c = new ArrayList<>(SHARED);
        for (int h : horizons(windows)) {
            c.addAll(horizonColumns(h));
        }
        for (int h : horizons(windows)) {
            c.add(target(h));
        }
        return List.copyOf(c);
    }

    public static boolean isCategorical(String column) {
        return CATEGORICAL.contains(column);
    }
}
