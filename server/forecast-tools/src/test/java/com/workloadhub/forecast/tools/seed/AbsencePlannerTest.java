package com.workloadhub.forecast.tools.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class AbsencePlannerTest {

    static SeedConfig cfg() {
        return new SeedConfig(52, LocalDate.of(2026, 9, 6), 1, false, 0);
    }

    static Person person(LocalDate joined, LocalDate left) {
        return new Person(UUID.fromString("30000000-0000-0000-0000-000000000003"), "Eng Three",
                "Calibration Engineer", "PTE / CT2", "CT2", null, "MEMBER", WorkFamily.CALIBRATION, joined, left);
    }

    static SeedCalendar cal() {
        return SeedCalendar.fromHolidayRows(List.of(SeedCalendarTest.holiday("Labour Day", "2026-05-01", "2026-05-01", "CONFIRMED", "NATIONAL")), cfg());
    }

    @Property(tries = 40)
    boolean absencesAreWorkingDaysWhileEmployedAndWithinBounds(@ForAll @LongRange(min = 0, max = 100_000) long seed) {
        SeedCalendar cal = cal();
        Person p = person(cfg().firstMonday(), null);
        AbsencePlanner.Plan plan = AbsencePlanner.plan(p, cal, cfg(), new SeedRandom(seed));
        boolean allWorking = plan.absentDays().stream().allMatch(d -> cal.isWorkingDay(d) && p.employedOn(d)
                && !d.isBefore(cfg().firstMonday()) && !d.isAfter(cfg().lastDay()));
        // two blocks of 5..10 days; the second block is cut short when it runs into the first, so 5 is the
        // floor. Scoped to APPROVED: a PENDING leave is also leave_type PAID_LEAVE (same kind of leave, just
        // not yet decided) and would otherwise inflate this count past its 20-day ceiling.
        long vacation = plan.leaveRows().stream()
                .filter(l -> "PAID_LEAVE".equals(l.get("leave_type")) && "APPROVED".equals(l.get("status")))
                .mapToLong(l -> daysOf(l)).sum();
        long sick = plan.leaveRows().stream().filter(l -> "SICK_LEAVE".equals(l.get("leave_type"))).count();
        boolean approvedInHistory = plan.leaveRows().stream()
                .filter(l -> !LocalDate.parse((String) l.get("start_date")).isAfter(cfg().lastDay()))
                .allMatch(l -> "APPROVED".equals(l.get("status")));
        boolean pendingInFuture = plan.leaveRows().stream()
                .filter(l -> "PENDING".equals(l.get("status")))
                .allMatch(l -> LocalDate.parse((String) l.get("start_date")).isAfter(cfg().lastDay()));
        return allWorking && vacation >= 5 && vacation <= 20 && sick <= 4 && approvedInHistory && pendingInFuture;
    }

    static long daysOf(java.util.LinkedHashMap<String, Object> l) {
        SeedCalendar cal = cal();
        long n = 0;
        for (LocalDate d = LocalDate.parse((String) l.get("start_date")); !d.isAfter(LocalDate.parse((String) l.get("end_date"))); d = d.plusDays(1)) {
            if (cal.isWorkingDay(d)) {
                n++;
            }
        }
        return n;
    }

    @Test
    void hoursPresentFollowsCalendarEmploymentAndAbsence() {
        SeedCalendar cal = cal();
        Person p = person(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 8, 3));
        AbsencePlanner.Plan plan = AbsencePlanner.plan(p, cal, cfg(), new SeedRandom(3));
        assertEquals(0.0, plan.hoursPresent(p, LocalDate.of(2026, 2, 27)), "before joining");
        assertEquals(0.0, plan.hoursPresent(p, LocalDate.of(2026, 8, 3)), "left");
        assertEquals(0.0, plan.hoursPresent(p, LocalDate.of(2026, 5, 1)), "holiday");
        assertEquals(0.0, plan.hoursPresent(p, LocalDate.of(2026, 5, 2)), "Saturday");
        LocalDate anyAbsent = plan.absentDays().iterator().next();
        assertEquals(0.0, plan.hoursPresent(p, anyAbsent));
    }
}
