package com.workloadhub.forecast.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A member whose logged hours and assigned estimates deliberately diverge: they are assigned tasks
 * estimated at 20 hours a week and log 10. The target must follow the 10, not the 20.
 */
class RetargetTest {

    static final LocalDate ORIGIN = LocalDate.of(2026, 8, 24);
    static final MemberRow MEMBER = TestData.member("retarget", TestData.TEAM).withJoined(ORIGIN.minusWeeks(7));

    @Test
    void theTargetTracksLoggedHoursNotEstimates() {
        // One member, eight weeks (origin-7 .. origin): each week one task estimated at 20.0 assigned on
        // the Monday, and time_logs totalling 10.0 for that member in that week.
        List<TaskRow> tasks = new ArrayList<>();
        List<TimeLogRow> logs = new ArrayList<>();
        List<LocalDate> weeks = new ArrayList<>();
        for (int w = 7; w >= 0; w--) {
            LocalDate week = ORIGIN.minusWeeks(w);
            weeks.add(week);
            TaskRow task = TestData.task("w" + w, MEMBER.id(), week.atTime(9, 0), 20.0);
            tasks.add(task);
            logs.add(TestData.log(task.id(), MEMBER.id(), week, 10.0));
        }
        ForecastData data = TestData.data(List.of(MEMBER), tasks, List.of(), logs);
        Lifecycle lc = Lifecycle.derive(data);
        List<MemberRow> members = List.of(MEMBER);
        WorkingCalendar cal = WorkingCalendar.fromHolidays(List.of());
        CapacityRule rule = new CapacityRule(40);

        WeeklySeries s = WeeklySeries.build(lc, data, members, weeks);
        double[] logged = s.logged(MEMBER.id());
        double[] fresh = s.fresh(MEMBER.id());
        for (int i = 0; i < weeks.size(); i++) {
            assertEquals(10.0, logged[i], 1e-6, "week " + weeks.get(i));
            assertEquals(20.0, fresh[i], 1e-6, "the arrival series still reports the estimates");
        }

        FeatureMatrix m = new FeatureBuilder(data, lc, cal, rule).build(members, ORIGIN);
        int row = m.keys().indexOf(new MemberWeek(MEMBER.id(), ORIGIN.minusWeeks(4)));
        assertTrue(row >= 0);
        assertEquals(10.0, m.get(row, Features.target(1)), 1e-6,
                "target_h1 is the logged hours of the following week, not its estimates");
        assertEquals(20.0, m.get(row, "arrival_hrs_lag1"), 1e-6,
                "the estimates are still available, as an explanatory column");
    }
}
