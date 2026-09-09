package com.workloadhub.forecast.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.rows.HolidayRow;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkingCalendarTest {

    static WorkingCalendar cal() {
        return WorkingCalendar.fromHolidays(List.of(
                new HolidayRow(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1), true, true, "Labour Day"),
                new HolidayRow(LocalDate.of(2026, 3, 20), LocalDate.of(2026, 3, 21), false, true, "Pending"),
                new HolidayRow(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1), true, false, "Inactive")));
    }

    @Test
    void onlyConfirmedActiveHolidaysCount() {
        WorkingCalendar cal = cal();
        assertEquals(Set.of(LocalDate.of(2026, 5, 1)), cal.holidays());
        assertFalse(cal.isWorkingDay(LocalDate.of(2026, 5, 1)));
        assertTrue(cal.isWorkingDay(LocalDate.of(2026, 3, 20)));
        assertTrue(cal.isWorkingDay(LocalDate.of(2026, 6, 1)));
        assertFalse(cal.isWorkingDay(LocalDate.of(2026, 5, 2)));
        assertEquals(4, cal.workingDaysInWeek(LocalDate.of(2026, 4, 27)));
    }

    @Test
    void workingDaysSkipOffDays() {
        List<LocalDate> days = cal().workingDays(LocalDate.of(2026, 4, 27), LocalDate.of(2026, 5, 3), Set.of(LocalDate.of(2026, 4, 28)));
        assertEquals(List.of(LocalDate.of(2026, 4, 27), LocalDate.of(2026, 4, 29), LocalDate.of(2026, 4, 30)), days);
    }
}
