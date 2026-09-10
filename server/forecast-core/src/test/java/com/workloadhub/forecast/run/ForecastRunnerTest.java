package com.workloadhub.forecast.run;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.MemberDayForecast;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.calendar.ForecastWindow;
import com.workloadhub.forecast.calendar.Horizon;
import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.lifecycle.Truncation;
import com.workloadhub.forecast.model.XgboostArrival;
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
        runner = new ForecastRunner(new CapacityRule(40), true);
        prepared = runner.prepare(data, SeededData.asOf(), null, ForecastRunner.ProgressListener.NONE);
        team = data.teams().stream().filter(t -> !data.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).findFirst().orElseThrow();
    }

    @Test
    void timeFrameFollowsTheRunDay() {
        LocalDate asOf = SeededData.asOf();                                   // Sunday 2026-09-06
        assertEquals(Weeks.lastCompleteWeek(asOf), prepared.origin());
        assertEquals(Horizon.windows(asOf), prepared.windows());
        assertEquals(LocalDate.of(2026, 9, 7), prepared.windows().get(0).start());
        assertEquals(LocalDate.of(2026, 9, 18), prepared.windows().get(1).end());
        assertArrayEquals(new int[] {2, 3}, prepared.horizons(), "a weekend run: the week just ended is complete, the windows touch the next two");
        assertTrue(prepared.historyWeeks() >= 25 && prepared.historyWeeks() <= 30, "30 seeded weeks: " + prepared.historyWeeks());
        assertFalse(prepared.backtestOrigins().isEmpty());
    }

    @Test
    void aMidweekRunTouchesThreeHorizonWeeks() {
        LocalDate wednesday = LocalDate.of(2026, 9, 2);
        Prepared p = runner.prepare(data, wednesday, Backtest.FLOOR, ForecastRunner.ProgressListener.NONE);
        assertArrayEquals(new int[] {1, 2, 3}, p.horizons());
        assertEquals(LocalDate.of(2026, 9, 3), p.windows().get(0).start());
        assertEquals(LocalDate.of(2026, 9, 16), p.windows().get(1).end());
        for (int h : p.horizons()) {
            assertNotNull(p.bandOffsets().get(h));
        }
        TeamOutcome out = runner.forTeam(p, team, null);
        assertEquals(out.members().size() * 10, out.memberDays().size());
    }

    @Test
    void championIsScoredAndPredictionsExistForEveryMemberWithAnOriginRow() {
        assertTrue(List.of(Backtest.FLOOR, XgboostArrival.NAME).contains(prepared.champion()));
        assertTrue(prepared.backtest().meanMaseByModel().containsKey(XgboostArrival.NAME));
        assertTrue(prepared.backtest().meanMaseByModel().containsKey(Backtest.FLOOR));
        long originRows = prepared.features().filter(k -> k.week().equals(prepared.origin())).rowCount();
        assertEquals(originRows * prepared.horizons().length, prepared.predictedEst().size(), "one prediction per horizon per member with an origin row");
        assertTrue(prepared.predictedEst().values().stream().allMatch(v -> v >= 0));
        for (int h : prepared.horizons()) {
            double[] band = prepared.bandOffsets().get(h);
            assertTrue(band[0] <= 0 && band[1] >= 0);
        }
    }

    @Test
    void forcedModelIsUsedRegardlessOfItsScore() {
        Prepared floor = runner.prepare(data, SeededData.asOf(), Backtest.FLOOR, ForecastRunner.ProgressListener.NONE);
        assertEquals(Backtest.FLOOR, floor.champion());
        assertEquals(List.of(Backtest.FLOOR), List.copyOf(floor.backtest().meanMaseByModel().keySet()));
        Prepared xgb = runner.prepare(data, SeededData.asOf(), XgboostArrival.NAME, ForecastRunner.ProgressListener.NONE);
        assertEquals(XgboostArrival.NAME, xgb.champion());
        assertEquals(xgb.backtest().meanMase(XgboostArrival.NAME), xgb.championMase(), 1e-12);
    }

    @Test
    void teamOutcomeHoldsTheInvariantsForEveryMemberWindowAndDay() {
        TeamOutcome out = runner.forTeam(prepared, team, null);
        List<MemberWindowForecast> rows = out.memberWindows();
        assertEquals(out.members().size() * 2, rows.size());
        assertEquals(out.members().size() * 10, out.memberDays().size());
        for (MemberWindowForecast r : rows) {
            assertEquals(r.demandHrs(), ForecastRunner.round2(r.openHrs() + r.newHrs() + r.plannedHrs()), 1e-9);
            assertEquals(r.overloadHrs(), ForecastRunner.round2(Math.max(0, r.demandHrs() - r.capacityHrs())), 1e-9);
            assertTrue(r.lowHrs() <= r.demandHrs() + 1e-9 && r.demandHrs() <= r.highHrs() + 1e-9);
            assertTrue(r.lowHrs() >= r.openHrs() + r.plannedHrs() - 1e-9, "the band never cuts into placed or planned work");
            assertTrue(r.capacityHrs() >= 0 && r.workingDays() >= 0 && r.workingDays() <= 5);
            ForecastWindow w = prepared.windows().get(r.windowIndex() - 1);
            assertEquals(w.start(), r.windowStart());
            assertEquals(w.end(), r.windowEnd());
            List<MemberDayForecast> days = out.memberDays().stream().filter(d -> d.userId().equals(r.userId()) && d.windowIndex() == r.windowIndex()).toList();
            assertEquals(5, days.size());
            assertEquals(w.weekdays(), days.stream().map(MemberDayForecast::day).toList());
            assertEquals(r.openHrs(), days.stream().mapToDouble(MemberDayForecast::openHrs).sum(), 0.05, "a window's open hours are the sum of its days");
            assertEquals(r.newHrs(), days.stream().mapToDouble(MemberDayForecast::newHrs).sum(), 0.05);
            assertEquals(r.plannedHrs(), days.stream().mapToDouble(MemberDayForecast::plannedHrs).sum(), 0.05);
            assertEquals(r.capacityHrs(), days.stream().mapToDouble(MemberDayForecast::capacityHrs).sum(), 0.05);
            assertEquals(r.demandHrs(), days.stream().mapToDouble(MemberDayForecast::demandHrs).sum(), 0.05);
            assertEquals(r.workingDays(), days.stream().filter(MemberDayForecast::workingDay).count());
            for (MemberDayForecast d : days) {
                assertEquals(d.demandHrs(), ForecastRunner.round2(d.openHrs() + d.newHrs() + d.plannedHrs()), 1e-9);
                assertEquals(d.overloadHrs(), ForecastRunner.round2(Math.max(0, d.demandHrs() - d.capacityHrs())), 1e-9);
                assertTrue(d.workingDay() || d.capacityHrs() == 0.0, "no capacity on a holiday");
            }
        }
        assertTrue(out.plannedWorkEnabled());
        TeamOutcome noPlanned = runner.forTeam(prepared, team, false);
        assertTrue(noPlanned.memberWindows().stream().allMatch(r -> r.plannedHrs() == 0.0));
        assertEquals(0, noPlanned.planned().pieces().size());
    }

    @Test
    void unknownOrEmptyTeamIsRejected() {
        assertEquals("TEAM_NOT_FOUND", assertThrows(ForecastException.class, () -> runner.forTeam(prepared, UUID.randomUUID(), null)).code());
    }

    @Test
    void aHolidayInsideWindowOneLowersCapacityAndArrivalsWithoutMovingTheWindow() {
        LocalDate wednesday = LocalDate.of(2026, 4, 29);               // Labour Day, Friday 1 May, is inside window 1
        Prepared p = runner.prepare(data, wednesday, Backtest.FLOOR, ForecastRunner.ProgressListener.NONE);
        assertEquals(LocalDate.of(2026, 4, 30), p.windows().get(0).start());
        assertEquals(LocalDate.of(2026, 5, 6), p.windows().get(0).end());
        assertTrue(p.windows().get(0).contains(LocalDate.of(2026, 5, 1)), "the holiday stays inside the window");
        TeamOutcome out = runner.forTeam(p, team, null);
        for (MemberWindowForecast r : out.memberWindows()) {
            if (r.windowIndex() == 1) {
                assertEquals(4, r.workingDays(), "five weekdays, one holiday");
            }
        }
        for (MemberDayForecast d : out.memberDays()) {
            if (d.day().equals(LocalDate.of(2026, 5, 1))) {
                assertFalse(d.workingDay());
                assertEquals(0.0, d.capacityHrs(), 1e-9);
                assertEquals(0.0, d.newHrs(), 1e-9, "no arrivals land on a holiday");
            }
        }
    }

    @Test
    void shortHistoryStillCompletesWithTheFloor() {
        ForecastData young = Truncation.at(data, SeededData.asOf().minusWeeks(22));
        Prepared p = runner.prepare(young, SeededData.asOf().minusWeeks(22), null, ForecastRunner.ProgressListener.NONE);
        assertTrue(p.backtestOrigins().isEmpty(), "under 13 weeks before every origin");
        assertEquals(Backtest.FLOOR, p.champion());
        assertTrue(Double.isNaN(p.championMase()));
        List<MemberWindowForecast> weeks = runner.forTeam(p, team, null).memberWindows();
        assertNotNull(weeks);
        assertFalse(weeks.isEmpty());
        for (MemberWindowForecast w : weeks) {
            assertTrue(w.lowHrs() <= w.demandHrs(), () -> "low " + w.lowHrs() + " > demand " + w.demandHrs() + " for " + w);
            assertTrue(w.demandHrs() <= w.highHrs(), () -> "demand " + w.demandHrs() + " > high " + w.highHrs() + " for " + w);
        }
    }

    @Test
    void twoRunsOnTheSameDataAreIdentical() {
        Prepared again = runner.prepare(data, SeededData.asOf(), null, ForecastRunner.ProgressListener.NONE);
        assertEquals(prepared.champion(), again.champion());
        assertEquals(prepared.predictedEst(), again.predictedEst());
        assertEquals(runner.forTeam(prepared, team, null).memberWindows(), runner.forTeam(again, team, null).memberWindows());
    }
}
