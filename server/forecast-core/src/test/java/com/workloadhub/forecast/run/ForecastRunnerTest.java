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
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.lifecycle.Truncation;
import com.workloadhub.forecast.testing.SeededData;
import java.time.LocalDate;
import java.util.List;
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
        team = data.teams().stream().filter(t -> !data.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).findFirst().orElseThrow();
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

    @Test
    void aHolidayInsideWindowOneLowersCapacityAndDemandWithoutMovingTheWindow() {
        LocalDate wednesday = LocalDate.of(2026, 4, 29);               // Labour Day, Friday 1 May, is inside window 1
        Prepared p = runner.prepare(data, wednesday, ForecastRunner.ProgressListener.NONE);
        assertEquals(LocalDate.of(2026, 4, 30), p.windows().get(0).start());
        assertEquals(LocalDate.of(2026, 5, 6), p.windows().get(0).end());
        assertTrue(p.windows().get(0).contains(LocalDate.of(2026, 5, 1)), "the holiday stays inside the window");
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
}
