package com.workloadhub.forecast.seed;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Working days of the history: weekdays minus confirmed, active holidays. */
public final class SeedCalendar {

    private final Set<LocalDate> holidays;
    private final List<LinkedHashMap<String, Object>> holidayRows;

    private SeedCalendar(Set<LocalDate> holidays, List<LinkedHashMap<String, Object>> holidayRows) {
        this.holidays = holidays;
        this.holidayRows = holidayRows;
    }

    public static SeedCalendar fromHolidayRows(List<LinkedHashMap<String, Object>> rows, SeedConfig cfg) {
        List<LinkedHashMap<String, Object>> all = new ArrayList<>(rows);
        Set<Integer> yearsWithRows = new HashSet<>();
        for (LinkedHashMap<String, Object> h : rows) {
            yearsWithRows.add(LocalDate.parse((String) h.get("start_date")).getYear());
        }
        for (int year = cfg.firstMonday().getYear(); year <= cfg.lastDay().getYear(); year++) {
            if (yearsWithRows.contains(year)) {
                continue;
            }
            for (LinkedHashMap<String, Object> h : rows) {
                if (!"NATIONAL".equals(h.get("type")) || !"CONFIRMED".equals(h.get("status"))) {
                    continue;
                }
                LocalDate start = LocalDate.parse((String) h.get("start_date")).withYear(year);
                LocalDate end = LocalDate.parse((String) h.get("end_date")).withYear(year);
                LinkedHashMap<String, Object> copy = new LinkedHashMap<>(h);
                copy.put("id", java.util.UUID.nameUUIDFromBytes((h.get("id") + ":" + year).getBytes()).toString());
                copy.put("start_date", start.toString());
                copy.put("end_date", end.toString());
                all.add(copy);
            }
        }
        Set<LocalDate> days = new TreeSet<>();
        for (LinkedHashMap<String, Object> h : all) {
            boolean active = h.get("active") == null || Boolean.TRUE.equals(h.get("active")) || Long.valueOf(1).equals(h.get("active"));
            if (!"CONFIRMED".equals(h.get("status")) || !active) {
                continue;
            }
            LocalDate d = LocalDate.parse((String) h.get("start_date"));
            LocalDate end = LocalDate.parse((String) h.get("end_date"));
            for (; !d.isAfter(end); d = d.plusDays(1)) {
                days.add(d);
            }
        }
        return new SeedCalendar(days, all);
    }

    public Set<LocalDate> holidays() {
        return holidays;
    }

    public List<LinkedHashMap<String, Object>> holidayRows() {
        return holidayRows;
    }

    public boolean isWorkingDay(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(d);
    }

    public List<LocalDate> workingDaysOf(LocalDate monday) {
        List<LocalDate> out = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LocalDate d = monday.plusDays(i);
            if (isWorkingDay(d)) {
                out.add(d);
            }
        }
        return out;
    }

    public int workingDays(LocalDate monday) {
        return workingDaysOf(monday).size();
    }
}
