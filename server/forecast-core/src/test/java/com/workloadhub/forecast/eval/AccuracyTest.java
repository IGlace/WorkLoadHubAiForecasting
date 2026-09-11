package com.workloadhub.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.AccuracyRow;
import com.workloadhub.forecast.api.AccuracyScore;
import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.MemberDayForecast;
import com.workloadhub.forecast.features.MemberDay;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccuracyTest {

    static final UUID TEAM = UUID.fromString("40000000-0000-0000-0000-000000000001");
    static final UUID A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final UUID B = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final UUID RUN1 = UUID.fromString("50000000-0000-0000-0000-000000000001");
    static final UUID RUN2 = UUID.fromString("50000000-0000-0000-0000-000000000002");
    static final LocalDate MON = LocalDate.of(2026, 8, 24);
    static final LocalDate TUE = MON.plusDays(1);
    static final LocalDate WED = MON.plusDays(2);
    static final LocalDate SAT = MON.plusDays(5);

    static CurrentDayForecast current(UUID user, LocalDate day, UUID run, double demand, double capacity) {
        return new CurrentDayForecast(TEAM, user, day, run, demand, 0, 0, demand, capacity, Math.max(0, demand - capacity), LocalDateTime.of(2026, 8, 20, 9, 0));
    }

    static RunDayForecast runDay(UUID run, LocalDate asOf, UUID user, LocalDate day, double demand, double capacity) {
        return new RunDayForecast(run, asOf, new MemberDayForecast(user, day, 1, demand, 0, 0, demand, capacity, Math.max(0, demand - capacity), true));
    }

    @Test
    void leadCountsWeekdaysAfterTheRunDay() {
        LocalDate wednesday = LocalDate.of(2026, 8, 19);
        assertEquals(1, Accuracy.lead(wednesday, LocalDate.of(2026, 8, 20)));
        assertEquals(2, Accuracy.lead(wednesday, LocalDate.of(2026, 8, 21)));
        assertEquals(3, Accuracy.lead(wednesday, LocalDate.of(2026, 8, 24)), "the weekend is not counted");
        assertEquals(10, Accuracy.lead(wednesday, LocalDate.of(2026, 9, 2)));
        LocalDate saturday = LocalDate.of(2026, 8, 22);
        assertEquals(1, Accuracy.lead(saturday, LocalDate.of(2026, 8, 24)), "a weekend run starts on Monday");
    }

    @Test
    void scoresATeamByMemberAndByLead() {
        // A: forecast 8 on Monday (logged 6), 8 on Tuesday (logged 10, capacity 8: actual overload, forecast no overload).
        // B: forecast 10 on Monday (capacity 8: forecast overload, logged 4: not actual), Tuesday forecast 8 with no log at all (truth 0).
        // Wednesday is after `to`; Saturday rows never exist. Run 1 was made on Thursday 2026-08-20 (lead 2 on Monday, 3 on Tuesday);
        // run 2 on Friday 2026-08-21 (lead 1 on Monday, 2 on Tuesday) and is the current one.
        List<CurrentDayForecast> current = List.of(
                current(A, MON, RUN2, 8, 8), current(A, TUE, RUN2, 8, 8), current(A, WED, RUN2, 8, 8),
                current(B, MON, RUN2, 10, 8), current(B, TUE, RUN2, 8, 8));
        List<RunDayForecast> runDays = List.of(
                runDay(RUN1, LocalDate.of(2026, 8, 20), A, MON, 7, 8), runDay(RUN1, LocalDate.of(2026, 8, 20), A, TUE, 7, 8),
                runDay(RUN2, LocalDate.of(2026, 8, 21), A, MON, 8, 8), runDay(RUN2, LocalDate.of(2026, 8, 21), A, TUE, 8, 8),
                runDay(RUN2, LocalDate.of(2026, 8, 21), B, MON, 10, 8), runDay(RUN2, LocalDate.of(2026, 8, 21), B, TUE, 8, 8));
        SortedMap<MemberDay, Double> logged = new TreeMap<>();
        logged.put(new MemberDay(A, MON), 6.0);
        logged.put(new MemberDay(A, TUE), 10.0);
        logged.put(new MemberDay(A, MON.minusDays(7)), 5.0);
        logged.put(new MemberDay(B, MON), 4.0);
        AccuracyResult r = Accuracy.evaluate(TEAM, MON, TUE, SAT, current, runDays, logged);

        assertEquals(4, r.current().size(), "Wednesday is outside the range");
        AccuracyRow aMon = r.current().get(0);
        assertEquals(new AccuracyRow(A, MON, RUN2, 1, 8, 6, 8, false, false), aMon);
        assertEquals(new AccuracyRow(A, TUE, RUN2, 2, 8, 10, 8, false, true), r.current().get(1));
        assertEquals(new AccuracyRow(B, MON, RUN2, 1, 10, 4, 8, true, false), r.current().get(2));
        assertEquals(new AccuracyRow(B, TUE, RUN2, 2, 8, 0, 8, false, false), r.current().get(3), "no log means zero hours");

        AccuracyScore team = r.scores().get(0);
        assertEquals("team", team.scope());
        assertEquals(TEAM.toString(), team.key());
        assertEquals(4, team.n());
        assertEquals((2 + 2 + 6 + 8) / 4.0, team.mae(), 1e-9);
        assertEquals((2 - 2 + 6 + 8) / 4.0, team.bias(), 1e-9);
        // MASE is scored on the rows whose member has a log the same weekday a week earlier: only A/Monday
        // has one (5 h), so the sum of |truth - forecast| over those rows is |6 - 8| and the naive floor |6 - 5|.
        assertEquals(1, team.maseN(), "only A/Monday has a prior-week log");
        assertEquals(Math.abs(6 - 8) / (double) Math.abs(6 - 5), team.mase(), 1e-9);
        assertEquals(0.0, team.overloadPrecision(), 1e-9, "one forecast overload (B Monday), not actual");
        assertEquals(0.0, team.overloadRecall(), 1e-9, "one actual overload (A Tuesday), not forecast");

        AccuracyScore a = r.scores().get(1);
        assertEquals("member", a.scope());
        assertEquals(A.toString(), a.key());
        assertEquals(2, a.n());
        assertEquals(2.0, a.mae(), 1e-9);
        assertEquals(0.0, a.bias(), 1e-9);
        assertEquals(1, a.maseN(), "A/Monday has a prior-week log, A/Tuesday has none");
        assertEquals(Math.abs(6 - 8) / (double) Math.abs(6 - 5), a.mase(), 1e-9);
        assertTrue(Double.isNaN(a.overloadPrecision()), "A never had a forecast overload");
        assertEquals(0.0, a.overloadRecall(), 1e-9);
        AccuracyScore b = r.scores().get(2);
        assertEquals(B.toString(), b.key());
        assertEquals(7.0, b.mae(), 1e-9);
        assertEquals(0, b.maseN(), "B logged nothing a week earlier");
        assertTrue(Double.isNaN(b.mase()), "no row with a naive means no MASE");
        assertTrue(Double.isNaN(b.overloadRecall()), "B never had an actual overload");

        List<AccuracyScore> leads = r.scores().stream().filter(s -> s.scope().equals("lead")).toList();
        assertEquals(List.of("1", "2", "3"), leads.stream().map(AccuracyScore::key).toList());
        assertEquals(2, leads.get(0).n(), "lead 1: run 2's Monday rows for A and B");
        assertEquals((2 + 6) / 2.0, leads.get(0).mae(), 1e-9);
        assertEquals(1, leads.get(0).maseN(), "of the two, only A/Monday has a prior-week log");
        assertEquals(Math.abs(6 - 8) / (double) Math.abs(6 - 5), leads.get(0).mase(), 1e-9);
        assertEquals(3, leads.get(1).n(), "lead 2: run 1's Monday row for A, run 2's Tuesday rows for A and B");
        assertEquals((Math.abs(7 - 6) + 2 + 8) / 3.0, leads.get(1).mae(), 1e-9);
        assertEquals(1, leads.get(1).maseN(), "of the three, only run 1's A/Monday has a prior-week log");
        assertEquals(Math.abs(6 - 7) / (double) Math.abs(6 - 5), leads.get(1).mase(), 1e-9);
        assertEquals(1, leads.get(2).n(), "lead 3: run 1's Tuesday row for A");
        assertEquals(3.0, leads.get(2).mae(), 1e-9);
        assertEquals(0, leads.get(2).maseN(), "A logged nothing the Tuesday a week earlier");
        assertTrue(Double.isNaN(leads.get(2).mase()));
        assertEquals(SAT, r.evaluatedAt());
    }

    @Test
    void anEmptyRangeGivesNaNScoresAndNoRows() {
        AccuracyResult r = Accuracy.evaluate(TEAM, MON, TUE, SAT, List.of(), List.of(), new TreeMap<>());
        assertTrue(r.current().isEmpty());
        assertEquals(1, r.scores().size(), "the team score is always there");
        assertEquals(0, r.scores().get(0).n());
        assertTrue(Double.isNaN(r.scores().get(0).mae()));
        assertEquals(0, r.scores().get(0).maseN());
        assertTrue(Double.isNaN(r.scores().get(0).mase()));
    }

    @Test
    void weekendsAndDaysOutsideTheRangeAreIgnored() {
        List<CurrentDayForecast> current = List.of(current(A, SAT, RUN2, 8, 8), current(A, MON.minusDays(3), RUN2, 8, 8));
        AccuracyResult r = Accuracy.evaluate(TEAM, MON, TUE, SAT, current, List.of(), new TreeMap<>());
        assertTrue(r.current().isEmpty());
    }
}
