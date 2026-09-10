package com.workloadhub.forecast.eval;

import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.features.MemberDay;
import com.workloadhub.forecast.features.MemberWeek;
import java.util.SortedMap;
import java.util.TreeMap;

/** What the demand forecast is measured against: the hours people logged, per member and week. */
public final class Truth {

    public static final String SOURCE = "time logs";

    private Truth() {
    }

    public static SortedMap<MemberWeek, Double> realisedHours(ForecastData data) {
        SortedMap<MemberWeek, Double> out = new TreeMap<>();
        for (TimeLogRow l : data.timeLogs()) {
            out.merge(new MemberWeek(l.userId(), Weeks.mondayOf(l.day())), l.hours(), Double::sum);
        }
        out.replaceAll((k, v) -> Math.round(v * 1e6) / 1e6);
        return out;
    }

    /** The hours people logged, per member and day. */
    public static SortedMap<MemberDay, Double> realisedHoursByDay(ForecastData data) {
        SortedMap<MemberDay, Double> out = new TreeMap<>();
        for (TimeLogRow l : data.timeLogs()) {
            out.merge(new MemberDay(l.userId(), l.day()), l.hours(), Double::sum);
        }
        out.replaceAll((k, v) -> Math.round(v * 1e6) / 1e6);
        return out;
    }
}
