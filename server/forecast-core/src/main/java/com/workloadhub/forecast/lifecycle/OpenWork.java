package com.workloadhub.forecast.lifecycle;

import java.time.LocalDate;
import java.util.List;

/**
 * The two sums over a member's open tasks that the run and the facts must agree on (design 2026-09-13,
 * section 8.2): the queue a forecast of logged hours cannot show.
 *
 * <p>They live here, beside the {@link TaskFacts} they read, rather than on {@code ForecastRunner}: {@code run}
 * and {@code facts} both need them and both already depend on {@code lifecycle}, so this is the one place that
 * closes no package cycle. {@code Numbers} was moved to the root package for exactly the same reason
 * (design 2026-09-09, section 16).
 */
public final class OpenWork {

    private OpenWork() {
    }

    /**
     * Remaining hours of a member's open tasks. The one definition of the sum, so {@code Patterns.open_est_hours}
     * and {@code ForecastRunner}'s {@code backlog_excess_hrs} can never drift apart by summing it twice.
     */
    public static double openEstHours(List<TaskFacts> open) {
        double sum = 0;
        for (TaskFacts f : open) {
            sum += f.remaining() != null ? f.remaining() : f.estimate();
        }
        return sum;
    }

    /**
     * Remaining hours of a member's open tasks falling due inside {@code [start, end]}: the one definition
     * {@code FactsBuilder}'s {@code due_hours} and {@code ForecastRunner}'s {@code due_excess_hrs} both read, so
     * a narrative comparing the two can never contradict itself.
     */
    public static double dueHours(List<TaskFacts> open, LocalDate start, LocalDate end) {
        double sum = 0;
        for (TaskFacts f : open) {
            LocalDate d = f.task().dueDate();
            if (d != null && !d.isBefore(start) && !d.isAfter(end)) {
                sum += f.remaining() != null ? f.remaining() : f.estimate();
            }
        }
        return sum;
    }
}
