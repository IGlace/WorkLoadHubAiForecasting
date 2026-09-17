package com.workloadhub.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.Numbers;
import org.junit.jupiter.api.Test;

class MetricsTest {

    @Test
    void pointMetrics() {
        assertEquals(1.0, Numbers.mae(new double[] {1, 2, 3}, new double[] {2, 3, 4}), 1e-9);
        assertEquals(1.0, Metrics.bias(new double[] {1, 2, 3}, new double[] {2, 3, 4}), 1e-9);
        assertTrue(Double.isNaN(Numbers.mae(new double[0], new double[0])));
    }

    @Test
    void overloadPrecisionAndRecall() {
        assertArrayEquals(new double[] {0.5, 1.0}, Metrics.overloadPrecisionRecall(new boolean[] {true, false, false}, new boolean[] {true, true, false}), 1e-9);
        double[] none = Metrics.overloadPrecisionRecall(new boolean[] {false}, new boolean[] {false});
        assertTrue(Double.isNaN(none[0]) && Double.isNaN(none[1]));
    }
}
