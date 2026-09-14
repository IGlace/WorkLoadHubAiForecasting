package com.workloadhub.forecast.backtest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.Numbers;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.testing.SyntheticMatrix;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class BacktestTest {

    static final FeatureMatrix M = SyntheticMatrix.plantedSignal();
    static final LocalDate FIRST_WEEK = M.key(0).week();
    static final LocalDate LAST = M.key(M.rowCount() - 1).week();
    static final List<LocalDate> ALL_ORIGINS = Backtest.origins(LAST, FIRST_WEEK);
    static final LocalDate ORIGIN_A = ALL_ORIGINS.get(2);
    static final LocalDate ORIGIN_B = ALL_ORIGINS.get(3);

    @Test
    void originsStepBackTwoWeeksAndNeedThirteenWeeksOfHistory() {
        List<LocalDate> all = Backtest.origins(LAST, LAST.minusWeeks(69));
        assertEquals(6, all.size());
        assertEquals(LAST.minusWeeks(12), all.get(0));
        assertEquals(LAST.minusWeeks(2), all.get(5));
        List<LocalDate> few = Backtest.origins(LAST, LAST.minusWeeks(18));
        assertEquals(List.of(LAST.minusWeeks(4), LAST.minusWeeks(2)), few, "origins 6 and 4 weeks back have under 13 weeks");
        assertTrue(Backtest.origins(LAST, LAST.minusWeeks(10)).isEmpty());
    }

    @Test
    void maseIsTheErrorRatioAgainstTheNaiveAndNaNWhenTheNaiveIsPerfect() {
        assertEquals(0.5, Numbers.mase(new double[] {1, 2, 3}, new double[] {1.5, 2.5, 3.5}, new double[] {2, 3, 4}), 1e-9);
        assertTrue(Double.isNaN(Numbers.mase(new double[] {1, 2}, new double[] {1, 3}, new double[] {1, 2})));
    }

    @Test
    void oneScorePerOriginAndHorizon() {
        FeatureMatrix m = SyntheticMatrix.plantedSignal();
        List<LocalDate> origins = List.of(ORIGIN_A, ORIGIN_B);
        Backtest.Result r = Backtest.run(m, origins, new int[] {1, 2, 3});
        assertEquals(6, r.scores().size(), "two origins by three horizons, no model dimension");
        for (Backtest.Score s : r.scores()) {
            assertTrue(s.mae() >= 0.0, "MAE is in hours and never negative");
        }
        assertTrue(r.meanMae() >= 0.0);
        assertTrue(r.meanActualHours() > 0.0, "the mean of the backtest targets, for scale beside the MAE");
    }

    @Test
    void aHorizonWithoutItsTargetColumnFailsByName() {
        FeatureMatrix narrow = SyntheticMatrix.plantedSignal();  // built for horizons 1..3
        ForecastException e = assertThrows(ForecastException.class,
                () -> Backtest.run(narrow, List.of(ORIGIN_A), new int[] {1, 7}));
        assertTrue(e.getMessage().contains("target_h7"), "the message names the horizon that is missing");
    }

    @Test
    void intervalBoundsAreTheTenthAndNinetiethPercentilesWithLinearInterpolation() {
        assertArrayEquals(new double[] {0, 0}, Backtest.intervalBounds(new double[0]));
        assertArrayEquals(new double[] {1.9, 9.1}, Backtest.intervalBounds(new double[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}), 1e-9);
        assertArrayEquals(new double[] {5, 5}, Backtest.intervalBounds(new double[] {5}), 1e-9);
    }
}
