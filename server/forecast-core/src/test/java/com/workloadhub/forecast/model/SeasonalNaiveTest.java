package com.workloadhub.forecast.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.Features;
import com.workloadhub.forecast.testing.SyntheticMatrix;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SeasonalNaiveTest {

    @Test
    void usesLastYearsWeekWhenKnownElseTheFourWeekMean() {
        FeatureMatrix m = SyntheticMatrix.arrivals(2, 60, 1);
        LocalDate origin = m.key(m.rowCount() - 1).week();
        FeatureMatrix train = m.filter(k -> !k.week().isAfter(origin.minusWeeks(3)));
        FeatureMatrix test = m.filter(k -> k.week().equals(origin));
        SeasonalNaive naive = new SeasonalNaive();
        naive.fit(train, Features.HORIZONS);
        double[] p1 = naive.predict(test, 1);
        assertEquals(test.rowCount(), p1.length);
        for (int i = 0; i < test.rowCount(); i++) {
            LocalDate lastYear = origin.plusWeeks(1).minusWeeks(52);
            int j = m.keys().indexOf(new com.workloadhub.forecast.features.MemberWeek(test.key(i).member(), lastYear));
            assertEquals(m.get(j, Features.FRESH), p1[i], 1e-9, "same week last year");
        }
        FeatureMatrix shortTrain = m.filter(k -> k.week().isAfter(origin.minusWeeks(20)) && !k.week().isAfter(origin.minusWeeks(3)));
        SeasonalNaive recent = new SeasonalNaive();
        recent.fit(shortTrain, Features.HORIZONS);
        assertArrayEquals(test.column("roll_mean_4"), recent.predict(test, 2), 1e-9);
        assertEquals("seasonal_naive", naive.name());
        assertThrows(IllegalStateException.class, () -> new SeasonalNaive().predict(test, 1));
    }
}
