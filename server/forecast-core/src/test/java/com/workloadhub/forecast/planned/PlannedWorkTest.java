package com.workloadhub.forecast.planned;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import com.workloadhub.forecast.model.EffortModel;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlannedWorkTest {

    static final WorkingCalendar CAL = WorkingCalendar.fromHolidays(List.of());
    static final LocalDate AS_OF = LocalDate.of(2026, 9, 2);              // a Wednesday
    static final LocalDate F1 = LocalDate.of(2026, 9, 7);
    static final MemberRow ANA = TestData.member("ana", TestData.TEAM);
    static final MemberRow BEN = TestData.member("ben", TestData.TEAM);
    static final MemberRow CID = TestData.member("cid", TestData.TEAM);
    static final UUID PROJECT = TestData.id("proj");
    static final UUID OTHER = TestData.id("other-proj");

    /** History: Ana took 6 of the project's tasks (4 Bugs), Ben 2 (Tasks), Cid none; each assigned 3 days after creation. */
    static ForecastData world(List<TaskRow> extra) {
        List<TaskRow> tasks = new ArrayList<>();
        List<com.workloadhub.forecast.data.rows.TransitionRow> history = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            LocalDateTime created = AS_OF.minusWeeks(10 - i).atTime(9, 0);
            TaskRow t = TestData.task("a" + i, ANA.id(), created, 4).withProject(PROJECT).withType(i < 4 ? "Bug" : "Task");
            tasks.add(t);
            history.add(TestData.assignee(t.id(), ANA.fullName(), created.plusDays(3)));
        }
        for (int i = 0; i < 2; i++) {
            LocalDateTime created = AS_OF.minusWeeks(8 - i).atTime(9, 0);
            TaskRow t = TestData.task("b" + i, BEN.id(), created, 4).withProject(PROJECT);
            tasks.add(t);
            history.add(TestData.assignee(t.id(), BEN.fullName(), created.plusDays(3)));
        }
        tasks.addAll(extra);
        return TestData.data(List.of(ANA, BEN, CID), tasks, history, List.of()).withProjects(List.of(
                new ProjectRow(PROJECT, "PRJ", "Project", "ACTIVE", TestData.TEAM),
                new ProjectRow(OTHER, "OTH", "Other", "ACTIVE", TestData.id("far-team"))));
    }

    @Test
    void candidatesAreUnassignedOpenTasksOfTheTeamsProjectsOlderThanTheLag() {
        TaskRow fresh = TestData.task("c1", null, AS_OF.minusDays(1).atTime(9, 0), 8).withProject(PROJECT);
        TaskRow old = TestData.task("c2", null, AS_OF.minusDays(4).atTime(9, 0), 8).withProject(PROJECT);
        TaskRow done = TestData.task("c3", null, AS_OF.minusDays(4).atTime(9, 0), 8).withProject(PROJECT).withStatus("DONE");
        TaskRow elsewhere = TestData.task("c4", null, AS_OF.minusDays(4).atTime(9, 0), 8).withProject(OTHER);
        ForecastData data = world(List.of(fresh, old, done, elsewhere));
        List<TaskFacts> c = PlannedWork.candidates(Lifecycle.derive(data), data, TestData.TEAM, AS_OF);
        assertEquals(List.of(old.id()), c.stream().map(TaskFacts::id).toList());
    }

    @Test
    void weightsFollowTheProjectAndFamilyHistoryWithShrinkage() {
        TaskRow bug = TestData.task("c", null, AS_OF.minusDays(4).atTime(9, 0), 8).withProject(PROJECT).withType("Bug");
        ForecastData data = world(List.of(bug));
        Lifecycle lc = Lifecycle.derive(data);
        List<MemberRow> eligible = List.of(ANA, BEN, CID);
        List<TaskFacts> history = PlannedWork.history(lc, eligible, AS_OF);
        Map<UUID, Double> w = PlannedWork.weights(lc.of(bug.id()), eligible, history);
        double k = PlannedWork.SHRINK_K;
        double equal = 1.0 / 3;
        // level 3: team × DEFECT: Ana 4 of 4
        double ana3 = (4 + k * equal) / (4 + k);
        double ben3 = (0 + k * equal) / (4 + k);
        // level 2: project: Ana 6, Ben 2 of 8
        double ana2 = (6 + k * ana3) / (8 + k);
        double ben2 = (2 + k * ben3) / (8 + k);
        // level 1: project × DEFECT: Ana 4 of 4
        assertEquals((4 + k * ana2) / (4 + k), w.get(ANA.id()), 1e-9);
        assertEquals((0 + k * ben2) / (4 + k), w.get(BEN.id()), 1e-9);
        assertEquals(1.0, w.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        assertTrue(w.get(CID.id()) > 0, "shrinkage keeps a newcomer in the running");
        assertTrue(w.get(ANA.id()) > w.get(BEN.id()) && w.get(BEN.id()) > w.get(CID.id()));
    }

    @Test
    void unknownProjectFallsThroughToTheTeamLevelAndNoHistoryIsAnEqualSplit() {
        TaskRow task = TestData.task("c", null, AS_OF.minusDays(4).atTime(9, 0), 8).withProject(TestData.id("new-proj")).withType("Task");
        ForecastData data = world(List.of(task));
        Lifecycle lc = Lifecycle.derive(data);
        List<MemberRow> eligible = List.of(ANA, BEN, CID);
        Map<UUID, Double> w = PlannedWork.weights(lc.of(task.id()), eligible, PlannedWork.history(lc, eligible, AS_OF));
        double k = PlannedWork.SHRINK_K;
        double equal = 1.0 / 3;
        assertEquals((2 + k * equal) / (4 + k), w.get(ANA.id()), 1e-9, "team × DELIVERY: Ana 2, Ben 2");
        assertEquals(w.get(ANA.id()), w.get(BEN.id()), 1e-9);
        Map<UUID, Double> none = PlannedWork.weights(lc.of(task.id()), eligible, List.of());
        assertEquals(equal, none.get(CID.id()), 1e-9);
    }

    @Test
    void lagIsTheProjectMedianWhenFiveTasksExistElseTheTeamThenAllThenTwoDays() {
        TaskRow task = TestData.task("c", null, AS_OF.minusDays(4).atTime(9, 0), 8).withProject(PROJECT);
        ForecastData data = world(List.of(task));
        Lifecycle lc = Lifecycle.derive(data);
        assertEquals(3, PlannedWork.lagDays(lc.of(task.id()), lc, data, Set.of(ANA.id(), BEN.id(), CID.id())));
        TaskRow elsewhere = TestData.task("d", null, AS_OF.minusDays(4).atTime(9, 0), 8).withProject(OTHER);
        ForecastData data2 = world(List.of(elsewhere));
        Lifecycle lc2 = Lifecycle.derive(data2);
        assertEquals(3, PlannedWork.lagDays(lc2.of(elsewhere.id()), lc2, data2, Set.of(ANA.id(), BEN.id())), "no project history: the team's");
        ForecastData bare = TestData.data(List.of(ANA), List.of(elsewhere), List.of(), List.of());
        Lifecycle lc3 = Lifecycle.derive(bare);
        assertEquals(Lifecycle.BACKLOG_LAG_DAYS, PlannedWork.lagDays(lc3.of(elsewhere.id()), lc3, bare, Set.of(ANA.id())));
    }

    @Test
    void allocationPlacesSharesFromTheExpectedDateAndReportsWhatFallsAfterTheWindow() {
        TaskRow soon = TestData.task("c1", null, AS_OF.minusDays(4).atTime(9, 0), 10).withProject(PROJECT).withType("Bug");
        TaskRow overdue = TestData.task("c2", null, AS_OF.minusWeeks(3).atTime(9, 0), 6).withProject(PROJECT);
        TaskRow far = TestData.task("c3", null, AS_OF.minusDays(2).atTime(9, 0), 4).withProject(PROJECT);
        ForecastData data = world(List.of(soon, overdue, far));
        Lifecycle lc = Lifecycle.derive(data);
        EffortModel effort = EffortModel.fit(lc, data);
        PlannedWork.Request req = new PlannedWork.Request(TestData.TEAM, List.of(ANA, BEN, CID), AS_OF, new LocalDate[] {F1, F1.plusWeeks(1)});
        PlannedWork.Allocation a = PlannedWork.allocate(req, lc, data, effort, CAL, id -> Set.of());
        assertEquals(3, a.candidateCount());
        assertEquals(20.0, a.candidateHours(), 1e-9);
        assertEquals(9, a.pieces().size(), "three candidates × three members");
        for (PlannedWork.Piece p : a.pieces()) {
            assertTrue(!p.expectedDate().isBefore(F1), "expected dates are floored at the first forecast week");
            assertEquals(p.estimate() * p.share() * effort.estimateRatio(p.member(), p.family(), TestData.TEAM),
                    p.hoursInWindow() + p.hoursAfterWindow(), 1e-9, "hours are conserved");
            if (p.taskId().equals(overdue.id())) {
                assertEquals(F1, p.expectedDate(), "created + 3 days is in the past: overdue for assignment");
                assertEquals(F1, p.expectedWeek());
            }
        }
        double inWindow = a.hours().values().stream().mapToDouble(Double::doubleValue).sum();
        double total = a.pieces().stream().mapToDouble(p -> p.hoursInWindow() + p.hoursAfterWindow()).sum();
        assertEquals(total - a.hoursAfterWindow(), inWindow, 1e-9);
        assertTrue(a.hours().keySet().stream().allMatch(k -> k.week().equals(F1) || k.week().equals(F1.plusWeeks(1))));
        assertTrue(a.hours().containsKey(new MemberWeek(ANA.id(), F1)));
    }

    @Test
    void aMemberAbsentEveryWorkingDayOfTheForecastWindowGetsNoShare() {
        TaskRow c = TestData.task("c1", null, AS_OF.minusDays(4).atTime(9, 0), 10).withProject(PROJECT);
        ForecastData data = world(List.of(c));
        Lifecycle lc = Lifecycle.derive(data);
        EffortModel effort = EffortModel.fit(lc, data);
        PlannedWork.Request req = new PlannedWork.Request(TestData.TEAM, List.of(ANA, BEN), AS_OF, new LocalDate[] {F1, F1.plusWeeks(1)});
        Set<LocalDate> anaOff = Set.copyOf(CAL.workingDays(F1, F1.plusWeeks(1).plusDays(6), Set.of()));
        PlannedWork.Allocation a = PlannedWork.allocate(req, lc, data, effort, CAL, id -> id.equals(ANA.id()) ? anaOff : Set.of());
        assertEquals(1, a.pieces().size(), "only Ben is eligible");
        assertEquals(BEN.id(), a.pieces().get(0).member());
        assertEquals(1.0, a.pieces().get(0).share(), 1e-9, "all shares go to the other member");
        assertTrue(a.hours().keySet().stream().noneMatch(k -> k.member().equals(ANA.id())));
    }

    @Test
    void noEligibleMemberMeansAnEmptyAllocationThatStillCountsTheBacklog() {
        TaskRow c = TestData.task("c1", null, AS_OF.minusDays(4).atTime(9, 0), 10).withProject(PROJECT);
        ForecastData data = world(List.of(c));
        Lifecycle lc = Lifecycle.derive(data);
        MemberRow gone = ANA.withLeft(AS_OF.minusDays(1));
        PlannedWork.Request req = new PlannedWork.Request(TestData.TEAM, List.of(gone), AS_OF, new LocalDate[] {F1, F1.plusWeeks(1)});
        PlannedWork.Allocation a = PlannedWork.allocate(req, lc, data, EffortModel.fit(lc, data), CAL, id -> Set.of());
        assertTrue(a.pieces().isEmpty());
        assertTrue(a.hours().isEmpty());
        assertEquals(1, a.candidateCount());
        assertEquals(10.0, a.candidateHours(), 1e-9);
    }

    @Test
    void seededTeamsHaveABacklogAndTheAllocationIsConserved() {
        ForecastData data = SeededData.data();
        Lifecycle lc = Lifecycle.derive(data);
        EffortModel effort = EffortModel.fit(lc, data);
        LocalDate asOf = SeededData.asOf();
        LocalDate[] weeks = com.workloadhub.forecast.calendar.Weeks.forecastWeeks(asOf);
        int teamsWithBacklog = 0;
        for (com.workloadhub.forecast.data.rows.TeamRow team : data.teams()) {
            List<MemberRow> members = data.membersOfTeam(team.id());
            if (members.isEmpty()) {
                continue;
            }
            PlannedWork.Allocation a = PlannedWork.allocate(new PlannedWork.Request(team.id(), members, asOf, weeks), lc, data, effort,
                    WorkingCalendar.fromHolidays(data.holidays()), id -> Set.of());
            if (a.candidateCount() > 0) {
                teamsWithBacklog++;
            }
            double pieces = a.pieces().stream().mapToDouble(p -> p.hoursInWindow() + p.hoursAfterWindow()).sum();
            double placed = a.hours().values().stream().mapToDouble(Double::doubleValue).sum() + a.hoursAfterWindow();
            assertEquals(pieces, placed, 1e-6, team.name());
        }
        assertTrue(teamsWithBacklog > 0, "the seed leaves an unassigned backlog");
    }
}
