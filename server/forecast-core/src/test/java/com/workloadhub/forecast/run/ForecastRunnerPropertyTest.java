package com.workloadhub.forecast.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.DoubleRange;

class ForecastRunnerPropertyTest {

    @Property(tries = 500)
    void theBandHoldsItsInvariants(@ForAll @DoubleRange(min = 0, max = 200) double demand,
            @ForAll @DoubleRange(min = -20, max = 0) double q10,
            @ForAll @DoubleRange(min = 0, max = 20) double q90,
            @ForAll @DoubleRange(min = 0, max = 60) double capacity) {
        ForecastRunner.Band b = ForecastRunner.band(demand, q10, q90, capacity);
        assertTrue(b.demand() >= 0.0);
        assertTrue(b.low() >= 0.0);
        assertTrue(b.low() <= b.demand() + 1e-9);
        assertTrue(b.demand() <= b.high() + 1e-9);
        assertTrue(b.overload() >= 0.0);
        assertEquals(ForecastRunner.excess(b.demand(), capacity), b.overload(), 0.0, "the band's overload is excess(), not a second formula");
        // The formula rounds LAST, so "zero exactly when demand <= capacity" is false: a demand of
        // capacity + 0.004 rounds its overload to 0.0. The property has to carry the rounding.
        if (b.overload() > 0.0) {
            assertTrue(b.demand() > capacity + 0.004);
        }
    }

    // A Monday with no holidays, no absences and a horizon wide enough to hold the whole week: every
    // weekday of it is "wholly inside the horizon".
    static final LocalDate WEEK_WHOLLY_INSIDE_THE_HORIZON = LocalDate.of(2026, 9, 7);
    static final WorkingCalendar PLAIN_CALENDAR = WorkingCalendar.fromHolidays(List.of());
    static final List<LocalDate> NON_WORKING_DAYS = List.of(
            WEEK_WHOLLY_INSIDE_THE_HORIZON.plusDays(5), WEEK_WHOLLY_INSIDE_THE_HORIZON.plusDays(6));

    static Map<LocalDate, Double> split(double prediction, double[] shares, LocalDate monday) {
        return ForecastRunner.splitWeek(prediction, List.of(shares[0], shares[1], shares[2], shares[3], shares[4]),
                monday, PLAIN_CALENDAR, Set.of(), monday.minusDays(30), monday.plusDays(30));
    }

    /** Five-share vectors, mostly a normal spread of nonnegative shares, sometimes the all-zero fallback case. */
    @Provide
    Arbitrary<double[]> shareVectors() {
        Arbitrary<Double> share = Arbitraries.doubles().between(0, 1);
        Arbitrary<double[]> normal = Combinators.combine(share, share, share, share, share)
                .as((a, b, c, d, e) -> new double[] {a, b, c, d, e});
        Arbitrary<double[]> allZero = Arbitraries.just(new double[] {0.0, 0.0, 0.0, 0.0, 0.0});
        return Arbitraries.frequencyOf(Tuple.of(4, normal), Tuple.of(1, allZero));
    }

    @Property(tries = 300)
    void theSplitConservesTheWeekAndNeverGoesNegative(
            @ForAll @DoubleRange(min = 0, max = 80) double prediction,
            @ForAll("shareVectors") double[] shares) {
        Map<LocalDate, Double> days = split(prediction, shares, WEEK_WHOLLY_INSIDE_THE_HORIZON);
        double sum = 0;
        for (double h : days.values()) {
            assertTrue(h >= 0.0);
            sum += h;
        }
        assertEquals(prediction, sum, 1e-6, "a week wholly inside the horizon keeps all its hours");
        for (LocalDate d : NON_WORKING_DAYS) {
            assertEquals(0.0, days.getOrDefault(d, 0.0), 0.0, "a non-working day receives nothing");
        }
    }

    // Design 2026-09-13, section 8.2: backlog_excess_hrs and due_excess_hrs share the same "round last"
    // shape as overload (band()) above, so the same rounding trap applies to both.
    @Property(tries = 500)
    void thePressureFiguresAreNonNegativeAndRoundLast(
            @ForAll @DoubleRange(min = 0, max = 300) double openEst,
            @ForAll @DoubleRange(min = 0, max = 300) double cumulativeDemand,
            @ForAll @DoubleRange(min = 0, max = 200) double dueHours,
            @ForAll @DoubleRange(min = 0, max = 100) double capacity) {
        // The production helper itself: backlog_excess_hrs and due_excess_hrs are excess() on different pairs,
        // so this property fails if the formula in ForecastRunner changes, not only if the test's copy does.
        double backlog = ForecastRunner.excess(openEst, cumulativeDemand);
        double due = ForecastRunner.excess(dueHours, capacity);
        assertTrue(backlog >= 0.0);
        assertTrue(due >= 0.0);
        // The formula rounds LAST, so "positive exactly when A exceeds B" is false: an excess of 0.004
        // rounds to 0.0. The property has to carry the rounding, or jqwik finds the counterexample.
        if (backlog > 0.0) {
            assertTrue(openEst > cumulativeDemand + 0.004);
        }
        if (due > 0.0) {
            assertTrue(dueHours > capacity + 0.004);
        }
    }
}
