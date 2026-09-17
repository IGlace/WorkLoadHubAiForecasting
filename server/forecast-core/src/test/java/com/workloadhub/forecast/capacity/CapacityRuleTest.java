package com.workloadhub.forecast.capacity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.LeaveRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.data.rows.UserRef;
import com.workloadhub.forecast.testing.SeededData;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class CapacityRuleTest {

    static final UUID M = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final UUID T = UUID.fromString("40000000-0000-0000-0000-000000000001");
    static final LocalDate MON = LocalDate.of(2026, 4, 27);   // the week of Labour Day (Friday 1 May): four working days

    static ForecastData data(List<LeaveRow> leaves) {
        MemberRow m = new MemberRow(M, "Eng Two", "e@example.test", "MEMBER", "Calibration Engineer", List.of(T), T, LocalDate.of(2026, 1, 5), null);
        return new ForecastData(List.of(m), List.of(new TeamRow(T, "CT2 · X", null, null)), List.of(), List.of(), List.of(), List.of(),
                leaves, List.of(), List.of(new HolidayRow(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1), true, true, "Labour Day")),
                List.of(new UserRef(M, "Eng Two", "e@example.test", "eng")), Map.of());
    }

    static LeaveRow leave(LocalDate start, LocalDate end, Double hours, LocalTime begin, LocalTime finish) {
        return new LeaveRow(M, start, end, begin, finish, hours, "APPROVED", "PAID_LEAVE");
    }

    @Test
    void capacityIsTheDefaultOverTheWeeksWorkingDaysMinusLeaveHours() {
        ForecastData d = data(List.of(leave(LocalDate.of(2026, 4, 28), LocalDate.of(2026, 4, 28), 8.8, null, null)));
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        CapacityRule rule = new CapacityRule(44.0);
        assertEquals(44 * 4 / 5.0 - 8.8, rule.capacity(d.members().get(0), MON, d, cal), 1e-9, "four working days, one on leave");
        assertEquals(8.8, rule.absenceHours(M, MON, d, cal), 1e-9);
        ForecastData none = data(List.of());
        assertEquals(44.0, rule.capacity(none.members().get(0), LocalDate.of(2026, 3, 16), none, cal), 1e-9);
        assertEquals(0.0, rule.absenceHours(M, LocalDate.of(2026, 3, 16), none, cal), 1e-9);
    }

    @Test
    void capacityNeverGoesBelowZero() {
        ForecastData d = data(List.of(leave(MON, LocalDate.of(2026, 5, 1), null, null, null)));
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        assertEquals(0.0, new CapacityRule(44.0).capacity(d.members().get(0), MON, d, cal), 1e-9, "the whole week on leave");
    }

    @Test
    void dayCapacityIsZeroOffWorkingDaysAndTheDefaultOverFiveMinusTheDaysLeave() {
        ForecastData d = data(List.of(
                leave(LocalDate.of(2026, 4, 28), LocalDate.of(2026, 4, 28), 4.0, null, null),
                leave(LocalDate.of(2026, 4, 29), LocalDate.of(2026, 4, 29), null, null, null)));
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        CapacityRule rule = new CapacityRule(44.0);
        MemberRow m = d.members().get(0);
        assertEquals(8.8, rule.dayCapacity(m, MON, d, cal), 1e-9, "44 h over five days");
        assertEquals(4.8, rule.dayCapacity(m, LocalDate.of(2026, 4, 28), d, cal), 1e-9, "minus the 4 h of leave that day");
        assertEquals(0.0, rule.dayCapacity(m, LocalDate.of(2026, 4, 29), d, cal), 1e-9, "a whole day of leave");
        assertEquals(0.0, rule.dayCapacity(m, LocalDate.of(2026, 5, 1), d, cal), 1e-9, "Labour Day");
        assertEquals(0.0, rule.dayCapacity(m, LocalDate.of(2026, 5, 2), d, cal), 1e-9, "Saturday");
        assertEquals(4.0, rule.dayAbsenceHours(M, LocalDate.of(2026, 4, 28), d), 1e-9);
        assertEquals(8.8, rule.dayAbsenceHours(M, LocalDate.of(2026, 4, 29), d), 1e-9);
        assertEquals(0.0, rule.dayAbsenceHours(M, MON, d), 1e-9);
    }

    @Test
    void aHalfDayLeaveIsNotAnOffDayAndAWholeDayIs() {
        ForecastData d = data(List.of(
                leave(LocalDate.of(2026, 4, 28), LocalDate.of(2026, 4, 28), 4.0, null, null),
                leave(LocalDate.of(2026, 4, 29), LocalDate.of(2026, 4, 30), 17.6, null, null)));
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        assertEquals(Set.of(LocalDate.of(2026, 4, 29), LocalDate.of(2026, 4, 30)), new CapacityRule(44.0).offDays(M, d, cal));
    }

    @Test
    void twoLeavesOnOneDayAreCappedAtAFullDay() {
        ForecastData d = data(List.of(
                leave(LocalDate.of(2026, 4, 28), LocalDate.of(2026, 4, 28), 6.0, null, null),
                leave(LocalDate.of(2026, 4, 28), LocalDate.of(2026, 4, 28), 6.0, null, null)));
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        CapacityRule rule = new CapacityRule(44.0);
        assertEquals(8.8, rule.dayAbsenceHours(M, LocalDate.of(2026, 4, 28), d), 1e-9, "12 h of leave on one day count as the whole day");
        assertEquals(Set.of(LocalDate.of(2026, 4, 28)), rule.offDays(M, d, cal));
        assertEquals(44 * 4 / 5.0 - 8.8, rule.capacity(d.members().get(0), MON, d, cal), 1e-9);
    }

    @Test
    void aPendingLeaveDoesNotReduceCapacity() {
        MemberRow m = new MemberRow(M, "Eng Two", "e@example.test", "MEMBER", "Calibration Engineer", List.of(T), T, LocalDate.of(2026, 1, 5), null);
        LeaveRow pending = new LeaveRow(M, LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 20), null, null, 44.0, "PENDING", "PAID_LEAVE");
        ForecastData d = new ForecastData(List.of(m), List.of(new TeamRow(T, "CT2 · X", null, null)), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(pending), List.of(), List.of(new UserRef(M, "Eng Two", "e@example.test", "eng")), Map.of());
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        assertEquals(44.0, new CapacityRule(44.0).capacity(m, LocalDate.of(2026, 3, 16), d, cal), 1e-9);
        assertEquals(1, d.pendingLeaves().size());
    }

    @Test
    void seededLeavesReduceSomeWeeksAndNeverBelowZero() {
        ForecastData d = SeededData.data();
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        CapacityRule rule = new CapacityRule(44.0);
        int reduced = 0;
        for (LeaveRow l : d.leaves()) {
            MemberRow m = d.members().stream().filter(x -> x.id().equals(l.employeeId())).findFirst().orElse(null);
            if (m == null) {
                continue;
            }
            LocalDate monday = com.workloadhub.forecast.calendar.Weeks.mondayOf(l.start());
            double c = rule.capacity(m, monday, d, cal);
            assertTrue(c >= 0 && c <= 44.0 + 1e-9, "capacity " + c);
            if (c < 44 * cal.workingDaysInWeek(monday) / 5.0 - 1e-9) {
                reduced++;
            }
        }
        assertTrue(reduced > 20, "leaves reduce weeks: " + reduced);
    }

    @Property
    boolean dayCapacityStaysBetweenZeroAndTheDefaultOverFive(@ForAll @DoubleRange(min = 0, max = 24) double hours, @ForAll @IntRange(min = 0, max = 13) int offset) {
        LocalDate day = LocalDate.of(2026, 4, 20).plusDays(offset);
        ForecastData d = data(List.of(leave(day, day, hours, null, null)));
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        double c = new CapacityRule(44.0).dayCapacity(d.members().get(0), day, d, cal);
        return c >= 0 && c <= 8.8 + 1e-9 && (cal.isWorkingDay(day) || c == 0.0);
    }
}
