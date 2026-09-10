package com.workloadhub.forecast.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.MemberWeekForecast;
import com.workloadhub.forecast.backtest.Backtest;
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
    void timeFrameFollowsTheAsOfDate() {
        LocalDate asOf = SeededData.asOf();                                   // a Sunday
        assertEquals(Weeks.lastCompleteWeek(asOf), prepared.origin());
        assertEquals(Weeks.forecastWeeks(asOf)[0], prepared.forecastWeeks()[0]);
        assertEquals(2, prepared.horizons()[0], "as-of is not a Monday: the first forecast week is two weeks after the origin");
        assertEquals(3, prepared.horizons()[1]);
        assertTrue(prepared.historyWeeks() >= 25 && prepared.historyWeeks() <= 30, "30 seeded weeks: " + prepared.historyWeeks());
        assertFalse(prepared.backtestOrigins().isEmpty());
    }

    @Test
    void championIsScoredAndPredictionsExistForEveryMemberWithAnOriginRow() {
        assertTrue(List.of(Backtest.FLOOR, XgboostArrival.NAME).contains(prepared.champion()));
        assertTrue(prepared.backtest().meanMaseByModel().containsKey(XgboostArrival.NAME));
        assertTrue(prepared.backtest().meanMaseByModel().containsKey(Backtest.FLOOR));
        long originRows = prepared.features().filter(k -> k.week().equals(prepared.origin())).rowCount();
        assertEquals(originRows * 2, prepared.predictedEst().size(), "two forecast weeks per member with an origin row");
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
    void teamOutcomeHoldsTheInvariantsForEveryMemberAndWeek() {
        TeamOutcome out = runner.forTeam(prepared, team, null);
        List<MemberWeekForecast> rows = out.memberWeeks();
        assertEquals(out.members().size() * 2, rows.size());
        for (MemberWeekForecast r : rows) {
            assertEquals(r.demandHrs(), ForecastRunner.round2(r.openHrs() + r.newHrs() + r.plannedHrs()), 1e-9);
            assertEquals(r.overloadHrs(), ForecastRunner.round2(Math.max(0, r.demandHrs() - r.capacityHrs())), 1e-9);
            assertTrue(r.lowHrs() <= r.demandHrs() + 1e-9 && r.demandHrs() <= r.highHrs() + 1e-9);
            assertTrue(r.lowHrs() >= r.openHrs() + r.plannedHrs() - 1e-9, "the band never cuts into placed or planned work");
            assertTrue(r.capacityHrs() >= 0 && r.workingDays() >= 0 && r.workingDays() <= 5);
            assertTrue(r.weekStart().equals(prepared.forecastWeeks()[0]) || r.weekStart().equals(prepared.forecastWeeks()[1]));
        }
        assertTrue(out.plannedWorkEnabled());
        TeamOutcome noPlanned = runner.forTeam(prepared, team, false);
        assertTrue(noPlanned.memberWeeks().stream().allMatch(r -> r.plannedHrs() == 0.0));
        assertEquals(0, noPlanned.planned().pieces().size());
    }

    @Test
    void unknownOrEmptyTeamIsRejected() {
        assertEquals("TEAM_NOT_FOUND", assertThrows(ForecastException.class, () -> runner.forTeam(prepared, UUID.randomUUID(), null)).code());
    }

    @Test
    void shortHistoryStillCompletesWithTheFloor() {
        ForecastData young = Truncation.at(data, SeededData.asOf().minusWeeks(22));
        Prepared p = runner.prepare(young, SeededData.asOf().minusWeeks(22), null, ForecastRunner.ProgressListener.NONE);
        assertTrue(p.backtestOrigins().isEmpty(), "under 13 weeks before every origin");
        assertEquals(Backtest.FLOOR, p.champion());
        assertTrue(Double.isNaN(p.championMase()));
        List<MemberWeekForecast> weeks = runner.forTeam(p, team, null).memberWeeks();
        assertNotNull(weeks);
        assertFalse(weeks.isEmpty());
        for (MemberWeekForecast w : weeks) {
            assertTrue(w.lowHrs() <= w.demandHrs(), () -> "low " + w.lowHrs() + " > demand " + w.demandHrs() + " for " + w);
            assertTrue(w.demandHrs() <= w.highHrs(), () -> "demand " + w.demandHrs() + " > high " + w.highHrs() + " for " + w);
        }
    }

    @Test
    void twoRunsOnTheSameDataAreIdentical() {
        Prepared again = runner.prepare(data, SeededData.asOf(), null, ForecastRunner.ProgressListener.NONE);
        assertEquals(prepared.champion(), again.champion());
        assertEquals(prepared.predictedEst(), again.predictedEst());
        assertEquals(runner.forTeam(prepared, team, null).memberWeeks(), runner.forTeam(again, team, null).memberWeeks());
    }
}
