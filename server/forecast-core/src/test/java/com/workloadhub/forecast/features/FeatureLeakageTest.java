package com.workloadhub.forecast.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.Truncation;
import com.workloadhub.forecast.testing.SeededData;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every non-target column of a row at week w must be computable from the database as it stood at the end of w. */
class FeatureLeakageTest {

    static FeatureMatrix build(ForecastData data, LocalDate origin) {
        return new FeatureBuilder(data, Lifecycle.derive(data), WorkingCalendar.fromHolidays(data.holidays()), new CapacityRule(40))
                .build(data.members(), origin);
    }

    @Test
    void rowsAtAPastWeekMatchTheRowsBuiltFromTheTruncatedDatabase() {
        ForecastData full = SeededData.data();
        LocalDate origin = Weeks.lastCompleteWeek(SeededData.asOf());
        LocalDate past = origin.minusWeeks(4);
        FeatureMatrix fromFull = build(full, origin).filter(k -> k.week().equals(past));
        FeatureMatrix fromCut = build(Truncation.at(full, past.plusDays(6)), past).filter(k -> k.week().equals(past));
        assertEquals(fromFull.keys(), fromCut.keys());
        List<String> columns = Features.allColumns().stream().filter(c -> !c.startsWith("target_h")).toList();
        int compared = 0;
        for (int i = 0; i < fromFull.rowCount(); i++) {
            for (String c : columns) {
                double a = fromFull.get(i, c);
                double b = fromCut.get(i, c);
                assertTrue(Double.isNaN(a) == Double.isNaN(b) && (Double.isNaN(a) || Math.abs(a - b) < 1e-9),
                        c + " at " + fromFull.key(i) + ": " + a + " vs " + b);
                compared++;
            }
        }
        assertTrue(compared > 0);
    }

    @Test
    void everyFeatureColumnIsPopulatedOnTheSeed() {
        ForecastData data = SeededData.data();
        FeatureMatrix m = build(data, Weeks.lastCompleteWeek(SeededData.asOf()));
        for (int h : Features.HORIZONS) {
            List<String> cols = Features.featureColumns(h);
            assertEquals(cols, m.nonEmptyColumns(cols), "all-NaN columns at horizon " + h);
        }
    }
}
