package com.workloadhub.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.model.XgboostArrival;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.testing.SeededData;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HarnessTest {

    static EvalResult result;
    static ForecastData data;

    @BeforeAll
    static void evaluate() {
        data = SeededData.data();
        CapacityRule rule = new CapacityRule(40);
        List<UUID> teams = data.teams().stream().filter(t -> !data.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).limit(2).toList();
        result = new Harness(new ForecastRunner(rule, true), rule)
                .evaluate(data, new EvalConfig(SeededData.asOf(), 2, List.of(), teams));
    }

    @Test
    void arrivalLevelHasEveryMetricForEveryModelHorizonAndOrigin() {
        assertEquals(2, result.origins().size());
        Set<String> metrics = Set.of("mae", "mase", "beats_naive", "coverage80", "wql", "seconds");
        for (String model : List.of(Backtest.FLOOR, XgboostArrival.NAME)) {
            for (int h : new int[] {1, 2}) {
                for (var origin : result.origins()) {
                    Set<String> got = result.scores().stream().filter(s -> s.model().equals(model) && s.horizon() == h && s.origin().equals(origin))
                            .map(ScoreRow::metric).collect(java.util.stream.Collectors.toSet());
                    assertEquals(metrics, got, model + " h" + h + " " + origin);
                }
            }
        }
        assertTrue(result.scores().stream().filter(s -> s.model().equals(Backtest.FLOOR) && s.metric().equals("mase")).allMatch(s -> Math.abs(s.value() - 1.0) < 1e-9));
        assertTrue(result.scores().stream().filter(s -> s.metric().equals("seconds") && s.horizon() == 2).allMatch(s -> Double.isNaN(s.value())));
        assertTrue(result.scores().stream().filter(s -> s.metric().equals("coverage80")).allMatch(s -> !Double.isNaN(s.value())), "two origins: leave-one-out bands exist");
    }

    @Test
    void demandLevelReplaysEveryOriginModelAndTeam() {
        assertFalse(result.demand().isEmpty());
        assertEquals(Set.of(Backtest.FLOOR, XgboostArrival.NAME), result.demand().stream().map(DemandRow::model).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, result.demand().stream().map(DemandRow::teamId).distinct().count());
        for (DemandRow r : result.demand()) {
            assertEquals(r.forecast(), ForecastRunner.round2(r.openHours() + r.newHours() + r.plannedHours()), 1e-9);
            assertTrue(r.truth() >= 0 && r.capacity() >= 0);
            assertTrue(r.windowStart().equals(r.origin().plusWeeks(1).plusDays(1)) || r.windowStart().equals(r.origin().plusWeeks(2).plusDays(1)),
                    "the replay runs on the Monday after the origin, so its windows start on Tuesdays");
            assertTrue(r.windowIndex() == 1 || r.windowIndex() == 2);
        }
        assertTrue(result.skipped().isEmpty());
        assertEquals(Truth.SOURCE, result.truthSource());
        assertTrue(result.elapsedSeconds() > 0);
    }

    @Test
    void unknownModelIsRejected() {
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> new Harness(new ForecastRunner(new CapacityRule(40), true), new CapacityRule(40))
                .evaluate(data, new EvalConfig(SeededData.asOf(), 1, List.of("gbm"), List.of()))).code());
    }
}
