package com.workloadhub.forecast.model;

import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.Features;
import com.workloadhub.forecast.features.MemberWeek;
import java.util.HashMap;
import java.util.Map;

/** Same week last year when known, else the recent four-week mean. The floor every model must beat. */
public final class SeasonalNaive implements ArrivalModel {

    public static final String NAME = "seasonal_naive";
    static final int WEEKS_PER_YEAR = 52;

    private Map<MemberWeek, Double> history;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public SeasonalNaive fit(FeatureMatrix train, int[] horizons) {
        Map<MemberWeek, Double> h = new HashMap<>();
        double[] fresh = train.column(Features.FRESH);
        for (int i = 0; i < train.rowCount(); i++) {
            h.put(train.key(i), fresh[i]);
        }
        history = h;
        return this;
    }

    @Override
    public double[] predict(FeatureMatrix rows, int horizon) {
        if (history == null) {
            throw new IllegalStateException("fit before predict");
        }
        double[] fallback = rows.column("roll_mean_4");
        double[] out = new double[rows.rowCount()];
        for (int i = 0; i < out.length; i++) {
            MemberWeek k = rows.key(i);
            Double v = history.get(new MemberWeek(k.member(), k.week().plusWeeks(horizon).minusWeeks(WEEKS_PER_YEAR)));
            double value = v != null ? v : (Double.isNaN(fallback[i]) ? 0.0 : fallback[i]);
            out[i] = Math.max(0.0, value);
        }
        return out;
    }
}
