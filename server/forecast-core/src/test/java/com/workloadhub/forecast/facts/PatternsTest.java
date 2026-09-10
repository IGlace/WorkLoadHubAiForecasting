package com.workloadhub.forecast.facts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PatternsTest {

    static final LocalDate AS_OF = LocalDate.of(2026, 9, 2);              // Wednesday; window Monday 2026-06-01 .. 2026-08-30
    static final MemberRow ANA = TestData.member("ana", TestData.TEAM);
    static final UUID PROJECT = TestData.id("proj");

    @Test
    void recentWindowStatisticsFollowTheThirteenWeeks() {
        LocalDateTime mon = LocalDate.of(2026, 8, 24).atTime(9, 0);       // Monday of the last window week
        TaskRow a = TestData.task("1", ANA.id(), mon, 8).withProject(PROJECT).withReporter(ANA.id());
        TaskRow b = TestData.task("2", ANA.id(), mon.plusDays(1), 4).withType("Bug");
        TaskRow old = TestData.task("3", ANA.id(), LocalDate.of(2026, 5, 4).atTime(9, 0), 40);
        TaskRow current = TestData.task("4", ANA.id(), LocalDate.of(2026, 8, 31).atTime(9, 0), 6);   // this week: outside the window
        ForecastData data = TestData.data(List.of(ANA), List.of(a, b, old, current), List.of(), List.of())
                .withProjects(List.of(new ProjectRow(PROJECT, "PRJ", "Project", "ACTIVE", TestData.TEAM)));
        MemberPattern p = Patterns.of(ANA.id(), Lifecycle.derive(data), data, AS_OF);
        assertEquals(2, p.tasks13w());
        assertEquals(12.0, p.hours13w(), 1e-9);
        assertEquals(12.0 / 13, p.hoursPerWeek13w(), 1e-9);
        assertTrue(p.trendHoursPerWeek() > 0, "all hours in the last week: rising trend");
        assertEquals(0.5, p.shareSelfPicked(), 1e-9);
        assertEquals(0.5, p.shareManual(), 1e-9);
        assertEquals("Monday", p.topWeekday());
        assertEquals(List.of(0.5, 0.5, 0.0, 0.0, 0.0), p.weekdayShares());
        assertEquals(0.5, p.shareWithProject(), 1e-9);
        assertEquals(1.0, p.hoursByProject().get("PRJ"), 1e-9);
        assertNull(p.estimateRatioMedian(), "nothing finished");
        assertEquals(4, p.openTasks());
        assertEquals(58.0, p.openEstHours(), 1e-9);
        assertEquals(ANA.id().toString(), p.toMap().get("member_id"), "member ids are strings in the facts");
        assertEquals(20, p.toMap().size());
    }

    @Test
    void completionStatisticsUseTheWholeHistory() {
        LocalDateTime c = LocalDate.of(2026, 3, 2).atTime(9, 0);
        TaskRow fast = TestData.task("1", ANA.id(), c, 10).withStatus("DONE").withFinished(c.plusDays(1)).withDue(c.toLocalDate().plusDays(3)).withRemaining(0.0);
        TaskRow slow = TestData.task("2", ANA.id(), c, 10).withStatus("DONE").withFinished(c.plusDays(9)).withDue(c.toLocalDate().plusDays(3)).withType("Bug").withRemaining(0.0);
        ForecastData data = TestData.data(List.of(ANA), List.of(fast, slow), List.of(), List.of(
                TestData.log(fast.id(), ANA.id(), c.toLocalDate(), 5), TestData.log(slow.id(), ANA.id(), c.toLocalDate(), 20)));
        MemberPattern p = Patterns.of(ANA.id(), Lifecycle.derive(data), data, AS_OF);
        assertEquals(0, p.tasks13w());
        assertNull(p.shareManual());
        assertNull(p.topWeekday());
        assertEquals((0.5 + 2.0) / 2, p.estimateRatioMedian(), 1e-9);
        assertEquals((2 + 10) / 2.0, p.cycleDaysMedian(), 1e-9);
        assertEquals(2.0, p.cycleDaysByFamily().get("delivery"), 1e-9);
        assertEquals(10.0, p.cycleDaysByFamily().get("defect"), 1e-9);
        assertEquals((-2 + 6) / 2.0, p.latenessDaysMedian(), 1e-9);
        assertEquals(0.5, p.shareLate(), 1e-9);
        assertEquals(0, p.openTasks());
    }

    @Test
    void tableCoversEveryMemberOfTheSeed() {
        ForecastData data = SeededData.data();
        List<MemberPattern> table = Patterns.table(data.members(), Lifecycle.derive(data), data, SeededData.asOf());
        assertEquals(data.members().size(), table.size());
        for (int i = 1; i < table.size(); i++) {
            assertTrue(table.get(i - 1).memberId().toString().compareTo(table.get(i).memberId().toString()) < 0);
        }
        assertTrue(table.stream().anyMatch(p -> p.tasks13w() > 0));
    }
}
