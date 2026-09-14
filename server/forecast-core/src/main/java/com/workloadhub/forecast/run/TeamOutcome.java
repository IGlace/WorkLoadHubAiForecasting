package com.workloadhub.forecast.run;

import com.workloadhub.forecast.api.MemberDayForecast;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.data.rows.MemberRow;
import java.util.List;
import java.util.UUID;

/** The per-team half of a run: the window and day tables. */
public record TeamOutcome(
        Prepared prepared,
        UUID teamId,
        List<MemberRow> members,
        List<MemberWindowForecast> memberWindows,
        List<MemberDayForecast> memberDays) {
}
