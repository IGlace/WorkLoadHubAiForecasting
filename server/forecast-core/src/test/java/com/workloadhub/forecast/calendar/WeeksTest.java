package com.workloadhub.forecast.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.time.api.constraints.DateRange;
import org.junit.jupiter.api.Test;

class WeeksTest {

    @Property
    boolean mondayOfIsAMondayNotAfterTheDay(@ForAll @DateRange(min = "2020-01-01", max = "2030-12-31") LocalDate d) {
        LocalDate m = Weeks.mondayOf(d);
        return m.getDayOfWeek().getValue() == 1 && !m.isAfter(d) && d.toEpochDay() - m.toEpochDay() < 7;
    }

    @Test
    void forecastWeeksStartThisWeekOnAMondayElseNextWeek() {
        LocalDate[] onMonday = Weeks.forecastWeeks(LocalDate.of(2026, 9, 7));
        assertEquals(LocalDate.of(2026, 9, 7), onMonday[0]);
        assertEquals(LocalDate.of(2026, 9, 14), onMonday[1]);
        LocalDate[] onSunday = Weeks.forecastWeeks(LocalDate.of(2026, 9, 6));
        assertEquals(LocalDate.of(2026, 9, 7), onSunday[0]);
        assertEquals(LocalDate.of(2026, 8, 24), Weeks.lastCompleteWeek(LocalDate.of(2026, 9, 6)));
    }

    @Test
    void betweenListsEveryMondayInclusive() {
        List<LocalDate> weeks = Weeks.between(LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 24));
        assertEquals(List.of(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 24)), weeks);
        assertEquals(3, Weeks.weeksBetween(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 24)));
        assertEquals(32, Weeks.isoWeek(LocalDate.of(2026, 8, 3)));
    }
}
