package com.workloadhub.forecast.calendar;

import com.workloadhub.forecast.data.rows.HolidayRow;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Working days: Monday to Friday minus confirmed, active holidays. */
public final class WorkingCalendar {

    private final Set<LocalDate> holidays;

    private WorkingCalendar(Set<LocalDate> holidays) {
        this.holidays = holidays;
    }

    public static WorkingCalendar fromHolidays(List<HolidayRow> rows) {
        Set<LocalDate> days = new TreeSet<>();
        for (HolidayRow h : rows) {
            if (!h.confirmed() || !h.active()) {
                continue;
            }
            for (LocalDate d = h.start(); !d.isAfter(h.end()); d = d.plusDays(1)) {
                days.add(d);
            }
        }
        return new WorkingCalendar(days);
    }

    public Set<LocalDate> holidays() {
        return holidays;
    }

    public boolean isWorkingDay(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(d);
    }

    public List<LocalDate> workingDays(LocalDate start, LocalDate end, Set<LocalDate> off) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (isWorkingDay(d) && !off.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    public int workingDaysInWeek(LocalDate monday) {
        return workingDays(monday, monday.plusDays(6), Set.of()).size();
    }
}
