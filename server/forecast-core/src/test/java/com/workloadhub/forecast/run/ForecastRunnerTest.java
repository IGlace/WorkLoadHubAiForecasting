package com.workloadhub.forecast.run;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.Numbers;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.MemberDayForecast;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.calendar.ForecastWindow;
import com.workloadhub.forecast.calendar.Horizon;
import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Truncation;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ForecastRunnerTest {

    static ForecastData data;
    static ForecastRunner runner;
    static Prepared prepared;
    static UUID team;

    @BeforeAll
    static void prepare() {
        data = SeededData.data();
        runner = new ForecastRunner(new CapacityRule(40), 2);
        prepared = runner.prepare(data, SeededData.asOf(), ForecastRunner.ProgressListener.NONE);
        team = SeededData.anyTeam(data);
    }

    @Test
    void theBandIsDemandWithItsResidualOffsets() {
        ForecastRunner.Band b = ForecastRunner.band(30.0, -3.1, 4.0, 44.0);
        assertEquals(30.0, b.demand(), 1e-9);
        assertEquals(26.9, b.low(), 1e-9);
        assertEquals(34.0, b.high(), 1e-9);
        assertEquals(0.0, b.overload(), 1e-9, "30 hours against 44 of capacity is not overload");
    }

    @Test
    void overloadIsWhatDemandExceedsCapacityBy() {
        assertEquals(6.0, ForecastRunner.band(50.0, -3.0, 3.0, 44.0).overload(), 1e-9);
    }

    @Test
    void theLowEndNeverGoesNegative() {
        assertEquals(0.0, ForecastRunner.band(2.0, -9.0, 1.0, 44.0).low(), 1e-9,
                "a member cannot log negative hours");
    }

    @Test
    void timeFrameFollowsTheRunDay() {
        LocalDate asOf = SeededData.asOf();                                   // Sunday 2026-09-06
        assertEquals(Weeks.lastCompleteWeek(asOf), prepared.origin());
        assertEquals(Horizon.windows(asOf, 2), prepared.windows());
        assertEquals(LocalDate.of(2026, 9, 7), prepared.windows().get(0).start());
        assertEquals(LocalDate.of(2026, 9, 18), prepared.windows().get(1).end());
        assertArrayEquals(new int[] {2, 3}, prepared.horizons(), "a weekend run: the week just ended is complete, the windows touch the next two");
        assertTrue(prepared.historyWeeks() >= 25 && prepared.historyWeeks() <= 30, "30 seeded weeks: " + prepared.historyWeeks());
        assertFalse(prepared.backtestOrigins().isEmpty());
    }

    @Test
    void aMidweekRunTouchesThreeHorizonWeeks() {
        LocalDate wednesday = LocalDate.of(2026, 9, 2);
        Prepared p = runner.prepare(data, wednesday, ForecastRunner.ProgressListener.NONE);
        assertArrayEquals(new int[] {1, 2, 3}, p.horizons());
        assertEquals(LocalDate.of(2026, 9, 3), p.windows().get(0).start());
        assertEquals(LocalDate.of(2026, 9, 16), p.windows().get(1).end());
        for (int h : p.horizons()) {
            assertNotNull(p.bandOffsets().get(h));
        }
        TeamOutcome out = runner.forTeam(p, team);
        assertEquals(out.members().size() * 10, out.memberDays().size());
    }

    @Test
    void predictionsExistForEveryMemberWithAnOriginRowAndTheBacktestReportsScale() {
        assertNotNull(prepared.mae());
        assertTrue(prepared.mae() >= 0);
        assertNotNull(prepared.meanActualHours());
        assertTrue(prepared.meanActualHours() > 0, "the average week a member of this team actually logs");
        long originRows = prepared.features().filter(k -> k.week().equals(prepared.origin())).rowCount();
        assertEquals(originRows * prepared.horizons().length, prepared.predictedHours().size(), "one prediction per horizon per member with an origin row");
        assertTrue(prepared.predictedHours().values().stream().allMatch(v -> v >= 0));
        for (int h : prepared.horizons()) {
            double[] band = prepared.bandOffsets().get(h);
            assertTrue(band[0] <= 0 && band[1] >= 0);
        }
    }

    @Test
    void teamOutcomeHoldsTheInvariantsForEveryMemberWindowAndDay() {
        TeamOutcome out = runner.forTeam(prepared, team);
        List<MemberWindowForecast> rows = out.memberWindows();
        assertEquals(out.members().size() * 2, rows.size());
        assertEquals(out.members().size() * 10, out.memberDays().size());
        for (MemberWindowForecast r : rows) {
            assertEquals(r.overloadHrs(), Numbers.round2(Math.max(0, r.demandHrs() - r.capacityHrs())), 1e-9);
            assertTrue(r.lowHrs() <= r.demandHrs() + 1e-9 && r.demandHrs() <= r.highHrs() + 1e-9);
            assertTrue(r.lowHrs() >= 0.0, "a member cannot log negative hours");
            assertTrue(r.capacityHrs() >= 0 && r.workingDays() >= 0 && r.workingDays() <= 5);
            ForecastWindow w = prepared.windows().get(r.windowIndex() - 1);
            assertEquals(w.start(), r.windowStart());
            assertEquals(w.end(), r.windowEnd());
            List<MemberDayForecast> days = out.memberDays().stream().filter(d -> d.userId().equals(r.userId()) && d.windowIndex() == r.windowIndex()).toList();
            assertEquals(5, days.size());
            assertEquals(w.weekdays(), days.stream().map(MemberDayForecast::day).toList());
            assertEquals(r.capacityHrs(), days.stream().mapToDouble(MemberDayForecast::capacityHrs).sum(), 0.05);
            assertEquals(r.demandHrs(), days.stream().mapToDouble(MemberDayForecast::demandHrs).sum(), 0.05,
                    "a window's demand is the sum of its days (rounding is per day, not per component)");
            assertEquals(r.workingDays(), days.stream().filter(MemberDayForecast::workingDay).count());
            for (MemberDayForecast d : days) {
                assertEquals(d.overloadHrs(), Numbers.round2(Math.max(0, d.demandHrs() - d.capacityHrs())), 1e-9);
                assertTrue(d.workingDay() || d.capacityHrs() == 0.0, "no capacity on a holiday");
            }
        }
    }

    @Test
    void unknownOrEmptyTeamIsRejected() {
        assertEquals("TEAM_NOT_FOUND", assertThrows(ForecastException.class, () -> runner.forTeam(prepared, UUID.randomUUID())).code());
    }

    // Design 2026-09-13, section 8: a member holding 120 hours of open work (60 of it due inside window 1,
    // whose capacity is 44, and 7 already overdue at the run day), forecast by hand at 30 hours in window 1
    // and 25 in window 2 so the pressure arithmetic can be checked exactly rather than through a fitted model.
    static List<MemberWindowForecast> pressureWindows;

    @BeforeAll
    static void preparePressureFixture() {
        MemberRow ana = TestData.member("ana", TestData.TEAM);
        LocalDate asOf = LocalDate.of(2026, 9, 6);                                    // Sunday: window 1 starts Monday 2026-09-07
        TaskRow dueInWindowOne = TestData.task("due", ana.id(), LocalDateTime.of(2026, 8, 1, 9, 0), 60)
                .withDue(LocalDate.of(2026, 9, 10)).withRemaining(60.0);
        TaskRow noDueDate = TestData.task("nodue", ana.id(), LocalDateTime.of(2026, 8, 1, 9, 0), 53)
                .withRemaining(53.0);
        TaskRow overdue = TestData.task("overdue", ana.id(), LocalDateTime.of(2026, 8, 1, 9, 0), 7)
                .withDue(LocalDate.of(2026, 9, 3)).withRemaining(7.0);
        ForecastData data = TestData.data(List.of(ana), List.of(dueInWindowOne, noDueDate, overdue), List.of(), List.of());
        ForecastRunner pressureRunner = new ForecastRunner(new CapacityRule(44), 2);
        Prepared base = pressureRunner.prepare(data, asOf, ForecastRunner.ProgressListener.NONE);
        // The model is bypassed: predictedHours is replaced with the two figures the fixture is built around,
        // so window 1 and window 2 demand are exactly 30.0 and 25.0 rather than whatever a fitted booster
        // would produce on three hand-built tasks.
        Map<MemberWeek, Double> predicted = new TreeMap<>();
        predicted.put(new MemberWeek(ana.id(), base.origin().plusWeeks(base.horizons()[0])), 30.0);
        predicted.put(new MemberWeek(ana.id(), base.origin().plusWeeks(base.horizons()[1])), 25.0);
        Prepared fixture = new Prepared(base.data(), base.lifecycle(), base.calendar(), base.asOf(), base.origin(), base.windows(), base.horizons(),
                base.features(), base.backtestOrigins(), base.backtest(), base.mae(), base.meanActualHours(), base.bandOffsets(), predicted,
                base.historyWeeks(), base.secondsByPhase());
        pressureWindows = pressureRunner.forTeam(fixture, TestData.TEAM).memberWindows();
    }

    private static MemberWindowForecast window(int index) {
        return pressureWindows.stream().filter(w -> w.windowIndex() == index).findFirst().orElseThrow();
    }

    @Test
    void theBacklogFigureIsWhatIsLeftAfterTheForecast() {
        assertEquals(90.0, window(1).backlogExcessHrs(), 1e-6, "120 - 30");
        assertEquals(65.0, window(2).backlogExcessHrs(), 1e-6, "120 - (30 + 25), cumulative");
    }

    @Test
    void theBacklogFigureNeverGrowsAcrossAWindow() {
        double previous = Double.MAX_VALUE;
        for (MemberWindowForecast w : pressureWindows) {
            assertTrue(w.backlogExcessHrs() <= previous + 1e-9);
            previous = w.backlogExcessHrs();
        }
    }

    @Test
    void theDeadlineGapIsPerWindowAgainstThatWindowsCapacity() {
        // 60 hours due inside window 1, whose capacity is 44.
        assertEquals(16.0, window(1).dueExcessHrs(), 1e-6);
        assertEquals(0.0, window(2).dueExcessHrs(), 1e-6, "a deadline belongs to its window and does not roll forward");
    }

    @Test
    void aHolidayInsideWindowOneLowersCapacityAndDemandWithoutMovingTheWindow() {
        LocalDate wednesday = LocalDate.of(2026, 4, 29);               // Labour Day, Friday 1 May, is inside window 1
        Prepared p = runner.prepare(data, wednesday, ForecastRunner.ProgressListener.NONE);
        assertEquals(LocalDate.of(2026, 4, 30), p.windows().get(0).start());
        assertEquals(LocalDate.of(2026, 5, 6), p.windows().get(0).end());
        assertTrue(p.windows().get(0).weekdays().contains(LocalDate.of(2026, 5, 1)), "the holiday stays inside the window");
        TeamOutcome out = runner.forTeam(p, team);
        for (MemberWindowForecast r : out.memberWindows()) {
            if (r.windowIndex() == 1) {
                assertEquals(4, r.workingDays(), "five weekdays, one holiday");
            }
        }
        for (MemberDayForecast d : out.memberDays()) {
            if (d.day().equals(LocalDate.of(2026, 5, 1))) {
                assertFalse(d.workingDay());
                assertEquals(0.0, d.capacityHrs(), 1e-9);
                assertEquals(0.0, d.demandHrs(), 1e-9, "no demand lands on a holiday");
            }
        }
    }

    @Test
    void thinHistoryStillCompletesWithAZeroWidthBand() {
        ForecastData young = Truncation.at(data, SeededData.asOf().minusWeeks(22));
        Prepared p = runner.prepare(young, SeededData.asOf().minusWeeks(22), ForecastRunner.ProgressListener.NONE);
        assertTrue(p.backtestOrigins().isEmpty(), "under 13 weeks before every origin");
        assertNull(p.mae(), "no scored origin means no MAE");
        assertNull(p.meanActualHours());
        List<MemberWindowForecast> weeks = runner.forTeam(p, team).memberWindows();
        assertNotNull(weeks);
        assertFalse(weeks.isEmpty());
        for (MemberWindowForecast w : weeks) {
            assertEquals(w.demandHrs(), w.lowHrs(), 1e-9, "a zero-width band: no residuals to widen it");
            assertEquals(w.demandHrs(), w.highHrs(), 1e-9);
        }
    }

    @Test
    void twoRunsOnTheSameDataAreIdentical() {
        Prepared again = runner.prepare(data, SeededData.asOf(), ForecastRunner.ProgressListener.NONE);
        assertEquals(prepared.mae(), again.mae());
        assertEquals(prepared.predictedHours(), again.predictedHours());
        assertEquals(runner.forTeam(prepared, team).memberWindows(), runner.forTeam(again, team).memberWindows());
    }

    // The week used by the weekday-split tests below: a Monday, with a wide-open horizon so it is always
    // wholly inside it. shares/cal/offDays are varied per test before dayHours(day) is called.
    static final LocalDate monday = LocalDate.of(2026, 9, 7);
    static final LocalDate tuesday = monday.plusDays(1);
    static final LocalDate wednesday = monday.plusDays(2);
    static final LocalDate thursday = monday.plusDays(3);
    static final LocalDate friday = monday.plusDays(4);

    List<Double> shares = List.of(0.4, 0.3, 0.2, 0.1, 0.0);
    WorkingCalendar cal = WorkingCalendar.fromHolidays(List.of());
    Set<LocalDate> offDays = Set.of();

    private double dayHours(LocalDate day) {
        Map<LocalDate, Double> days = ForecastRunner.splitWeek(20.0, shares, monday, cal, offDays,
                monday.minusDays(30), monday.plusDays(30));
        return days.getOrDefault(day, 0.0);
    }

    @Test
    void aWeeksHoursFollowTheMembersWeekdayShape() {
        // A member whose shares are Monday 0.4, Tuesday 0.3, Wednesday 0.2, Thursday 0.1, Friday 0.0,
        // predicted 20 hours for a week wholly inside the horizon with no holiday and no absence.
        assertEquals(8.0, dayHours(monday), 1e-6);
        assertEquals(6.0, dayHours(tuesday), 1e-6);
        assertEquals(4.0, dayHours(wednesday), 1e-6);
        assertEquals(2.0, dayHours(thursday), 1e-6);
        assertEquals(0.0, dayHours(friday), 1e-6);
    }

    @Test
    void aHolidayRedistributesOntoTheRestOfTheWeek() {
        cal = WorkingCalendar.fromHolidays(List.of(new HolidayRow(wednesday, wednesday, true, true, "Test Holiday")));
        // The same member and week, with Wednesday a public holiday: the 0.2 share is removed from the
        // denominator, so the other four days carry the whole 20 hours between them.
        assertEquals(0.0, dayHours(wednesday), 1e-6);
        assertEquals(20.0, dayHours(monday) + dayHours(tuesday) + dayHours(thursday) + dayHours(friday), 1e-6);
    }

    @Test
    void aFullAbsenceDayTakesNoHours() {
        offDays = Set.of(tuesday);
        // The same, with Tuesday a full-day absence: its 0.3 share leaves the denominator, so the other four
        // days carry the whole 20 hours between them rather than the week losing Tuesday's share.
        assertEquals(0.0, dayHours(tuesday), 1e-6);
        assertEquals(20.0, dayHours(monday) + dayHours(wednesday) + dayHours(thursday) + dayHours(friday), 1e-6);
    }

    @Test
    void aDayAlreadyPastDropsItsHoursInsteadOfRedistributingThem() {
        // The same member and week, with the horizon starting on the Wednesday: Monday and Tuesday are behind
        // the run day and are not re-forecast. Unlike a holiday or an absence, their shares stay in the
        // denominator, so Wednesday to Friday keep their own 0.2, 0.1 and 0.0 of the week and the 14 hours of
        // the two past days are dropped. Filtering [first, last] before the denominator instead would silently
        // turn redistribution on for days already past.
        Map<LocalDate, Double> days = ForecastRunner.splitWeek(20.0, shares, monday, cal, offDays, wednesday, monday.plusDays(30));
        assertEquals(Set.of(wednesday, thursday, friday), days.keySet());
        assertEquals(4.0, days.get(wednesday), 1e-6);
        assertEquals(2.0, days.get(thursday), 1e-6);
        assertEquals(0.0, days.get(friday), 1e-6);
        assertEquals(6.0, days.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-6,
                "the 14 hours of Monday and Tuesday are gone, not spread over the rest of the week");
    }

    @Test
    void aMemberWithNoLoggedHistoryGetsTheEvenSplit() {
        shares = List.of(0.0, 0.0, 0.0, 0.0, 0.0);
        // Shares all zero: 20 hours over five working days is 4.0 each, exactly today's behaviour.
        assertEquals(4.0, dayHours(monday), 1e-6);
    }
}
