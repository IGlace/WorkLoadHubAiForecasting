package com.workloadhub.forecast.calendar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.time.api.constraints.DateRange;
import org.junit.jupiter.api.Test;

class HorizonTest {

    @Property
    boolean tenDistinctWeekdaysAfterTheRunDayInTwoContiguousWindows(@ForAll @DateRange(min = "2020-01-01", max = "2030-12-31") LocalDate asOf) {
        List<ForecastWindow> w = Horizon.windows(asOf);
        List<LocalDate> days = Horizon.days(w);
        boolean shape = w.size() == 2 && days.size() == 10 && new HashSet<>(days).size() == 10
                && w.get(0).weekdays().size() == 5 && w.get(1).weekdays().size() == 5 && w.get(0).index() == 1 && w.get(1).index() == 2;
        boolean order = days.get(0).equals(Horizon.firstDay(asOf)) && days.get(0).isAfter(asOf);
        boolean noWeekend = days.stream().noneMatch(Horizon::isWeekend);
        boolean contiguous = true;
        for (int i = 1; i < days.size(); i++) {
            LocalDate next = days.get(i - 1).plusDays(1);
            while (Horizon.isWeekend(next)) {
                next = next.plusDays(1);
            }
            contiguous &= days.get(i).equals(next);
        }
        boolean ends = w.get(0).start().equals(days.get(0)) && w.get(0).end().equals(days.get(4)) && w.get(1).start().equals(days.get(5))
                && w.get(1).end().equals(days.get(9));
        return shape && order && noWeekend && contiguous && ends;
    }

    @Property
    boolean aFridayOrWeekendRunStartsOnMondayOthersTomorrow(@ForAll @DateRange(min = "2020-01-01", max = "2030-12-31") LocalDate asOf) {
        LocalDate first = Horizon.firstDay(asOf);
        DayOfWeek dow = asOf.getDayOfWeek();
        boolean lateInWeek = dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
        return lateInWeek ? first.getDayOfWeek() == DayOfWeek.MONDAY && first.toEpochDay() - asOf.toEpochDay() <= 3 : first.equals(asOf.plusDays(1));
    }

    @Test
    void theWorkedExamplesOfTheDesign() {
        List<ForecastWindow> wed = Horizon.windows(LocalDate.of(2026, 9, 9));
        assertEquals(LocalDate.of(2026, 9, 10), wed.get(0).start());
        assertEquals(LocalDate.of(2026, 9, 16), wed.get(0).end());
        assertEquals(LocalDate.of(2026, 9, 17), wed.get(1).start());
        assertEquals(LocalDate.of(2026, 9, 23), wed.get(1).end());
        assertEquals(List.of(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 9, 16)), wed.get(0).weekdays());
        assertTrue(wed.get(0).contains(LocalDate.of(2026, 9, 14)) && !wed.get(0).contains(LocalDate.of(2026, 9, 12)));
        for (LocalDate asOf : List.of(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 13))) {
            List<ForecastWindow> w = Horizon.windows(asOf);
            assertEquals(LocalDate.of(2026, 9, 14), w.get(0).start(), asOf.toString());
            assertEquals(LocalDate.of(2026, 9, 18), w.get(0).end());
            assertEquals(LocalDate.of(2026, 9, 21), w.get(1).start());
            assertEquals(LocalDate.of(2026, 9, 25), w.get(1).end());
        }
        List<ForecastWindow> mon = Horizon.windows(LocalDate.of(2026, 9, 7));
        assertEquals(LocalDate.of(2026, 9, 8), mon.get(0).start());
        assertEquals(LocalDate.of(2026, 9, 14), mon.get(0).end());
        assertEquals(LocalDate.of(2026, 9, 21), mon.get(1).end());
    }

    @Test
    void horizonsAreTheWeeksTheDaysTouchCountedFromTheOrigin() {
        assertArrayEquals(new int[] {1, 2, 3}, Horizon.horizons(Weeks.lastCompleteWeek(LocalDate.of(2026, 9, 9)), Horizon.windows(LocalDate.of(2026, 9, 9))));
        assertArrayEquals(new int[] {1, 2, 3}, Horizon.horizons(Weeks.lastCompleteWeek(LocalDate.of(2026, 9, 7)), Horizon.windows(LocalDate.of(2026, 9, 7))));
        assertArrayEquals(new int[] {2, 3}, Horizon.horizons(Weeks.lastCompleteWeek(LocalDate.of(2026, 9, 11)), Horizon.windows(LocalDate.of(2026, 9, 11))));
        assertArrayEquals(new int[] {2, 3}, Horizon.horizons(Weeks.lastCompleteWeek(LocalDate.of(2026, 9, 6)), Horizon.windows(LocalDate.of(2026, 9, 6))));
    }
}
