package com.workloadhub.forecast.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;

class ForecastRunnerPropertyTest {

    @Property
    void bandsBracketDemandAndNeverCutPlacedWork(@ForAll @DoubleRange(min = 0, max = 60) double open, @ForAll @DoubleRange(min = 0, max = 60) double fresh,
            @ForAll @DoubleRange(min = 0, max = 30) double planned, @ForAll @DoubleRange(min = -40, max = 0) double q10,
            @ForAll @DoubleRange(min = 0, max = 40) double q90, @ForAll @DoubleRange(min = 0.5, max = 2.5) double ratio,
            @ForAll @DoubleRange(min = 0, max = 50) double capacity) {
        ForecastRunner.Band b = ForecastRunner.band(open, fresh, planned, q10, q90, ratio, capacity);
        assertEquals(b.demand(), ForecastRunner.round2(ForecastRunner.round2(open) + ForecastRunner.round2(fresh) + ForecastRunner.round2(planned)), 1e-9);
        assertTrue(b.low() <= b.demand() + 1e-9 && b.demand() <= b.high() + 1e-9);
        assertTrue(b.low() + 1e-9 >= ForecastRunner.round2(open) + ForecastRunner.round2(planned));
        assertEquals(b.overload(), ForecastRunner.round2(Math.max(0, b.demand() - capacity)), 1e-9);
        assertTrue(b.overload() >= 0);
    }
}
