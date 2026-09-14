package com.workloadhub.forecast.run;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
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
        // The formula rounds LAST, so "zero exactly when demand <= capacity" is false: a demand of
        // capacity + 0.004 rounds its overload to 0.0. The property has to carry the rounding.
        if (b.overload() > 0.0) {
            assertTrue(b.demand() > capacity + 0.004);
        }
    }
}
