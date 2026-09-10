package com.workloadhub.forecast.facts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Deterministic statistics about one member, handed to the narrative as facts. */
public record MemberPattern(UUID memberId, int tasks13w, double hours13w, double hoursPerWeek13w, double trendHoursPerWeek, Double shareManual,
        Double shareSelfPicked, Double shareProject, String topWeekday, List<Double> weekdayShares, Double estimateRatioMedian, Double cycleDaysMedian,
        Map<String, Double> cycleDaysByFamily, Double latenessDaysMedian, Double shareLate, Double shareWithProject, Map<String, Double> hoursByProject,
        int openTasks, double openEstHours, int overdueOpen) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("member_id", memberId.toString());
        m.put("tasks_13w", tasks13w);
        m.put("hours_13w", hours13w);
        m.put("hours_per_week_13w", hoursPerWeek13w);
        m.put("trend_hours_per_week", trendHoursPerWeek);
        m.put("share_manual", shareManual);
        m.put("share_self_picked", shareSelfPicked);
        m.put("share_project", shareProject);
        m.put("top_weekday", topWeekday);
        m.put("weekday_shares", weekdayShares);
        m.put("estimate_ratio_median", estimateRatioMedian);
        m.put("cycle_days_median", cycleDaysMedian);
        m.put("cycle_days_by_type", cycleDaysByFamily);
        m.put("lateness_days_median", latenessDaysMedian);
        m.put("share_late", shareLate);
        m.put("share_with_project", shareWithProject);
        m.put("hours_by_project", hoursByProject);
        m.put("open_tasks", openTasks);
        m.put("open_est_hours", openEstHours);
        m.put("overdue_open", overdueOpen);
        return m;
    }
}
