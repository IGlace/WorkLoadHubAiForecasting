package com.workloadhub.forecast.backtest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.Features;
import com.workloadhub.forecast.model.ArrivalModel;
import com.workloadhub.forecast.model.ModelUnavailable;
import com.workloadhub.forecast.model.SeasonalNaive;
import com.workloadhub.forecast.model.XgboostArrival;
import com.workloadhub.forecast.testing.SyntheticMatrix;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class BacktestTest {

    static final FeatureMatrix M = SyntheticMatrix.arrivals(8, 70, 3);
    static final LocalDate LAST = M.key(M.rowCount() - 1).week();

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
        assertEquals(0.5, Backtest.mase(new double[] {1, 2, 3}, new double[] {1.5, 2.5, 3.5}, new double[] {2, 3, 4}), 1e-9);
        assertTrue(Double.isNaN(Backtest.mase(new double[] {1, 2}, new double[] {1, 3}, new double[] {1, 2})));
    }

    @Test
    void scoresEveryModelAtEveryOriginAndHorizonAndPoolsResiduals() {
        Map<String, Supplier<ArrivalModel>> factories = new LinkedHashMap<>();
        factories.put(Backtest.FLOOR, SeasonalNaive::new);
        factories.put(XgboostArrival.NAME, XgboostArrival::new);
        List<LocalDate> origins = Backtest.origins(LAST, M.key(0).week()).subList(2, 5);   // targets at h3 stay inside the fixture
        Backtest.Result r = Backtest.run(M, factories, origins, Features.HORIZONS);
        assertEquals(2 * 3 * 3, r.scores().size());
        for (Backtest.Score s : r.scores()) {
            if (s.model().equals(Backtest.FLOOR)) {
                assertEquals(1.0, s.mase(), 1e-9, "the floor scores 1 against itself");
            }
            assertTrue(s.mae() >= 0);
        }
        assertTrue(r.meanMase(XgboostArrival.NAME) < 1.0, "planted signal: " + r.meanMase(XgboostArrival.NAME));
        assertEquals(8 * 3, r.residuals().get(XgboostArrival.NAME).get(1).length, "8 members × 3 origins");
        assertTrue(r.unavailable().isEmpty());
        assertTrue(r.secondsPerModel().get(XgboostArrival.NAME) > 0);
        Backtest.Champion c = Backtest.selectChampion(r.scores());
        assertEquals(XgboostArrival.NAME, c.model());
        assertEquals(r.meanMase(XgboostArrival.NAME), c.meanMase(), 1e-12);
    }

    @Test
    void aModelUnavailableAtAnyOriginLosesEverything() {
        ArrivalModel flaky = new ArrivalModel() {
            int fits;

            public String name() {
                return "flaky";
            }

            public ArrivalModel fit(FeatureMatrix train, int[] horizons) {
                if (++fits == 2) {
                    throw new ModelUnavailable("gone at the second origin");
                }
                return this;
            }

            public double[] predict(FeatureMatrix rows, int h) {
                return rows.column("roll_mean_4");
            }
        };
        Map<String, Supplier<ArrivalModel>> factories = new LinkedHashMap<>();
        factories.put(Backtest.FLOOR, SeasonalNaive::new);
        factories.put("flaky", () -> flaky);
        List<LocalDate> origins = Backtest.origins(LAST, M.key(0).week()).subList(3, 6);
        Backtest.Result r = Backtest.run(M, factories, origins, new int[] {1});
        assertEquals("gone at the second origin", r.unavailable().get("flaky"));
        assertTrue(r.scores().stream().noneMatch(s -> s.model().equals("flaky")));
        assertFalse(r.residuals().containsKey("flaky"));
        assertFalse(r.secondsPerModel().containsKey("flaky"));
        assertEquals(Backtest.FLOOR, Backtest.selectChampion(r.scores()).model());
    }

    @Test
    void championFallsBackToTheFloorWhenNothingBeatsIt() {
        List<Backtest.Score> weak = List.of(
                new Backtest.Score(Backtest.FLOOR, LAST, 1, 1, 1.0),
                new Backtest.Score("x", LAST, 1, 2, 1.2),
                new Backtest.Score("x", LAST, 2, 2, Double.NaN));
        Backtest.Champion c = Backtest.selectChampion(weak);
        assertEquals(Backtest.FLOOR, c.model());
        assertEquals(1.0, c.meanMase());
        assertEquals(Backtest.FLOOR, Backtest.selectChampion(List.of()).model());
        assertTrue(Double.isNaN(Backtest.selectChampion(List.of()).meanMase()));
        assertEquals("x", Backtest.selectChampion(List.of(new Backtest.Score("x", LAST, 1, 1, 0.8))).model());
    }

    @Test
    void intervalBoundsAreTheTenthAndNinetiethPercentilesWithLinearInterpolation() {
        assertArrayEquals(new double[] {0, 0}, Backtest.intervalBounds(new double[0]));
        assertArrayEquals(new double[] {1.9, 9.1}, Backtest.intervalBounds(new double[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}), 1e-9);
        assertArrayEquals(new double[] {5, 5}, Backtest.intervalBounds(new double[] {5}), 1e-9);
    }
}
