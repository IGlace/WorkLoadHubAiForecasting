package com.workloadhub.forecast.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.Features;
import com.workloadhub.forecast.testing.SyntheticMatrix;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class XgboostArrivalTest {

    static final FeatureMatrix M = SyntheticMatrix.arrivals(12, 70, 7);
    static final LocalDate ORIGIN = M.key(M.rowCount() - 1).week();
    static final FeatureMatrix TRAIN = M.filter(k -> !k.week().isAfter(ORIGIN.minusWeeks(6)));
    static final FeatureMatrix TEST = M.filter(k -> k.week().equals(ORIGIN.minusWeeks(3)));

    static double mae(double[] y, double[] p) {
        double s = 0;
        for (int i = 0; i < y.length; i++) {
            s += Math.abs(y[i] - p[i]);
        }
        return s / y.length;
    }

    @Test
    void beatsTheFloorOnAPlantedSeasonalSignalAndNeverPredictsBelowZero() {
        try (XgboostArrival xgb = new XgboostArrival()) {
            xgb.fit(TRAIN, Features.HORIZONS);
            SeasonalNaive naive = new SeasonalNaive().fit(TRAIN, Features.HORIZONS);
            for (int h : Features.HORIZONS) {
                double[] y = TEST.target(h);
                double[] p = xgb.predict(TEST, h);
                assertEquals(TEST.rowCount(), p.length);
                for (double v : p) {
                    assertTrue(v >= 0.0);
                }
                double model = mae(y, p);
                double floor = mae(y, naive.predict(TEST, h));
                assertTrue(model < floor, "h" + h + ": xgboost " + model + " vs floor " + floor);
            }
            assertFalse(xgb.columnsUsed(1).contains("open_tasks"), "all-NaN columns are dropped");
            assertTrue(xgb.columnsUsed(1).contains("lag1"));
            assertEquals("xgboost", xgb.name());
        }
    }

    @Test
    void isDeterministicAcrossFits() {
        double[] first;
        try (XgboostArrival a = new XgboostArrival()) {
            first = a.fit(TRAIN, new int[] {1}).predict(TEST, 1);
        }
        try (XgboostArrival b = new XgboostArrival()) {
            assertArrayEquals(first, b.fit(TRAIN, new int[] {1}).predict(TEST, 1), 1e-6);
        }
    }

    @Test
    void numericFallbackTrainsAndPredictsToo() {
        try (XgboostArrival plain = new XgboostArrival(false)) {
            double[] p = plain.fit(TRAIN, new int[] {2}).predict(TEST, 2);
            assertEquals(TEST.rowCount(), p.length);
            assertTrue(mae(TEST.target(2), p) < mae(TEST.target(2), new SeasonalNaive().fit(TRAIN, Features.HORIZONS).predict(TEST, 2)));
        }
    }

    @Test
    void failsClearlyWithoutTrainingRowsOrAnUnfittedHorizon() {
        FeatureMatrix empty = TRAIN.filter(k -> false);
        try (XgboostArrival xgb = new XgboostArrival()) {
            assertThrows(ModelUnavailable.class, () -> xgb.fit(empty, new int[] {1}));
            xgb.fit(TRAIN, new int[] {1});
            assertThrows(IllegalStateException.class, () -> xgb.predict(TEST, 3));
        }
        assertTrue(List.of(true, false).contains(XgboostArrival.categoricalSupported()));
    }
}
