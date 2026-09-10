package com.workloadhub.forecast.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Family;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EffortModelTest {

    static final WorkingCalendar CAL = WorkingCalendar.fromHolidays(List.of());
    static final MemberRow ANA = TestData.member("ana", TestData.TEAM);
    static final MemberRow BEN = TestData.member("ben", TestData.TEAM);
    static final LocalDate MON = LocalDate.of(2026, 8, 3);

    /** Ana: three delivery tasks at ratio 2.0, cycle 3; Ben: one bug at ratio 1.0, cycle 10. */
    static ForecastData history() {
        List<TaskRow> tasks = new ArrayList<>();
        List<com.workloadhub.forecast.data.rows.TimeLogRow> logs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            LocalDateTime created = MON.minusWeeks(6 - i).atTime(9, 0);
            TaskRow t = TestData.task("a" + i, ANA.id(), created, 4).withStatus("DONE")
                    .withFinished(created.plusDays(2)).withDue(created.toLocalDate().plusDays(1)).withRemaining(0.0);
            tasks.add(t);
            logs.add(TestData.log(t.id(), ANA.id(), created.toLocalDate(), 8));
        }
        LocalDateTime created = MON.minusWeeks(4).atTime(9, 0);
        TaskRow bug = TestData.task("b", BEN.id(), created, 6).withType("Bug").withStatus("DONE").withFinished(created.plusDays(9)).withRemaining(0.0);
        tasks.add(bug);
        logs.add(TestData.log(bug.id(), BEN.id(), created.toLocalDate(), 6));
        return TestData.data(List.of(ANA, BEN), tasks, List.of(), logs);
    }

    static EffortModel model() {
        ForecastData data = history();
        return EffortModel.fit(Lifecycle.derive(data), data);
    }

    @Test
    void ratiosShrinkTowardTheTeamAndClip() {
        EffortModel m = model();
        double team = (2.0 * 3 + 1.0) / 4;                     // team mean over four tasks
        double teamDelivery = 2.0;
        double expected = EffortModel.shrink(3, 2.0, teamDelivery, EffortModel.SHRINK_K);
        assertEquals(expected, m.estimateRatio(ANA.id(), Family.DELIVERY, TestData.TEAM), 1e-9);
        assertEquals(EffortModel.shrink(3, 2.0, team, EffortModel.SHRINK_K), m.estimateRatio(ANA.id(), null, TestData.TEAM), 1e-9);
        assertEquals(team, m.estimateRatio(UUID.randomUUID(), null, TestData.TEAM), 1e-9, "unknown member: the team prior");
        assertEquals(team, m.estimateRatio(ANA.id(), Family.SUPPORT, TestData.TEAM), 1e-9,
                "no support history at any level: the team prior itself");
        assertTrue(m.estimateRatio(UUID.randomUUID(), null, UUID.randomUUID()) >= EffortModel.RATIO_MIN);
    }

    @Test
    void cyclesAndLatenessUseMediansWithTheSameShrinkage() {
        EffortModel m = model();
        double teamCycle = 3.0;                                  // median of {3, 3, 3, 10}
        assertEquals(EffortModel.shrink(3, 3.0, teamCycle, EffortModel.SHRINK_K), m.memberCycleDays(ANA.id(), TestData.TEAM), 1e-9);
        assertEquals(EffortModel.shrink(1, 10.0, teamCycle, EffortModel.SHRINK_K), m.memberCycleDays(BEN.id(), TestData.TEAM), 1e-9);
        assertEquals(EffortModel.shrink(1, 10.0, 10.0, EffortModel.SHRINK_K), m.familyCycleDays(BEN.id(), Family.DEFECT, TestData.TEAM), 1e-9,
                "team defect median is Ben's own bug");
        assertEquals(1.0, m.memberLatenessDays(ANA.id(), TestData.TEAM), "finished one day after due");
        assertEquals(1.0, m.memberLatenessDays(BEN.id(), TestData.TEAM), "no due dates: the team median");
        assertEquals(EffortModel.DEFAULT_CYCLE_DAYS, EffortModel.fit(Lifecycle.derive(TestData.data(List.of(ANA), List.of(), List.of(), List.of())),
                TestData.data(List.of(ANA), List.of(), List.of(), List.of())).memberCycleDays(ANA.id(), TestData.TEAM), 1e-9);
    }

    @Test
    void openTasksPlaceTheirRemainingHoursFromTheStartToTheLaterOfCycleAndDue() {
        ForecastData data = history();
        EffortModel m = EffortModel.fit(Lifecycle.derive(data), data);
        TaskRow open = TestData.task("o", ANA.id(), MON.minusDays(3).atTime(9, 0), 10).withRemaining(6.0).withDue(MON.plusDays(8));
        TaskRow noRemaining = TestData.task("p", ANA.id(), MON.atTime(9, 0), 10).withRemaining(null);
        ForecastData withOpen = TestData.data(List.of(ANA, BEN), List.of(open, noRemaining), List.of(), List.of(
                TestData.log(noRemaining.id(), ANA.id(), MON, 3)));
        Lifecycle lc = Lifecycle.derive(withOpen);
        List<TaskFacts> openFacts = List.of(lc.of(open.id()), lc.of(noRemaining.id()));
        SortedMap<MemberWeek, Double> placed = EffortModel.placeOpenTasks(openFacts, m, MON, id -> TestData.TEAM, id -> Set.of(), CAL);
        double total = placed.values().stream().mapToDouble(Double::doubleValue).sum();
        double ratio = m.estimateRatio(ANA.id(), Family.DELIVERY, TestData.TEAM);
        assertEquals(6.0 + Math.max(0, 10 * ratio - 3), total, 1e-9, "remaining column, else estimate × ratio minus logged");
        assertTrue(placed.containsKey(new MemberWeek(ANA.id(), MON)));
        assertTrue(placed.containsKey(new MemberWeek(ANA.id(), MON.plusWeeks(1))), "due in week 2 plus one day of lateness");
        assertTrue(placed.keySet().stream().allMatch(k -> !k.week().isBefore(MON)), "nothing before the placement start");
    }

    @Test
    void newArrivalsAreScaledByTheRatioAndSpreadOverTheCycle() {
        EffortModel m = model();
        SortedMap<MemberWeek, Double> placed = EffortModel.placeNewArrivals(
                Map.of(new MemberWeek(BEN.id(), MON), 10.0), m, id -> TestData.TEAM, id -> Set.of(), CAL);
        double ratio = m.estimateRatio(BEN.id(), null, TestData.TEAM);
        assertEquals(10.0 * ratio, placed.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        int span = (int) Math.round(m.memberCycleDays(BEN.id(), TestData.TEAM));
        assertEquals(span > 5 ? 2 : 1, placed.size(), "a cycle longer than a working week spills into the next");
    }

    @Test
    void seededFitIsWithinTheClipsAndPlacesEveryOpenHour() {
        ForecastData data = SeededData.data();
        Lifecycle lc = Lifecycle.derive(data);
        EffortModel m = EffortModel.fit(lc, data);
        for (MemberRow member : data.members()) {
            double r = m.estimateRatio(member.id(), null, member.primaryTeamId());
            assertTrue(r >= EffortModel.RATIO_MIN && r <= EffortModel.RATIO_MAX);
            assertTrue(m.memberCycleDays(member.id(), member.primaryTeamId()) >= 1.0);
        }
        List<TaskFacts> open = lc.all().stream().filter(f -> f.isAssigned() && !f.done()).toList();
        LocalDate f1 = com.workloadhub.forecast.calendar.Weeks.forecastWeeks(SeededData.asOf())[0];
        Map<UUID, MemberRow> members = data.memberById();
        SortedMap<MemberWeek, Double> placed = EffortModel.placeOpenTasks(
                open.stream().filter(f -> members.containsKey(f.assignee())).toList(), m, f1,
                id -> members.get(id).primaryTeamId(), id -> Set.of(), WorkingCalendar.fromHolidays(data.holidays()));
        double expected = open.stream().filter(f -> members.containsKey(f.assignee()))
                .mapToDouble(f -> f.remaining() != null ? f.remaining() : Math.max(0, f.estimate() * m.estimateRatio(f.assignee(), f.family(), members.get(f.assignee()).primaryTeamId()) - f.actualHours()))
                .sum();
        assertEquals(expected, placed.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-6, "hours are moved, never lost");
    }
}
