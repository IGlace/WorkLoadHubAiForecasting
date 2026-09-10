package com.workloadhub.forecast.run;

import com.workloadhub.forecast.api.MemberWeekForecast;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.planned.PlannedWork;
import java.util.List;
import java.util.SortedMap;
import java.util.UUID;

/** The per-team half of a run: placements, planned work and the member-week table. */
public record TeamOutcome(
        Prepared prepared,
        UUID teamId,
        List<MemberRow> members,
        boolean plannedWorkEnabled,
        SortedMap<MemberWeek, Double> openHours,
        SortedMap<MemberWeek, Double> newHours,
        PlannedWork.Allocation planned,
        List<MemberWeekForecast> memberWeeks) {
}
