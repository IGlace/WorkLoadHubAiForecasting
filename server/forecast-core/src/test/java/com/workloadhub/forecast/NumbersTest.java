package com.workloadhub.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NumbersTest {

    @Test
    void roundsToTwoDecimals() {
        assertEquals(1.23, Numbers.round2(1.234), 1e-9);
        assertEquals(1.24, Numbers.round2(1.235), 1e-9);
        assertEquals(-1.23, Numbers.round2(-1.234), 1e-9);
        assertEquals(0.0, Numbers.round2(0.0), 1e-9);
    }

    @Test
    void meanAbsoluteError() {
        assertEquals(0.0, Numbers.mae(new double[] {1, 2, 3}, new double[] {1, 2, 3}), 1e-9);
        assertEquals(2.0, Numbers.mae(new double[] {1, 2, 3}, new double[] {3, 4, 5}), 1e-9);
    }

    @Test
    void maeOfNothingIsNotANumber() {
        assertTrue(Double.isNaN(Numbers.mae(new double[] {}, new double[] {})));
    }

    @Test
    void meanAbsoluteScaledError() {
        // forecast off by 1 each step, naive off by 2 each step: 3/6.
        assertEquals(0.5, Numbers.mase(new double[] {1, 2, 3}, new double[] {2, 3, 4}, new double[] {3, 4, 5}), 1e-9);
    }

    @Test
    void maseAgainstAPerfectNaiveIsNotANumber() {
        assertTrue(Double.isNaN(Numbers.mase(new double[] {1, 2}, new double[] {5, 5}, new double[] {1, 2})));
    }
}
