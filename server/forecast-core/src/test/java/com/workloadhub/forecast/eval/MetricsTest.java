package com.workloadhub.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricsTest {

    @Test
    void pointMetrics() {
        assertEquals(1.0, Metrics.mae(new double[] {1, 2, 3}, new double[] {2, 3, 4}), 1e-9);
        assertEquals(1.0, Metrics.bias(new double[] {1, 2, 3}, new double[] {2, 3, 4}), 1e-9);
        assertTrue(Double.isNaN(Metrics.mae(new double[0], new double[0])));
        assertEquals(2.0 / 3, Metrics.coverage(new double[] {1, 2, 3}, new double[] {0, 0, 4}, new double[] {2, 2, 5}), 1e-9);
    }

    @Test
    void weightedQuantileLossMatchesTheDefinition() {
        double[] y = {10, 20};
        double v = Metrics.weightedQuantileLoss(y, Map.of(0.5, new double[] {12, 18}));
        assertEquals(2.0 * ((0.5 * 2 + 0.5 * 2) / 2) / 15.0, v, 1e-9, "pinball at the median is half the absolute error");
        assertTrue(Double.isNaN(Metrics.weightedQuantileLoss(new double[] {0, 0}, Map.of(0.5, new double[] {0, 0}))));
    }

    @Test
    void overloadPrecisionAndRecall() {
        assertArrayEquals(new double[] {0.5, 1.0}, Metrics.overloadPrecisionRecall(new boolean[] {true, false, false}, new boolean[] {true, true, false}), 1e-9);
        double[] none = Metrics.overloadPrecisionRecall(new boolean[] {false}, new boolean[] {false});
        assertTrue(Double.isNaN(none[0]) && Double.isNaN(none[1]));
    }
}
