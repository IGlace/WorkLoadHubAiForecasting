package com.workloadhub.forecast.features;

import java.util.ArrayList;
import java.util.List;

/**
 * Column names and constants of the feature matrix (spec section 6).
 *
 * <p>Missing values, stated once (spec section 5.2): the matrix stores {@code Double.NaN} where a cell has
 * no value, and XGBoost's histogram trees learn a default direction for it from the training rows that lack
 * it — a blank is not zero and not average, it is its own branch. The cells left blank, and what "nothing to
 * measure" means for each:
 *
 * <table>
 * <caption>Blank cells and their non-blank neighbour</caption>
 * <tr><th>Column</th><th>Blank when</th><th>Was</th></tr>
 * <tr><td>{@code weeks_since_last_arrival}</td><td>the member received no fresh task in the loaded
 * history</td><td>52</td></tr>
 * <tr><td>{@code share_defect_13w}, {@code share_delivery_13w}, {@code share_support_13w},
 * {@code share_high_priority_13w}</td><td>no task was assigned to the member in the 13-week window
 * ({@code arrivals_13w} = 0)</td><td>0.0</td></tr>
 * <tr><td>{@code share_self_picked_13w}, {@code share_assigned_13w}</td><td>no task in the window has a known
 * mode (section 6)</td><td>1/3</td></tr>
 * <tr><td>{@code reopen_rate_13w}</td><td>the member finished no task in the window</td><td>0.0</td></tr>
 * <tr><td>{@code estimate_ratio_13w}</td><td>no finished task in the window has both a positive estimate and
 * positive hours</td><td>1.0</td></tr>
 * <tr><td>{@code cycle_days_13w}</td><td>the member finished no task in the window</td><td>already NaN</td></tr>
 * <tr><td>{@code lag{k}}, {@code arrival_hrs_lag{k}}</td><td>the week is before the member's first
 * row</td><td>already NaN</td></tr>
 * <tr><td>{@code target_h}</td><td>the target week is after the origin</td><td>already NaN</td></tr>
 * </table>
 *
 * <p>Not blank, on purpose: {@code arrivals_13w}, {@code open_tasks}, {@code open_remaining_hrs},
 * {@code overdue_open}, {@code in_progress_tasks}, {@code team_backlog_unassigned_hrs}, {@code proj_active},
 * {@code proj_planning}, {@code due_hrs_h}, {@code planned_hrs_h}, {@code absence_hrs_h} are counts and sums,
 * and zero is their true value when there is nothing. {@code roll_std_*} is 0.0 for a single-week window
 * because one observation has no spread, which is a statement, not an absence of one.
 *
 * <p>Read as of the run day (ruling G of 2026-09-16): {@code Truncation} rewinds assignments, statuses and
 * logs to the row's week, but five task and project fields have no history and are read as they stand today —
 * {@code tasks.reopened_from_done}, {@code projects.status}, {@code tasks.due_date},
 * {@code tasks.original_estimate_hrs} and {@code tasks.planned_week}. {@code reopen_rate_13w},
 * {@code proj_active}, {@code proj_planning}, {@code overdue_open}, {@code due_hrs_h}, {@code planned_hrs_h}
 * and every estimate-based sum carry that hindsight. {@code planned_week} is the fifth of them:
 * {@code Truncation} passes it through unrewound and {@code MemberContext.plannedHours} reads it, and a leader
 * usually sets it shortly before the week it names, so a training row can see a plan made after its own week
 * ended. Accepted on the same grounds as the other four: the field has no history to replay, and the
 * alternative is to drop the columns.
 */
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
        c.add("share_assigned_13w");
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
        c.addAll(CATEGORICAL);
        c.add("tenure_weeks");
        c.add("week_of_year");
        return List.copyOf(c);
    }

    public static String target(int h) {
        return "target_h" + h;
    }

    public static List<String> horizonColumns(int h) {
        return List.of("due_hrs_h" + h, "planned_hrs_h" + h, "working_days_h" + h, "absence_hrs_h" + h, "available_hrs_h" + h);
    }

    /** 45 features: 40 shared, then the five `_h{h}` columns. */
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
