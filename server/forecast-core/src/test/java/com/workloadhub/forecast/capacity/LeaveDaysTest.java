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
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LeaveDaysTest {

    static final UUID M = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final double FULL = 8.8;
    static final LocalDate MON = LocalDate.of(2026, 9, 7);
    static final LocalDate TUE = MON.plusDays(1);
    static final LocalDate WED = MON.plusDays(2);
    static final LocalDate THU = MON.plusDays(3);
    static final LocalDate FRI = MON.plusDays(4);
    // Thursday 10 September is a confirmed, active public holiday in this calendar.
    static final WorkingCalendar CAL = WorkingCalendar.fromHolidays(List.of(new HolidayRow(THU, THU, true, true, "Test holiday")));
    static final WorkingCalendar PLAIN = WorkingCalendar.fromHolidays(List.of());

    static LeaveRow leave(LocalDate start, LocalDate end, Double hours, LocalTime begin, LocalTime endTime) {
        return new LeaveRow(M, start, end, begin, endTime, hours, "APPROVED", "PAID_LEAVE");
    }

    static NavigableMap<LocalDate, Double> days(LeaveRow l, WorkingCalendar cal) {
        Map<UUID, NavigableMap<LocalDate, Double>> out = LeaveDays.expand(List.of(l), cal, FULL);
        return out.getOrDefault(M, new java.util.TreeMap<>());
    }

    @Test
    void aWholeLeaveDealsAFullDayToEachWorkingDay() {
        assertEquals(Map.of(MON, 8.8, TUE, 8.8, WED, 8.8), days(leave(MON, WED, 26.4, null, null), PLAIN));
    }

    @Test
    void aLeaveEndingAtNoonLeavesTheRemainderOnItsLastDay() {
        assertEquals(Map.of(MON, 8.8, TUE, 8.8, WED, 4.4), days(leave(MON, WED, 22.0, null, LocalTime.of(12, 0)), PLAIN));
    }

    @Test
    void aLeaveStartingAtOneLeavesTheRemainderOnItsFirstDay() {
        assertEquals(Map.of(MON, 4.4, TUE, 8.8, WED, 8.8), days(leave(MON, WED, 22.0, LocalTime.of(13, 0), null), PLAIN));
    }

    @Test
    void aLeaveWithBothTimesIsDealtForwardLikeOneThatOnlyEnds() {
        // Both times set: only begin_time alone moves the partial day to the front (design 2026-09-17,
        // section 2.2), so a leave that starts at 13:00 and ends at 12:00 still leaves its remainder last.
        assertEquals(Map.of(MON, 8.8, TUE, 8.8, WED, 4.4),
                days(leave(MON, WED, 22.0, LocalTime.of(13, 0), LocalTime.of(12, 0)), PLAIN));
    }

    @Test
    void weekendsAndHolidaysCostNothing() {
        LocalDate fri = LocalDate.of(2026, 9, 4);
        assertEquals(Map.of(fri, 8.8, MON, 8.8), days(leave(fri, MON, 17.6, null, null), PLAIN), "Saturday and Sunday are skipped");
        assertEquals(Map.of(WED, 8.8, FRI, 8.8), days(leave(WED, FRI, 17.6, null, null), CAL), "the Thursday holiday is skipped");
        assertTrue(days(leave(THU, THU, 8.8, null, null), CAL).isEmpty(), "a leave on the holiday alone contributes nothing");
    }

    @Test
    void aHalfDayOnOneDateAndAOneDayLeaveOverTwoDates() {
        assertEquals(Map.of(THU, 4.0), days(leave(THU, THU, 4.0, null, null), PLAIN));
        assertEquals(Map.of(THU, 8.8), days(leave(THU, FRI, 8.8, null, null), PLAIN), "the second day receives zero and is not an absent day");
    }

    @Test
    void nullOrNonPositiveHoursMeanTheWholePeriodAndTooManyHoursAreCapped() {
        assertEquals(Map.of(MON, 8.8, TUE, 8.8, WED, 8.8, THU, 8.8, FRI, 8.8), days(leave(MON, FRI, null, null, null), PLAIN));
        assertEquals(Map.of(MON, 8.8, TUE, 8.8), days(leave(MON, TUE, 0.0, null, null), PLAIN));
        assertEquals(Map.of(MON, 8.8, TUE, 8.8), days(leave(MON, TUE, 40.0, null, null), PLAIN), "capped at n × full day");
    }

    @Test
    void twoLeavesOnOneDayAddUpAndMembersAreKeptApart() {
        UUID other = UUID.fromString("30000000-0000-0000-0000-000000000003");
        Map<UUID, NavigableMap<LocalDate, Double>> out = LeaveDays.expand(List.of(
                leave(MON, MON, 4.0, null, null), leave(MON, MON, 2.0, null, null),
                new LeaveRow(other, TUE, TUE, null, null, null, "APPROVED", "SICK_LEAVE")), PLAIN, FULL);
        assertEquals(Map.of(MON, 6.0), out.get(M));
        assertEquals(Map.of(TUE, 8.8), out.get(other));
    }
}
