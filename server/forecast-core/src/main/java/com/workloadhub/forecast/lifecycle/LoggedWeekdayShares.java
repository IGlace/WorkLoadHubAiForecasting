package com.workloadhub.forecast.lifecycle;

import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.data.ForecastData;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The share of a member's logged hours falling on each of Monday to Friday over the history window (design
 * 2026-09-13, section 5): when the member actually works, as opposed to {@code Patterns.weekdayShares}, which
 * counts when work arrives.
 *
 * <p>It is its own function, and lives here rather than inside {@code Patterns.of}, because the weekday split in
 * {@code ForecastRunner} needs this one series and nothing else of a member's pattern: building a whole
 * {@code MemberPattern} to read one field scanned every time log and computed four medians per member, and it
 * made {@code run} depend on {@code facts} while {@code facts} depends on {@code run}.
 */
public final class LoggedWeekdayShares {

    /** Weeks of history the shares are measured over, the window every member pattern uses. */
    public static final int WINDOW_WEEKS = 13;

    private LoggedWeekdayShares() {
    }

    /** The five shares, in Monday-to-Friday order, over the 13 weeks before the Monday of {@code asOf}; all zero without logs. */
    public static List<Double> of(UUID member, ForecastData data, LocalDate asOf) {
        LocalDate windowEnd = Weeks.mondayOf(asOf);
        return of(member, data, windowEnd.minusWeeks(WINDOW_WEEKS), windowEnd);
    }

    /** The five shares over {@code [windowStart, windowEnd)}. */
    public static List<Double> of(UUID member, ForecastData data, LocalDate windowStart, LocalDate windowEnd) {
        double[] hours = new double[5];
        double total = 0;
        for (var log : data.timeLogs()) {
            if (!log.userId().equals(member) || log.day().isBefore(windowStart) || !log.day().isBefore(windowEnd)) {
                continue;
            }
            DayOfWeek dow = log.day().getDayOfWeek();
            if (dow.getValue() <= 5) {
                hours[dow.getValue() - 1] += log.hours();
                total += log.hours();
            }
        }
        List<Double> shares = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            shares.add(total == 0 ? 0.0 : Math.round(hours[i] / total * 1000.0) / 1000.0);
        }
        return shares;
    }
}
