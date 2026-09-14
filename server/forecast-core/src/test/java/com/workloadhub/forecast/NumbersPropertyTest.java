package com.workloadhub.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.Size;

class NumbersPropertyTest {

    @Property
    void round2IsIdempotent(@ForAll @DoubleRange(min = -1e6, max = 1e6) double v) {
        assertEquals(Numbers.round2(v), Numbers.round2(Numbers.round2(v)), 0.0);
    }

    @Property
    void round2MovesByLessThanHalfACent(@ForAll @DoubleRange(min = -1e6, max = 1e6) double v) {
        assertTrue(Math.abs(Numbers.round2(v) - v) <= 0.005 + 1e-9);
    }

    @Property
    void maeIsNonNegative(@ForAll @Size(min = 1, max = 50) double[] y, @ForAll @Size(min = 1, max = 50) double[] yHat) {
        int n = Math.min(y.length, yHat.length);
        double[] a = java.util.Arrays.copyOf(y, n);
        double[] b = java.util.Arrays.copyOf(yHat, n);
        double m = Numbers.mae(a, b);
        assertTrue(m >= 0.0 || Double.isNaN(m));
    }

    @Property
    void maeIsZeroExactlyWhenTheForecastIsTheTruth(@ForAll @Size(min = 1, max = 50) double[] y) {
        assertEquals(0.0, Numbers.mae(y, y.clone()), 0.0);
    }
}
