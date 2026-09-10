package com.workloadhub.forecast.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TruncationTest {

    static final MemberRow ANA = TestData.member("ana", TestData.TEAM);
    static final MemberRow BEN = TestData.member("ben", TestData.TEAM);
    static final LocalDate CUT = LocalDate.of(2026, 8, 9);
    static final LocalDateTime BEFORE = CUT.minusDays(5).atTime(9, 0);

    @Test
    void dropsWhatDidNotExistAndRewindsStatusAssigneeAndRemaining() {
        TaskRow future = TestData.task("1", ANA.id(), CUT.plusDays(1).atTime(9, 0), 8);
        TaskRow finishedLater = TestData.task("2", ANA.id(), BEFORE, 10).withStatus("DONE")
                .withStarted(CUT.minusDays(2).atTime(9, 0)).withFinished(CUT.plusDays(3).atTime(9, 0)).withRemaining(0.0);
        TaskRow assignedLater = TestData.task("3", ANA.id(), BEFORE, 6);
        TaskRow reassigned = TestData.task("4", BEN.id(), BEFORE, 4);
        ForecastData data = TestData.data(List.of(ANA, BEN), List.of(future, finishedLater, assignedLater, reassigned), List.of(
                TestData.assignee(assignedLater.id(), ANA.fullName(), CUT.plusDays(2).atTime(9, 0)),
                TestData.assignee(reassigned.id(), ANA.fullName(), BEFORE.plusDays(1)),
                TestData.assignee(reassigned.id(), BEN.fullName(), CUT.plusDays(4).atTime(9, 0)),
                TestData.status(finishedLater.id(), "Done", CUT.plusDays(3).atTime(9, 0))), List.of(
                TestData.log(finishedLater.id(), ANA.id(), CUT.minusDays(1), 3),
                TestData.log(finishedLater.id(), ANA.id(), CUT.plusDays(1), 7)));
        ForecastData cut = Truncation.at(data, CUT);
        assertEquals(3, cut.tasks().size());
        TaskRow t2 = cut.taskById().get(finishedLater.id());
        assertEquals("IN_PROGRESS", t2.statusCategory());
        assertNull(t2.finishedDate());
        assertEquals(7.0, t2.remaining(), 1e-9, "10 − 3 logged by the cutoff");
        assertNull(cut.taskById().get(assignedLater.id()).assigneeId());
        assertEquals(ANA.id(), cut.taskById().get(reassigned.id()).assigneeId(), "Ana still held it at the cutoff");
        assertEquals(1, cut.timeLogs().size());
        assertTrue(cut.transitions().stream().allMatch(t -> !t.changedAt().toLocalDate().isAfter(CUT)));
        Lifecycle lc = Lifecycle.derive(cut);
        assertEquals(BEFORE.plusDays(1), lc.of(reassigned.id()).assigned());
        assertNull(lc.of(finishedLater.id()).finished());
    }

    @Test
    void seededTruncationKeepsOnlyWhatWasVisible() {
        ForecastData data = SeededData.data();
        LocalDate cut = SeededData.asOf().minusWeeks(8);
        ForecastData t = Truncation.at(data, cut);
        assertTrue(t.tasks().size() < data.tasks().size());
        assertEquals(data.members().size(), t.members().size());
        for (TaskRow task : t.tasks()) {
            assertTrue(!task.createdDate().toLocalDate().isAfter(cut));
            if (task.finishedDate() != null) {
                assertTrue(!task.finishedDate().toLocalDate().isAfter(cut));
            }
        }
        Lifecycle lc = Lifecycle.derive(t);
        assertTrue(lc.all().stream().filter(TaskFacts::isAssigned).allMatch(f -> !f.assigned().toLocalDate().isAfter(cut)));
    }
}
