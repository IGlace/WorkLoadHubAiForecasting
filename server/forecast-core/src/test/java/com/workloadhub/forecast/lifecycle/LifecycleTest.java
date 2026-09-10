package com.workloadhub.forecast.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LifecycleTest {

    static final UUID TEAM = TestData.TEAM;
    static final MemberRow ANA = TestData.member("ana", TEAM);
    static final MemberRow BEN = TestData.member("ben", TEAM);
    static final LocalDateTime CREATED = LocalDateTime.of(2026, 8, 3, 9, 0);

    @Test
    void assignedDateIsTheLatestTransitionResolvingToTheAssigneeByName() {
        TaskRow t = TestData.task("1", ANA.id(), CREATED, 8);
        ForecastData data = TestData.data(List.of(ANA, BEN), List.of(t), List.of(
                TestData.assignee(t.id(), BEN.fullName(), CREATED.plusDays(1)),
                TestData.assignee(t.id(), ANA.fullName(), CREATED.plusDays(5))), List.of());
        TaskFacts f = Lifecycle.derive(data).of(t.id());
        assertEquals(CREATED.plusDays(5), f.assigned());
        assertEquals(5, f.lagDays());
        assertFalse(f.fresh());
        assertFalse(f.assignmentFallback());
    }

    @Test
    void assignedDateResolvesUuidAndEmailToo() {
        TaskRow t = TestData.task("1", ANA.id(), CREATED, 8);
        ForecastData byUuid = TestData.data(List.of(ANA), List.of(t),
                List.of(TestData.assignee(t.id(), ANA.id().toString(), CREATED.plusHours(3))), List.of());
        assertEquals(CREATED.plusHours(3), Lifecycle.derive(byUuid).of(t.id()).assigned());
        ForecastData byEmail = TestData.data(List.of(ANA), List.of(t),
                List.of(TestData.assignee(t.id(), ANA.email().toUpperCase(), CREATED.plusHours(4))), List.of());
        TaskFacts f = Lifecycle.derive(byEmail).of(t.id());
        assertEquals(CREATED.plusHours(4), f.assigned());
        assertEquals(0, f.lagDays());
        assertTrue(f.fresh());
    }

    @Test
    void noTransitionMeansAssignedAtCreationAndUnresolvableFallsBackAndIsCounted() {
        TaskRow plain = TestData.task("1", ANA.id(), CREATED, 8);
        TaskRow odd = TestData.task("2", ANA.id(), CREATED, 8);
        MemberRow twin = TestData.member("ana2", TEAM).withFullName(ANA.fullName());
        ForecastData data = TestData.data(List.of(ANA, twin), List.of(plain, odd),
                List.of(TestData.assignee(odd.id(), ANA.fullName(), CREATED.plusDays(2))), List.of());
        Lifecycle lc = Lifecycle.derive(data);
        assertEquals(CREATED, lc.of(plain.id()).assigned());
        assertFalse(lc.of(plain.id()).assignmentFallback());
        assertEquals(CREATED, lc.of(odd.id()).assigned(), "an ambiguous name falls back to created_date");
        assertTrue(lc.of(odd.id()).assignmentFallback());
        assertEquals(List.of(odd.key()), lc.unresolvedAssignments());
    }

    @Test
    void unassignedTaskHasNoAssignmentAndNoneValueMeansUnassigned() {
        TaskRow t = TestData.task("1", null, CREATED, 8);
        ForecastData data = TestData.data(List.of(ANA), List.of(t),
                List.of(TestData.assignee(t.id(), "None", CREATED.plusDays(1))), List.of());
        TaskFacts f = Lifecycle.derive(data).of(t.id());
        assertNull(f.assigned());
        assertFalse(f.isAssigned());
        assertEquals(0, f.lagDays());
        assertTrue(Lifecycle.derive(data).unresolvedAssignments().isEmpty());
    }

    @Test
    void startedAndFinishedComeFromColumnsElseTransitions() {
        TaskRow fromColumns = TestData.task("1", ANA.id(), CREATED, 8)
                .withStatus("DONE").withStarted(CREATED.plusDays(1)).withFinished(CREATED.plusDays(3));
        TaskRow fromHistory = TestData.task("2", ANA.id(), CREATED, 8).withStatus("DONE");
        ForecastData data = TestData.data(List.of(ANA), List.of(fromColumns, fromHistory), List.of(
                TestData.status(fromHistory.id(), "In Progress", CREATED.plusDays(2)),
                TestData.status(fromHistory.id(), "In Review", CREATED.plusDays(4)),
                TestData.status(fromHistory.id(), "Done", CREATED.plusDays(6)),
                TestData.status(fromHistory.id(), "Closed", CREATED.plusDays(7))), List.of());
        Lifecycle lc = Lifecycle.derive(data);
        assertEquals(CREATED.plusDays(1), lc.of(fromColumns.id()).started());
        assertEquals(CREATED.plusDays(3), lc.of(fromColumns.id()).finished());
        assertEquals(CREATED.plusDays(2), lc.of(fromHistory.id()).started(), "first entry into IN_PROGRESS");
        assertEquals(CREATED.plusDays(6), lc.of(fromHistory.id()).finished(), "first entry into DONE");
        assertEquals(7, lc.of(fromHistory.id()).cycleDays());
    }

    @Test
    void anOpenTaskIsNeverFinishedEvenWithAStaleColumn() {
        TaskRow reopened = TestData.task("1", ANA.id(), CREATED, 8)
                .withStatus("IN_PROGRESS").withFinished(CREATED.plusDays(3)).withReopened(true);
        Lifecycle lc = Lifecycle.derive(TestData.data(List.of(ANA), List.of(reopened), List.of(), List.of()));
        assertNull(lc.of(reopened.id()).finished());
        assertTrue(lc.of(reopened.id()).openAtEndOf(CREATED.toLocalDate().plusDays(10)));
        assertFalse(lc.of(reopened.id()).openAtEndOf(CREATED.toLocalDate().minusDays(1)));
    }

    @Test
    void actualHoursSumTheAssigneeLogsElseEstimateMinusRemainingWhenDone() {
        TaskRow logged = TestData.task("1", ANA.id(), CREATED, 8).withStatus("DONE").withFinished(CREATED.plusDays(3));
        TaskRow silent = TestData.task("2", ANA.id(), CREATED, 10).withStatus("DONE").withFinished(CREATED.plusDays(3)).withRemaining(2.0);
        TaskRow open = TestData.task("3", ANA.id(), CREATED, 10);
        ForecastData data = TestData.data(List.of(ANA, BEN), List.of(logged, silent, open), List.of(), List.of(
                TestData.log(logged.id(), ANA.id(), CREATED.toLocalDate(), 3),
                TestData.log(logged.id(), ANA.id(), CREATED.toLocalDate().plusDays(1), 4.5),
                TestData.log(logged.id(), BEN.id(), CREATED.toLocalDate().plusDays(1), 2)));
        Lifecycle lc = Lifecycle.derive(data);
        assertEquals(7.5, lc.of(logged.id()).actualHours(), 1e-9, "only the assignee's logs");
        assertFalse(lc.of(logged.id()).unlogged());
        assertEquals(8.0, lc.of(silent.id()).actualHours(), 1e-9);
        assertTrue(lc.of(silent.id()).unlogged());
        assertEquals(0.0, lc.of(open.id()).actualHours(), 1e-9);
        assertFalse(lc.of(open.id()).unlogged(), "an open task without logs is not a data-quality issue");
        assertEquals(List.of(silent.key()), lc.unloggedTasks());
    }

    @Test
    void aFinishedTaskWithAZeroHourAssigneeLogIsNotTreatedAsUnlogged() {
        TaskRow t = TestData.task("1", ANA.id(), CREATED, 8).withStatus("DONE").withFinished(CREATED.plusDays(3));
        ForecastData data = TestData.data(List.of(ANA), List.of(t), List.of(),
                List.of(TestData.log(t.id(), ANA.id(), CREATED.toLocalDate(), 0.0)));
        TaskFacts f = Lifecycle.derive(data).of(t.id());
        assertEquals(0.0, f.actualHours(), 1e-9, "a real zero-hour log is not the same as no log at all");
        assertFalse(f.unlogged());
    }

    @Test
    void familyAndModeFollowTheTables() {
        TaskRow epic = TestData.task("1", ANA.id(), CREATED, 40).withType("Epic");
        TaskRow sub = TestData.task("2", ANA.id(), CREATED, 4).withType("Sub-task").withParent(epic.id());
        TaskRow bug = TestData.task("3", ANA.id(), CREATED, 4).withType("Bug").withReporter(ANA.id());
        TaskRow spike = TestData.task("4", ANA.id(), CREATED, 4).withType("Spike").withReporter(BEN.id());
        TaskRow orphanSub = TestData.task("5", ANA.id(), CREATED, 4).withType("Sub-task");
        Lifecycle lc = Lifecycle.derive(TestData.data(List.of(ANA, BEN), List.of(epic, sub, bug, spike, orphanSub), List.of(), List.of()));
        assertEquals(Family.CONTAINER, lc.of(epic.id()).family());
        assertEquals(Family.CONTAINER, lc.of(sub.id()).family(), "a sub-task takes its parent's family");
        assertEquals(Mode.PROJECT, lc.of(sub.id()).mode());
        assertEquals(Family.DEFECT, lc.of(bug.id()).family());
        assertEquals(Mode.SELF_PICKED, lc.of(bug.id()).mode());
        assertEquals(Family.SUPPORT, lc.of(spike.id()).family());
        assertEquals(Mode.MANUAL, lc.of(spike.id()).mode());
        assertEquals(Family.DELIVERY, lc.of(orphanSub.id()).family());
    }

    @Test
    void seededDataResolvesEveryAssignmentAndSplitsFreshFromBacklog() {
        Lifecycle lc = Lifecycle.derive(SeededData.data());
        List<UUID> ids = lc.facts().keySet().stream().toList();
        List<UUID> sortedIds = ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        assertEquals(sortedIds, ids, "facts() must iterate in id-string order for deterministic downstream reads");
        assertTrue(lc.unresolvedAssignments().isEmpty(), "seed writes assignee names the rule resolves: " + lc.unresolvedAssignments());
        long assigned = lc.all().stream().filter(TaskFacts::isAssigned).count();
        long fresh = lc.all().stream().filter(f -> f.isAssigned() && f.fresh()).count();
        assertTrue(assigned > 0);
        assertTrue(fresh > assigned * 0.3 && fresh < assigned, "fresh " + fresh + " of " + assigned);
        long doneWithHours = lc.all().stream().filter(f -> f.done() && f.actualHours() > 0).count();
        long done = lc.all().stream().filter(TaskFacts::done).count();
        assertTrue(doneWithHours > done * 0.9, "done tasks carry hours: " + doneWithHours + " of " + done);
        LocalDate asOf = SeededData.asOf();
        for (TaskFacts f : lc.all()) {
            if (f.isAssigned()) {
                assertFalse(f.assigned().toLocalDate().isAfter(asOf));
                assertFalse(f.assigned().isBefore(f.task().createdDate()), "assigned before created: " + f.task().key());
            }
        }
    }
}
