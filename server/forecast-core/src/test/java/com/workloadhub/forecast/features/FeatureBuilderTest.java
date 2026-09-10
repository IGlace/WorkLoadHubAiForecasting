package com.workloadhub.forecast.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeatureBuilderTest {

    static final WorkingCalendar CAL = WorkingCalendar.fromHolidays(List.of());
    static final CapacityRule RULE = new CapacityRule(40);
    static final LocalDate ORIGIN = LocalDate.of(2026, 8, 24);
    static final MemberRow ANA = TestData.member("ana", TestData.TEAM).withJoined(ORIGIN.minusWeeks(6));

    /** Ana receives 8 h in week −5, 4 h (backlog, lag 3 days) in week −4, 12 h in week −2, a Bug in week −1. */
    static ForecastData ana() {
        List<TaskRow> tasks = new ArrayList<>();
        LocalDateTime w5 = ORIGIN.minusWeeks(5).atTime(9, 0);
        tasks.add(TestData.task("1", ANA.id(), w5, 8));
        TaskRow lagged = TestData.task("2", ANA.id(), ORIGIN.minusWeeks(4).atTime(9, 0), 4);
        tasks.add(lagged);
        tasks.add(TestData.task("3", ANA.id(), ORIGIN.minusWeeks(2).atTime(9, 0), 12).withPriority("HIGH"));
        tasks.add(TestData.task("4", ANA.id(), ORIGIN.minusWeeks(1).atTime(9, 0), 2).withType("Bug").withReporter(ANA.id()));
        return TestData.data(List.of(ANA), tasks,
                List.of(TestData.assignee(lagged.id(), ANA.fullName(), lagged.createdDate().plusDays(3))), List.of());
    }

    static FeatureMatrix matrix(ForecastData data) {
        return new FeatureBuilder(data, Lifecycle.derive(data), CAL, RULE).build(data.members(), ORIGIN);
    }

    static int row(FeatureMatrix m, LocalDate week) {
        return m.keys().indexOf(new MemberWeek(ANA.id(), week));
    }

    @Test
    void rowsRunFromTheJoinWeekToTheOriginWithAllColumns() {
        FeatureMatrix m = matrix(ana());
        assertEquals(7, m.rowCount(), "join week −6 to origin inclusive");
        assertEquals(Features.allColumns(), m.columns());
        assertEquals(new MemberWeek(ANA.id(), ORIGIN.minusWeeks(6)), m.key(0));
        assertEquals(new MemberWeek(ANA.id(), ORIGIN), m.key(6));
    }

    @Test
    void lagsRollingStatsAndGapFollowTheFreshSeries() {
        FeatureMatrix m = matrix(ana());
        int origin = row(m, ORIGIN);
        assertEquals(0.0, m.get(origin, "lag1"));
        assertEquals(2.0, m.get(origin, "lag2"));
        assertEquals(12.0, m.get(origin, "lag3"));
        assertEquals(0.0, m.get(origin, "lag4"), "week −3 had nothing");
        assertTrue(Double.isNaN(m.get(origin, "lag8")), "before the member's first row");
        assertEquals(0.0, m.get(origin, "fresh_hours"));
        int w4 = row(m, ORIGIN.minusWeeks(4));
        assertEquals(0.0, m.get(w4, "fresh_hours"), "the 4 h task had a 3-day lag");
        assertEquals(4.0, m.get(w4, "est_hours"));
        assertEquals((0 + 8 + 0) / 3.0, m.get(w4, "roll_mean_4"), 1e-9, "three rows so far");
        assertEquals((0 + 8 + 0 + 0 + 12 + 2 + 0) / 7.0, m.get(origin, "roll_mean_8"), 1e-9);
        assertEquals(0.0, m.get(0, "roll_std_4"), "one value, no deviation");
        assertEquals(Math.sqrt(32.0), m.get(row(m, ORIGIN.minusWeeks(5)), "roll_std_4"), 1e-9, "sample std of {0, 8}");
        assertEquals(1.0, m.get(origin, "weeks_since_last_arrival"));
        assertEquals(52.0, m.get(0, "weeks_since_last_arrival"));
        assertEquals(1.0, m.get(w4, "weeks_since_last_arrival"), "week −5 was the last fresh arrival");
    }

    @Test
    void thirteenWeekSharesCountEveryArrivalFreshOrNot() {
        FeatureMatrix m = matrix(ana());
        int origin = row(m, ORIGIN);
        assertEquals(4.0, m.get(origin, "arrivals_13w"));
        assertEquals(0.25, m.get(origin, "share_defect_13w"));
        assertEquals(0.75, m.get(origin, "share_delivery_13w"));
        assertEquals(0.0, m.get(origin, "share_support_13w"));
        assertEquals(0.25, m.get(origin, "share_high_priority_13w"));
        assertEquals(0.25, m.get(origin, "share_self_picked_13w"));
        assertEquals(0.75, m.get(origin, "share_manual_13w"));
        assertEquals(0.0, m.get(origin, "share_project_13w"));
        assertEquals(1.0 / 3, m.get(0, "share_manual_13w"), 1e-9, "no arrivals yet: one third each");
        assertEquals(0.0, m.get(0, "share_defect_13w"));
        assertEquals(0.0, m.get(origin, "reopen_rate_13w"));
        assertEquals(1.0, m.get(origin, "estimate_ratio_13w"), "nothing finished: neutral ratio");
        assertTrue(Double.isNaN(m.get(origin, "cycle_days_13w")));
    }

    @Test
    void targetsAreTheFutureFreshHoursAndUnknownPastTheOrigin() {
        FeatureMatrix m = matrix(ana());
        int w3 = row(m, ORIGIN.minusWeeks(3));
        assertEquals(12.0, m.get(w3, "target_h1"));
        assertEquals(2.0, m.get(w3, "target_h2"));
        assertEquals(0.0, m.get(w3, "target_h3"));
        int w1 = row(m, ORIGIN.minusWeeks(1));
        assertEquals(0.0, m.get(w1, "target_h1"));
        assertTrue(Double.isNaN(m.get(w1, "target_h2")));
        assertTrue(Double.isNaN(m.get(row(m, ORIGIN), "target_h1")));
    }

    @Test
    void availabilityIdentityAndTenure() {
        FeatureMatrix m = matrix(ana());
        int origin = row(m, ORIGIN);
        assertEquals(5.0, m.get(origin, "working_days_h1"));
        assertEquals(0.0, m.get(origin, "absence_hrs_h2"));
        assertEquals(40.0, m.get(origin, "available_hrs_h3"));
        assertEquals(0.0, m.get(origin, "member_id"));
        assertEquals(ANA.id().toString(), m.decode("member_id", 0));
        assertEquals(TestData.TEAM.toString(), m.decode("team_id", m.get(origin, "team_id")));
        assertEquals("MEMBER", m.decode("role", m.get(origin, "role")));
        assertEquals("Engineer", m.decode("job_title", m.get(origin, "job_title")));
        assertEquals(6.0, m.get(origin, "tenure_weeks"));
        assertEquals(0.0, m.get(0, "tenure_weeks"));
        assertEquals(35.0, m.get(origin, "week_of_year"));
    }

    @Test
    void seededMatrixHasEveryFeatureColumnPopulated() {
        ForecastData data = SeededData.data();
        LocalDate origin = com.workloadhub.forecast.calendar.Weeks.lastCompleteWeek(SeededData.asOf());
        FeatureMatrix m = new FeatureBuilder(data, Lifecycle.derive(data), WorkingCalendar.fromHolidays(data.holidays()), RULE)
                .build(data.members(), origin);
        assertTrue(m.rowCount() > data.members().size() * 20, "rows " + m.rowCount());
        List<String> expected = new ArrayList<>(Features.featureColumns(1));
        expected.removeAll(List.of("logged_hours_lag1", "logged_hours_lag2", "logged_hours_lag3", "logged_hours_lag4", "open_tasks",
                "open_remaining_hrs", "overdue_open", "in_progress_tasks", "team_backlog_unassigned_hrs", "proj_active",
                "proj_planning", "proj_first_due_weeks", "due_hrs_h1"));
        assertEquals(expected, m.nonEmptyColumns(expected), "Task 6 fills the rest");
        for (int i = 1; i < m.rowCount(); i++) {
            assertTrue(m.key(i - 1).compareTo(m.key(i)) < 0, "rows sorted");
        }
        assertEquals(data.members().size(), m.codebooks().get("member_id").size());
    }

    @Test
    void throughputCountsOpenWorkAndLogsAtTheEndOfTheWeek() {
        LocalDate w2 = ORIGIN.minusWeeks(2);
        TaskRow done = TestData.task("1", ANA.id(), ORIGIN.minusWeeks(4).atTime(9, 0), 8)
                .withStatus("DONE").withFinished(w2.atTime(17, 0)).withRemaining(0.0);
        TaskRow running = TestData.task("2", ANA.id(), ORIGIN.minusWeeks(3).atTime(9, 0), 10)
                .withStatus("IN_PROGRESS").withStarted(w2.atTime(10, 0)).withDue(w2.plusDays(3)).withRemaining(1.0);
        TaskRow queued = TestData.task("3", ANA.id(), w2.atTime(9, 0), 6).withDue(ORIGIN.plusWeeks(1).plusDays(2));
        ForecastData data = TestData.data(List.of(ANA), List.of(done, running, queued), List.of(), List.of(
                TestData.log(done.id(), ANA.id(), ORIGIN.minusWeeks(3), 5),
                TestData.log(done.id(), ANA.id(), w2, 3),
                TestData.log(running.id(), ANA.id(), w2.plusDays(1), 4),
                TestData.log(running.id(), ANA.id(), ORIGIN, 2)));
        FeatureMatrix m = matrix(data);
        int atW2 = row(m, w2);
        assertEquals(7.0, m.get(atW2, "logged_hours_lag1"));
        assertEquals(5.0, m.get(atW2, "logged_hours_lag2"));
        assertEquals(0.0, m.get(atW2, "logged_hours_lag3"));
        assertEquals(2.0, m.get(atW2, "open_tasks"), "running and queued; done finished this week");
        assertEquals(6.0 + 6.0, m.get(atW2, "open_remaining_hrs"), "10 − 4 logged, plus 6 untouched");
        assertEquals(1.0, m.get(atW2, "overdue_open"), "running is due inside the week");
        assertEquals(1.0, m.get(atW2, "in_progress_tasks"));
        int origin = row(m, ORIGIN);
        assertEquals(10.0, m.get(origin, "open_remaining_hrs"), 1e-9, "running 10 − 6 logged by the origin, plus queued 6");
        assertEquals(6.0, m.get(origin, "due_hrs_h1"), "queued is due in the first forecast week");
        assertEquals(0.0, m.get(origin, "due_hrs_h2"));
        assertEquals(1.0, m.get(origin, "estimate_ratio_13w"), 1e-9, "8 h logged on an 8 h estimate");
        assertEquals(15.0, m.get(origin, "cycle_days_13w"), "assigned −4 w, finished −2 w: 14 days + 1");
    }

    @Test
    void teamColumnsSeeTheBacklogAndTheProjects() {
        MemberRow ben = TestData.member("ben", TestData.TEAM).withJoined(ORIGIN.minusWeeks(6));
        UUID active = TestData.id("proj-active");
        UUID planning = TestData.id("proj-planning");
        List<ProjectRow> projects = List.of(
                new ProjectRow(active, "ACT", "Active", "ACTIVE", TestData.TEAM),
                new ProjectRow(planning, "PLN", "Planning", "PLANNING", TestData.PARENT_TEAM));
        LocalDate w1 = ORIGIN.minusWeeks(1);
        TaskRow backlog = TestData.task("1", null, w1.atTime(9, 0), 9).withProject(active);
        TaskRow assignedLater = TestData.task("2", ANA.id(), ORIGIN.minusWeeks(3).atTime(9, 0), 5).withProject(active).withDue(ORIGIN.plusWeeks(2));
        TaskRow bens = TestData.task("3", ben.id(), w1.atTime(9, 0), 7).withProject(active).withDue(ORIGIN.plusDays(3));
        ForecastData data = TestData.data(List.of(ANA, ben), List.of(backlog, assignedLater, bens),
                List.of(TestData.assignee(assignedLater.id(), ANA.fullName(), w1.atTime(12, 0))), List.of()).withProjects(projects);
        FeatureMatrix m = matrix(data);
        int atW3 = row(m, ORIGIN.minusWeeks(3));
        assertEquals(5.0, m.get(atW3, "team_backlog_unassigned_hrs"), "task 2 waited in the backlog until week −1");
        assertEquals(1.0, m.get(atW3, "proj_active"));
        assertEquals(1.0, m.get(atW3, "proj_planning"), "the parent team's project counts");
        assertEquals(52.0, m.get(atW3, "proj_first_due_weeks"), "nothing open with a due date yet");
        int atW1 = row(m, w1);
        assertEquals(9.0, m.get(atW1, "team_backlog_unassigned_hrs"));
        assertEquals(10.0 / 7, m.get(atW1, "proj_first_due_weeks"), 1e-9, "Ben's task is due 10 days after week −1");
        assertEquals(m.get(atW1, "team_backlog_unassigned_hrs"), m.get(m.keys().indexOf(new MemberWeek(ben.id(), w1)), "team_backlog_unassigned_hrs"));
    }
}
