package com.workloadhub.forecast.capacity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.LeaveRow;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

class LeaveDaysPropertyTest {

    static final UUID M = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final double FULL = 8.8;
    static final LocalDate BASE = LocalDate.of(2026, 8, 31);
    static final WorkingCalendar CAL = WorkingCalendar.fromHolidays(List.of(
            new HolidayRow(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10), true, true, "Holiday")));

    @Property(tries = 300)
    void hoursLandOnWorkingDaysInsideThePeriodAndSumToTheDealtTotal(
            @ForAll @IntRange(min = 0, max = 30) int startOffset, @ForAll @IntRange(min = 0, max = 14) int length,
            @ForAll @DoubleRange(min = -5, max = 120) double total, @ForAll boolean hoursKnown,
            @ForAll @IntRange(min = 0, max = 2) int times) {
        LocalDate start = BASE.plusDays(startOffset);
        LocalDate end = start.plusDays(length);
        LocalTime begin = times == 1 ? LocalTime.of(13, 0) : null;
        LocalTime finish = times == 2 ? LocalTime.of(12, 0) : null;
        LeaveRow l = new LeaveRow(M, start, end, begin, finish, hoursKnown ? total : null, "APPROVED", "PAID_LEAVE");
        NavigableMap<LocalDate, Double> days = LeaveDays.expand(List.of(l), CAL, FULL).getOrDefault(M, new TreeMap<>());
        int n = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (CAL.isWorkingDay(d)) {
                n++;
            }
        }
        for (Map.Entry<LocalDate, Double> e : days.entrySet()) {
            assertTrue(CAL.isWorkingDay(e.getKey()) && !e.getKey().isBefore(start) && !e.getKey().isAfter(end), "day " + e.getKey());
            assertTrue(e.getValue() > 0 && e.getValue() <= FULL + 1e-9, "hours " + e.getValue());
        }
        double expected = (!hoursKnown || total <= 0) ? n * FULL : Math.min(total, n * FULL);
        assertEquals(expected, days.values().stream().mapToDouble(Double::doubleValue).sum(), 0.02, "total dealt");
        // A total that is an exact number of full days has no partial day at all (the days it does not fill
        // get zero and are dropped), so the full-day count is floored with a tolerance: 26.4 / 8.8 is
        // 2.9999999999999996 in binary, and a bare floor reads an exact three-day total as two full days plus
        // an 8.8-hour "remainder", then demands that a full day be partial. Found by this property on
        // 2026-09-18 (26.4 hours over four working days); the dealing itself was right.
        double fullDays = Math.floor(total / FULL + 1e-9);
        double remainder = hoursKnown && total > 0 && total < n * FULL - 1e-9 ? total - fullDays * FULL : 0.0;
        if (remainder > 1e-6 && n > 1) {
            LocalDate partial = times == 1 ? days.firstKey() : days.lastKey();
            assertTrue(days.get(partial) < FULL, "the partial day is the first with begin_time only, else the last");
        }
    }
}
