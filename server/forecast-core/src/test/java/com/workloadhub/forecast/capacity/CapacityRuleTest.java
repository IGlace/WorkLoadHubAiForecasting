package com.workloadhub.forecast.capacity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.AbsenceRow;
import com.workloadhub.forecast.data.rows.CapacityRow;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.data.rows.UserRef;
import com.workloadhub.forecast.testing.SeededData;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapacityRuleTest {

    static final UUID M = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final UUID T = UUID.fromString("40000000-0000-0000-0000-000000000001");

    static ForecastData data(List<CapacityRow> capacity, List<AbsenceRow> absences) {
        MemberRow m = new MemberRow(M, "Eng Two", "e@example.test", "MEMBER", "Calibration Engineer", List.of(T), T, LocalDate.of(2026, 1, 5), null);
        return new ForecastData(List.of(m), List.of(new TeamRow(T, "CT2 · X", null, null)), List.of(), List.of(), List.of(), List.of(),
                capacity, absences, List.of(new HolidayRow(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1), true, true, "Labour Day")),
                List.of(new UserRef(M, "Eng Two", "e@example.test", "eng")), Map.of());
    }

    @Test
    void capacityRowWinsWhenPresent() {
        ForecastData d = data(List.of(new CapacityRow(M, LocalDate.of(2026, 4, 27), 40, 8, 24)), List.of());
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        assertEquals(24.0, new CapacityRule(40).capacity(d.members().get(0), LocalDate.of(2026, 4, 27), d, cal), 1e-9);
        assertEquals(8.0, new CapacityRule(40).absenceHours(M, LocalDate.of(2026, 4, 27), d, cal), 1e-9);
    }

    @Test
    void latestBaseThenDefaultTimesWorkingDaysMinusAbsences() {
        ForecastData d = data(List.of(new CapacityRow(M, LocalDate.of(2026, 3, 2), 36, 0, 36)),
                List.of(new AbsenceRow(M, LocalDate.of(2026, 4, 28), 8), new AbsenceRow(M, LocalDate.of(2026, 5, 2), 8)));
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        // week of 27 April: 4 working days (Labour Day), one absence day on Tue 28; Sat 2 May is not a working day
        assertEquals(36 * 4 / 5.0 - 8, new CapacityRule(40).capacity(d.members().get(0), LocalDate.of(2026, 4, 27), d, cal), 1e-9);
        ForecastData none = data(List.of(), List.of());
        assertEquals(40.0, new CapacityRule(40).capacity(none.members().get(0), LocalDate.of(2026, 3, 16), none, cal), 1e-9);
        assertEquals(java.util.Set.of(LocalDate.of(2026, 4, 28), LocalDate.of(2026, 5, 2)), CapacityRule.offDays(M, d));
    }

    @Test
    void capacityNeverGoesBelowZero() {
        ForecastData d = data(List.of(),
                List.of(new AbsenceRow(M, LocalDate.of(2026, 4, 27), 20), new AbsenceRow(M, LocalDate.of(2026, 4, 28), 20)));
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        // week of 27 April has 4 working days (Labour Day): 40 * 4/5 = 32 h base, 40 h of absence outweighs it.
        assertEquals(0.0, new CapacityRule(40).capacity(d.members().get(0), LocalDate.of(2026, 4, 27), d, cal), 1e-9);
    }

    @Test
    void seededCapacityMatchesTheCapacityRows() {
        ForecastData d = SeededData.data();
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        CapacityRule rule = new CapacityRule(40);
        int checked = 0;
        for (CapacityRow c : d.capacity()) {
            MemberRow m = d.memberById().get(c.userId());
            if (m == null) {
                continue;
            }
            assertEquals(c.available(), rule.capacity(m, c.weekStart(), d, cal), 1e-6);
            checked++;
        }
        assertTrue(checked > 100);
    }
}
