package com.workloadhub.forecast.tools.seed;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.tools.export.ExportEnvelope;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import net.jqwik.api.Property;

/**
 * A seed must be as busy in its last weeks as in its middle, because a run is made on its end date: the
 * forecast a showcase or an experiment looks at covers the weekdays after the seed's last day, and a
 * population that has wound down by then forecasts near zero for everybody, which is arithmetically right
 * and useless to look at.
 *
 * Measured per week, not as a mean over the whole window: WorkFamilyPropertyTest already averages over all
 * 52 weeks, which is exactly why it could not see the taper that a live run on 2026-09-14 found. Fixture
 * size: 120 users, 52 weeks, seed 7 — the showcase's own demo population.
 */
class SeedTailPropertyTest {

    private static final SeedConfig CFG = new SeedConfig(52, LocalDate.of(2026, 9, 6), 7, true, 120);

    /** Weeks at each end that a partial first week or the horizon's own edge makes uneven. */
    private static final int EDGE = 2;

    /** The tail this property is about. */
    private static final int TAIL = 8;

    @Property(tries = 1)
    void theLastWeeksCarryAsMuchWorkAsTheMiddle() {
        ExportEnvelope env = SeedGenerator.generate(null, CFG);
        Map<LocalDate, Double> hoursPerWeek = new TreeMap<>();
        for (LocalDate monday : CFG.mondays()) {
            hoursPerWeek.put(monday, 0.0);
        }
        for (var log : env.rows("time_logs")) {
            LocalDate week = SeedConfig.mondayOf(LocalDate.parse((String) log.get("log_date")));
            hoursPerWeek.computeIfPresent(week, (k, v) -> v + (Double) log.get("hours"));
        }

        var weeks = new java.util.ArrayList<>(hoursPerWeek.entrySet());
        int n = weeks.size();

        // 1. No week inside the window is empty.
        Map<LocalDate, Double> empty = new HashMap<>();
        for (int i = EDGE; i < n - EDGE; i++) {
            if (weeks.get(i).getValue() <= 0.0) {
                empty.put(weeks.get(i).getKey(), weeks.get(i).getValue());
            }
        }
        assertTrue(empty.isEmpty(), "weeks inside the window with no logged hours at all: " + empty);

        // 2. The last eight weeks carry at least 60% of the middle's weekly mean.
        double middle = mean(weeks, EDGE, n - TAIL);
        double tail = mean(weeks, n - TAIL, n - EDGE);
        assertTrue(tail >= 0.60 * middle,
                "the seed tapers: last " + TAIL + " weeks mean " + Math.round(tail) + " h/week against a middle mean of "
                        + Math.round(middle) + " h/week (ratio " + String.format("%.2f", tail / middle) + ")");
    }

    private static double mean(java.util.List<Map.Entry<LocalDate, Double>> weeks, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += weeks.get(i).getValue();
        }
        return sum / (to - from);
    }
}
