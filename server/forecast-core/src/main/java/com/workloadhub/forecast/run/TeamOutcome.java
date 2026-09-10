package com.workloadhub.forecast.run;

import com.workloadhub.forecast.api.MemberDayForecast;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.features.MemberDay;
import com.workloadhub.forecast.planned.PlannedWork;
import java.util.List;
import java.util.SortedMap;
import java.util.UUID;

/** The per-team half of a run: per-day placements, planned work, and the window and day tables. */
public record TeamOutcome(
        Prepared prepared,
        UUID teamId,
        List<MemberRow> members,
        boolean plannedWorkEnabled,
        SortedMap<MemberDay, Double> openHours,
        SortedMap<MemberDay, Double> newHours,
        PlannedWork.Allocation planned,
        List<MemberWindowForecast> memberWindows,
        List<MemberDayForecast> memberDays) {
}
