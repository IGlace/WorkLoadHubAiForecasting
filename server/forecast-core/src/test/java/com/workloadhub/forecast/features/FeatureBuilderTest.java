package com.workloadhub.forecast.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeatureBuilderTest {

    static final WorkingCalendar CAL = WorkingCalendar.fromHolidays(List.of());
    static final CapacityRule RULE = new CapacityRule(40);
    static final int WINDOWS = 2;
    static final LocalDate ORIGIN = LocalDate.of(2026, 8, 24);
    static final MemberRow ANA = TestData.member("ana", TestData.TEAM).withJoined(ORIGIN.minusWeeks(6));

    /**
     * Ana receives 8 h in week −5, 4 h (backlog, lag 3 days) in week −4, 12 h in week −2, a Bug in week −1.
     * She logs a deliberately different number of hours each of those weeks (5, 1, 3, 9) plus 6 h in week
     * −3, when nothing was assigned to her at all, so a test that reads the logged series cannot pass by
     * accident if it silently reads the arrival series instead (task 4's retarget).
     */
    static ForecastData ana() {
        List<TaskRow> tasks = new ArrayList<>();
        LocalDateTime w5 = ORIGIN.minusWeeks(5).atTime(9, 0);
        TaskRow first = TestData.task("1", ANA.id(), w5, 8);
        tasks.add(first);
        TaskRow lagged = TestData.task("2", ANA.id(), ORIGIN.minusWeeks(4).atTime(9, 0), 4);
        tasks.add(lagged);
        TaskRow third = TestData.task("3", ANA.id(), ORIGIN.minusWeeks(2).atTime(9, 0), 12).withPriority("HIGH");
        tasks.add(third);
        TaskRow fourth = TestData.task("4", ANA.id(), ORIGIN.minusWeeks(1).atTime(9, 0), 2).withType("Bug").withReporter(ANA.id());
        tasks.add(fourth);
        List<TimeLogRow> logs = List.of(
                TestData.log(first.id(), ANA.id(), ORIGIN.minusWeeks(5), 5),
                TestData.log(lagged.id(), ANA.id(), ORIGIN.minusWeeks(4), 1),
                TestData.log(first.id(), ANA.id(), ORIGIN.minusWeeks(3), 6),
                TestData.log(third.id(), ANA.id(), ORIGIN.minusWeeks(2), 3),
                TestData.log(fourth.id(), ANA.id(), ORIGIN.minusWeeks(1), 9));
        return TestData.data(List.of(ANA), tasks,
                List.of(TestData.assignee(lagged.id(), ANA.fullName(), lagged.createdDate().plusDays(3)),
                        TestData.assignedBy(first.id(), TestData.id("lead"), ANA.fullName(), w5),
                        TestData.assignedBy(third.id(), TestData.id("lead"), ANA.fullName(), ORIGIN.minusWeeks(2).atTime(9, 0)),
                        TestData.assignedBy(fourth.id(), ANA.id(), ANA.fullName(), ORIGIN.minusWeeks(1).atTime(9, 0))), logs);
    }

    static FeatureMatrix matrix(ForecastData data) {
        return new FeatureBuilder(data, Lifecycle.derive(data), CAL, RULE, WINDOWS).build(data.members(), ORIGIN);
    }

    static int row(FeatureMatrix m, LocalDate week) {
        return m.keys().indexOf(new MemberWeek(ANA.id(), week));
    }

    private static FeatureMatrix seeded;

    /** The seeded matrix once per JVM: the two tests that read it only read it. */
    static synchronized FeatureMatrix seededMatrix() {
        if (seeded == null) {
            ForecastData data = SeededData.data();
            LocalDate origin = com.workloadhub.forecast.calendar.Weeks.lastCompleteWeek(SeededData.asOf());
            seeded = new FeatureBuilder(data, Lifecycle.derive(data), WorkingCalendar.fromHolidays(data.holidays()), RULE, WINDOWS)
                    .build(data.members(), origin);
        }
        return seeded;
    }

    @Test
    void rowsRunFromTheJoinWeekToTheOriginWithAllColumns() {
        FeatureMatrix m = matrix(ana());
        assertEquals(7, m.rowCount(), "join week −6 to origin inclusive");
        assertEquals(Features.allColumns(WINDOWS), m.columns());
        assertEquals(new MemberWeek(ANA.id(), ORIGIN.minusWeeks(6)), m.key(0));
        assertEquals(new MemberWeek(ANA.id(), ORIGIN), m.key(6));
    }

    @Test
    void lagsAndRollingStatsFollowTheLoggedSeriesWhileGapAndArrivalLagsFollowArrivals() {
        FeatureMatrix m = matrix(ana());
        int origin = row(m, ORIGIN);
        // lag1..lag4 are the target's own history: the logged series, not the estimates.
        assertEquals(0.0, m.get(origin, "lag1"), "nothing logged yet this week");
        assertEquals(9.0, m.get(origin, "lag2"));
        assertEquals(3.0, m.get(origin, "lag3"));
        assertEquals(6.0, m.get(origin, "lag4"), "week −3 had no assignment but 6 h were logged");
        assertTrue(Double.isNaN(m.get(origin, "lag8")), "before the member's first row");
        assertEquals(0.0, m.get(origin, "arrival_hrs_lag1"), "nothing assigned this week");
        int w4 = row(m, ORIGIN.minusWeeks(4));
        assertEquals(0.0, m.get(w4, "arrival_hrs_lag1"), "the 4 h task had a 3-day lag, so it was not fresh");
        assertEquals((0 + 5 + 1) / 3.0, m.get(w4, "roll_mean_4"), 1e-9, "three rows so far, of the logged series");
        assertEquals((0 + 5 + 1 + 6 + 3 + 9 + 0) / 7.0, m.get(origin, "roll_mean_8"), 1e-9);
        assertEquals(0.0, m.get(0, "roll_std_4"), "one value, no deviation");
        assertEquals(Math.sqrt(12.5), m.get(row(m, ORIGIN.minusWeeks(5)), "roll_std_4"), 1e-9, "sample std of {0, 5}, the logged hours");
        // weeks_since_last_arrival is genuinely about arrivals, so it keeps reading the fresh series (unaffected by the logs above).
        assertEquals(1.0, m.get(origin, "weeks_since_last_arrival"));
        assertTrue(Double.isNaN(m.get(0, "weeks_since_last_arrival")), "never: blank, not 52");
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
        assertEquals(0.75, m.get(origin, "share_assigned_13w"));
        assertTrue(Double.isNaN(m.get(0, "share_assigned_13w")), "no arrivals yet: blank, not one third");
        assertTrue(Double.isNaN(m.get(0, "share_defect_13w")));
        assertTrue(Double.isNaN(m.get(origin, "reopen_rate_13w")), "nothing finished: blank");
        assertTrue(Double.isNaN(m.get(origin, "estimate_ratio_13w")), "nothing finished: blank, not 1.0");
        assertTrue(Double.isNaN(m.get(origin, "cycle_days_13w")));
    }

    @Test
    void reopenRateReflectsReopenedFinishesInTheWindow() {
        // Two tasks finish inside the 13-week window ending at the origin (windowStats counts a finish by
        // its finish date, from origin−12w to origin+6d); one of the two was reopened, so the ratio is 0.5,
        // not the blank of the sibling test above (nothing finished) and not 0.0 or 1.0 by accident.
        TaskRow steady = TestData.task("1", ANA.id(), ORIGIN.minusWeeks(4).atTime(9, 0), 8)
                .withStatus("DONE").withFinished(ORIGIN.minusWeeks(2).atTime(17, 0));
        TaskRow flaky = TestData.task("2", ANA.id(), ORIGIN.minusWeeks(3).atTime(9, 0), 5)
                .withStatus("DONE").withFinished(ORIGIN.minusWeeks(1).atTime(17, 0)).withReopened(true);
        FeatureMatrix m = matrix(TestData.data(List.of(ANA), List.of(steady, flaky), List.of(), List.of()));
        int origin = row(m, ORIGIN);
        assertEquals(0.5, m.get(origin, "reopen_rate_13w"), "one of the two tasks finished in the window (flaky) was reopened");
    }

    @Test
    void targetsAreTheFutureLoggedHoursAndUnknownPastTheOrigin() {
        FeatureMatrix m = matrix(ana());
        int w3 = row(m, ORIGIN.minusWeeks(3));
        assertEquals(3.0, m.get(w3, "target_h1"), "the logged hours of week −2, not its estimate of 12");
        assertEquals(9.0, m.get(w3, "target_h2"), "the logged hours of week −1, not its estimate of 2");
        assertEquals(0.0, m.get(w3, "target_h3"), "nothing logged at the origin week");
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
        FeatureMatrix m = seededMatrix();
        assertTrue(m.rowCount() > data.members().size() * 20, "rows " + m.rowCount());
        List<String> expected = new ArrayList<>(Features.featureColumns(1));
        expected.removeAll(List.of("arrival_hrs_lag1", "arrival_hrs_lag2", "arrival_hrs_lag3", "arrival_hrs_lag4", "open_tasks",
                "open_remaining_hrs", "overdue_open", "in_progress_tasks", "team_backlog_unassigned_hrs", "proj_active",
                "proj_planning", "due_hrs_h1"));
        assertEquals(expected, m.nonEmptyColumns(expected), "Task 6 fills the rest");
        for (int i = 1; i < m.rowCount(); i++) {
            assertTrue(m.key(i - 1).compareTo(m.key(i)) < 0, "rows sorted");
        }
        // Not just "not all NaN": planned_hrs_h1 was identically zero on every seeded row, because the seed
        // planned every task for its own assignment week and the column needs planned_week == w + h.
        long plannedRows = 0;
        for (int i = 0; i < m.rowCount(); i++) {
            if (m.get(i, "planned_hrs_h1") > 0) {
                plannedRows++;
            }
        }
        assertTrue(plannedRows > 0, "planned_hrs_h1 is zero on every seeded row: the column is never exercised");
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
        // lag1..lag3 are the target's own history now, so they carry what logged_hours_lag1..3 used to.
        assertEquals(7.0, m.get(atW2, "lag1"));
        assertEquals(5.0, m.get(atW2, "lag2"));
        assertEquals(0.0, m.get(atW2, "lag3"));
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
    void targetHEqualsLoggedHoursHWeeksLaterWhereBothExist() {
        ForecastData data = SeededData.data();
        FeatureMatrix m = seededMatrix();
        Map<MemberWeek, Integer> rowOf = new HashMap<>();
        for (int i = 0; i < m.rowCount(); i++) {
            rowOf.put(m.key(i), i);
        }
        int checked = 0;
        for (int h : Features.horizons(WINDOWS)) {
            double[] target = m.target(h);
            for (int i = 0; i < m.rowCount(); i++) {
                if (Double.isNaN(target[i])) {
                    continue;
                }
                MemberWeek future = new MemberWeek(m.key(i).member(), m.key(i).week().plusWeeks(h));
                Integer j = rowOf.get(future);
                if (j == null) {
                    continue;
                }
                // lag1 is the row's OWN week (see Features.LAGS), i.e. that future row's own logged hours.
                assertEquals(m.get(j, "lag1"), target[i], 1e-9, () -> future + " at h" + h);
                checked++;
            }
        }
        assertTrue(checked > 0, "the seed gives at least one row with both a target and its future logged hours (lag1)");
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
        int atW1 = row(m, w1);
        assertEquals(9.0, m.get(atW1, "team_backlog_unassigned_hrs"));
        assertEquals(m.get(atW1, "team_backlog_unassigned_hrs"), m.get(m.keys().indexOf(new MemberWeek(ben.id(), w1)), "team_backlog_unassigned_hrs"));
    }

    @Test
    void plannedHoursAreTheOpenTasksEstimatesPlannedForTheTargetWeek() {
        // planned is assigned week −1 and targets week +1: at the origin (h1 target = +1w) it is the only
        // open task whose plan matches, so planned_hrs_h1 = 12.0, its own estimate.
        TaskRow planned = TestData.task("1", ANA.id(), ORIGIN.minusWeeks(1).atTime(9, 0), 12).withPlannedWeek(ORIGIN.plusWeeks(1));
        // done also targets +2w, same as midweek below, but finishes before the origin: if the finished
        // guard were missing, planned_hrs_h2 at the origin would wrongly be 3.0 + 5.0 = 8.0.
        TaskRow done = TestData.task("2", ANA.id(), ORIGIN.minusWeeks(2).atTime(9, 0), 5).withPlannedWeek(ORIGIN.plusWeeks(2))
                .withStatus("DONE").withFinished(ORIGIN.minusWeeks(1).atTime(9, 0));
        // midweek targets a Wednesday of +2w, normalised to its Monday, and stays open through the origin.
        TaskRow midweek = TestData.task("3", ANA.id(), ORIGIN.minusWeeks(2).atTime(9, 0), 3).withPlannedWeek(ORIGIN.plusWeeks(2).plusDays(2));
        FeatureMatrix m = matrix(TestData.data(List.of(ANA), List.of(planned, done, midweek), List.of(), List.of()));
        int origin = row(m, ORIGIN);
        assertEquals(12.0, m.get(origin, "planned_hrs_h1"), "planned's own estimate, the only task open and planned for week +1");
        assertEquals(3.0, m.get(origin, "planned_hrs_h2"), "midweek only (a Wednesday normalised to its Monday): done's matching plan is excluded because it is finished");
        assertEquals(0.0, m.get(origin, "planned_hrs_h3"));
        // From week −2, h3 targets +1w — exactly the week `planned` is planned for — but `planned` is not
        // assigned until week −1, a week after this row's end, so the openAtEndOf guard must reject it: this
        // 0.0 holds only because of that guard, not because no plan matches the target (it does).
        int atW2 = row(m, ORIGIN.minusWeeks(2));
        assertEquals(0.0, m.get(atW2, "planned_hrs_h3"), "planned's target matches h3 here, but it is not yet assigned as of week −2");
    }
}
