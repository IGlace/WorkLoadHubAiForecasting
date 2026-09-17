package com.workloadhub.forecast.eval;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.features.MemberDay;
import java.util.SortedMap;
import java.util.TreeMap;

/** What the forecast is measured against: the hours people logged, per member and day. */
public final class Truth {

    private Truth() {
    }

    /** The hours people logged, per member and day, rounded to six decimals. */
    public static SortedMap<MemberDay, Double> realisedHoursByDay(ForecastData data) {
        SortedMap<MemberDay, Double> out = new TreeMap<>();
        for (TimeLogRow l : data.timeLogs()) {
            out.merge(new MemberDay(l.userId(), l.day()), l.hours(), Double::sum);
        }
        out.replaceAll((k, v) -> Math.round(v * 1e6) / 1e6);
        return out;
    }
}
