package com.workloadhub.forecast.testing;

import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.Features;
import com.workloadhub.forecast.features.MemberWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** A feature matrix with a planted level-plus-season signal, for model and backtest tests. */
public final class SyntheticMatrix {

    public static final LocalDate FIRST_WEEK = LocalDate.of(2025, 6, 2);

    private SyntheticMatrix() {
    }

    public static FeatureMatrix arrivals(int members, int weeks, long seed) {
        Random rnd = new Random(seed);
        List<String> columns = Features.allColumns();
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            col.put(columns.get(i), i);
        }
        List<String> memberIds = new ArrayList<>();
        for (int m = 0; m < members; m++) {
            memberIds.add(new UUID(0x3000_0000_0000_0000L, m + 1).toString());
        }
        List<MemberWeek> keys = new ArrayList<>();
        List<double[]> rows = new ArrayList<>();
        for (int m = 0; m < members; m++) {
            double level = 2 + rnd.nextDouble() * 28;
            double[] f = new double[weeks];
            for (int w = 0; w < weeks; w++) {
                f[w] = Math.max(0.0, level + 6 * Math.sin(2 * Math.PI * w / 9.0) + rnd.nextGaussian() * 1.5);
            }
            for (int i = 0; i < weeks; i++) {
                double[] r = new double[columns.size()];
                Arrays.fill(r, Double.NaN);
                for (int lag : Features.LAGS) {
                    int j = i - (lag - 1);
                    r[col.get("lag" + lag)] = j >= 0 ? f[j] : Double.NaN;
                }
                for (int win : Features.ROLL_WINDOWS) {
                    int from = Math.max(0, i - win + 1);
                    double sum = 0;
                    for (int j = from; j <= i; j++) {
                        sum += f[j];
                    }
                    r[col.get("roll_mean_" + win)] = sum / (i - from + 1);
                    r[col.get("roll_std_" + win)] = 0.0;
                }
                r[col.get("weeks_since_last_arrival")] = 0.0;
                r[col.get("member_id")] = m;
                r[col.get("team_id")] = m % 3;
                r[col.get("role")] = 0;
                r[col.get("job_title")] = 0;
                r[col.get("tenure_weeks")] = i;
                r[col.get("week_of_year")] = FIRST_WEEK.plusWeeks(i).get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                for (int h : Features.HORIZONS) {
                    r[col.get(Features.target(h))] = i + h < weeks ? f[i + h] : Double.NaN;
                    r[col.get("working_days_h" + h)] = 5;
                }
                r[col.get(Features.FRESH)] = f[i];
                r[col.get(Features.EST)] = f[i];
                keys.add(new MemberWeek(UUID.fromString(memberIds.get(m)), FIRST_WEEK.plusWeeks(i)));
                rows.add(r);
            }
        }
        Map<String, List<String>> books = new HashMap<>();
        books.put("member_id", memberIds);
        books.put("team_id", List.of("t0", "t1", "t2"));
        books.put("role", List.of("MEMBER"));
        books.put("job_title", List.of("(none)"));
        return FeatureMatrix.of(columns, keys, rows.toArray(double[][]::new), books);
    }
}
