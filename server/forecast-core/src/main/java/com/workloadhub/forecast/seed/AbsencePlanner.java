package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Vacation blocks, sick days and the resulting presence per day for one person. */
public final class AbsencePlanner {

    public record Plan(Set<LocalDate> absentDays, List<LinkedHashMap<String, Object>> absenceRows,
            List<LinkedHashMap<String, Object>> leaveRows, SeedCalendar calendar) {

        public double hoursPresent(Person p, LocalDate day) {
            return calendar.isWorkingDay(day) && p.employedOn(day) && !absentDays.contains(day) ? 8.0 : 0.0;
        }

        public double absenceHours(LocalDate monday) {
            int n = 0;
            for (LocalDate d : calendar.workingDaysOf(monday)) {
                if (absentDays.contains(d)) {
                    n++;
                }
            }
            return 8.0 * n;
        }
    }

    private AbsencePlanner() {
    }

    public static Plan plan(Person p, SeedCalendar cal, SeedConfig cfg, SeedRandom rnd) {
        Set<LocalDate> absent = new TreeSet<>();
        List<LinkedHashMap<String, Object>> absenceRows = new ArrayList<>();
        List<LinkedHashMap<String, Object>> leaveRows = new ArrayList<>();
        List<LocalDate> workingDays = new ArrayList<>();
        for (LocalDate d = cfg.firstMonday(); !d.isAfter(cfg.lastDay()); d = d.plusDays(1)) {
            if (cal.isWorkingDay(d) && p.employedOn(d)) {
                workingDays.add(d);
            }
        }
        if (workingDays.isEmpty()) {
            return new Plan(absent, absenceRows, leaveRows, cal);
        }
        // two vacation blocks per 52 weeks, scaled to the history length, at least one
        int blocks = Math.max(1, Math.round(2f * cfg.weeks() / 52f));
        for (int b = 0; b < blocks; b++) {
            int length = rnd.between(5, 10);
            int start = pickVacationStart(workingDays, rnd);
            List<LocalDate> block = new ArrayList<>();
            for (int i = start; i < workingDays.size() && block.size() < length; i++) {
                LocalDate d = workingDays.get(i);
                if (absent.contains(d)) {
                    break;
                }
                block.add(d);
            }
            if (block.isEmpty()) {
                continue;
            }
            addLeave(p, block, "VACATION", "PAID_LEAVE", "Annual leave", absent, absenceRows, leaveRows, rnd);
        }
        int sickDays = rnd.between(0, 4);
        for (int s = 0; s < sickDays; s++) {
            LocalDate d = workingDays.get(rnd.between(0, workingDays.size() - 1));
            if (!absent.contains(d)) {
                addLeave(p, List.of(d), "SICK_LEAVE", "SICK_LEAVE", "Sick", absent, absenceRows, leaveRows, rnd);
            }
        }
        return new Plan(absent, absenceRows, leaveRows, cal);
    }

    /** 45 % start in ISO weeks 30..34, 20 % in weeks 51..2, the rest anywhere. */
    static int pickVacationStart(List<LocalDate> workingDays, SeedRandom rnd) {
        double x = rnd.uniform(0, 1);
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < workingDays.size(); i++) {
            int week = workingDays.get(i).get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            boolean summer = week >= 30 && week <= 34;
            boolean winter = week >= 51 || week <= 2;
            if ((x < 0.45 && summer) || (x >= 0.45 && x < 0.65 && winter) || x >= 0.65) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            return rnd.between(0, workingDays.size() - 1);
        }
        return candidates.get(rnd.between(0, candidates.size() - 1));
    }

    static void addLeave(Person p, List<LocalDate> days, String absenceType, String leaveType, String note,
            Set<LocalDate> absent, List<LinkedHashMap<String, Object>> absenceRows,
            List<LinkedHashMap<String, Object>> leaveRows, SeedRandom rnd) {
        String created = days.get(0).minusDays(14).atTime(10, 0).toString();
        for (LocalDate d : days) {
            absent.add(d);
            LinkedHashMap<String, Object> a = new LinkedHashMap<>();
            a.put("id", rnd.uuid().toString());
            a.put("date", d.toString());
            a.put("note", note);
            a.put("type", absenceType);
            a.put("hours", 8.0);
            a.put("user_id", p.id().toString());
            a.put("created_at", created);
            a.put("updated_at", created);
            absenceRows.add(a);
        }
        LinkedHashMap<String, Object> l = new LinkedHashMap<>();
        l.put("absence_hours", 8.0 * days.size());
        l.put("begin_time", null);
        l.put("end_date", days.get(days.size() - 1).toString());
        l.put("end_time", null);
        l.put("start_date", days.get(0).toString());
        l.put("created_at", created);
        l.put("updated_at", created);
        l.put("employee_id", p.id().toString());
        l.put("id", rnd.uuid().toString());
        l.put("note", note);
        l.put("leave_type", leaveType);
        l.put("processor", null);
        l.put("status", "APPROVED");
        leaveRows.add(l);
    }
}
