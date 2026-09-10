package com.workloadhub.forecast.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.features.MemberWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

class EffortModelPropertyTest {

    @Property
    void shrinkStaysBetweenTheMeanAndThePrior(@ForAll @IntRange(min = 0, max = 50) int n, @ForAll @DoubleRange(min = 0.1, max = 5) double mean,
            @ForAll @DoubleRange(min = 0.1, max = 5) double prior) {
        double s = EffortModel.shrink(n, mean, prior, EffortModel.SHRINK_K);
        assertTrue(s >= Math.min(mean, prior) - 1e-12 && s <= Math.max(mean, prior) + 1e-12);
        if (n == 0) {
            assertEquals(prior, s, 1e-12);
        }
    }

    @Property
    void placedNewHoursAreConservedAndLandOnOrAfterTheWeek(@ForAll @DoubleRange(min = 0, max = 80) double est, @ForAll @IntRange(min = 0, max = 200) int weekOffset) {
        LocalDate week = LocalDate.of(2026, 1, 5).plusWeeks(weekOffset);
        UUID member = UUID.fromString("30000000-0000-0000-0000-000000000001");
        EffortModel empty = EffortModel.fit(
                com.workloadhub.forecast.lifecycle.Lifecycle.derive(com.workloadhub.forecast.testing.TestData.data(List.of(), List.of(), List.of(), List.of())),
                com.workloadhub.forecast.testing.TestData.data(List.of(), List.of(), List.of(), List.of()));
        SortedMap<MemberWeek, Double> placed = EffortModel.placeNewArrivals(Map.of(new MemberWeek(member, week), est), empty,
                id -> UUID.randomUUID(), id -> Set.of(), WorkingCalendar.fromHolidays(List.of()));
        double total = placed.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(est * empty.estimateRatio(member, null, null), total, 1e-9);
        assertTrue(placed.keySet().stream().allMatch(k -> !k.week().isBefore(week)));
        assertTrue(placed.values().stream().allMatch(v -> v >= 0));
    }
}
