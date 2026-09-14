package com.workloadhub.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.model.XgboostHours;
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
        result = new Harness(new ForecastRunner(rule, 2), rule)
                .evaluate(data, new EvalConfig(SeededData.asOf(), 2, List.of(), teams));
    }

    @Test
    void arrivalLevelHasEveryMetricForEveryHorizonAndOrigin() {
        assertEquals(2, result.origins().size());
        Set<String> metrics = Set.of("mae", "coverage80", "wql", "seconds");
        for (int h : new int[] {1, 2}) {
            for (var origin : result.origins()) {
                Set<String> got = result.scores().stream().filter(s -> s.model().equals(XgboostHours.NAME) && s.horizon() == h && s.origin().equals(origin))
                        .map(ScoreRow::metric).collect(java.util.stream.Collectors.toSet());
                assertEquals(metrics, got, "h" + h + " " + origin);
            }
        }
        assertTrue(result.scores().stream().filter(s -> s.metric().equals("coverage80")).allMatch(s -> !Double.isNaN(s.value())), "two origins: leave-one-out bands exist");
    }

    @Test
    void demandLevelReplaysEveryOriginAndTeam() {
        assertFalse(result.demand().isEmpty());
        assertEquals(Set.of(XgboostHours.NAME), result.demand().stream().map(DemandRow::model).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, result.demand().stream().map(DemandRow::teamId).distinct().count());
        for (DemandRow r : result.demand()) {
            assertTrue(r.forecast() >= 0);
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
    void theResultCarriesTheConfigItRanAndAFingerprintOfTheData() {
        assertEquals(SeededData.asOf(), result.resolved().asOf());
        assertEquals(2, result.resolved().origins());
        assertEquals(String.valueOf(data.members().size()), result.fingerprint().get("members"));
        assertEquals(String.valueOf(data.teams().size()), result.fingerprint().get("teams"));
        assertEquals(String.valueOf(data.tasks().size()), result.fingerprint().get("tasks"));
        assertEquals(String.valueOf(data.timeLogs().size()), result.fingerprint().get("time_logs"));
        assertEquals(lastCreated(), result.fingerprint().get("last_created"));
        assertTrue(result.fingerprint().get("first_created").compareTo(lastCreated()) <= 0);
    }

    /** The caller may not have loaded the data, so only the harness can say when history ends. */
    @Test
    void aNullAsOfBecomesTheLatestTaskCreationDate() {
        CapacityRule rule = new CapacityRule(40);
        EvalResult defaulted = new Harness(new ForecastRunner(rule, 2), rule)
                .evaluate(data, new EvalConfig(null, 1, List.of(XgboostHours.NAME), List.of()));
        assertEquals(lastCreated(), defaulted.resolved().asOf().toString());
        assertEquals(1, defaulted.resolved().origins());
        assertEquals(List.of(XgboostHours.NAME), defaulted.resolved().models());
    }

    private static String lastCreated() {
        return data.tasks().stream().map(t -> t.createdDate().toLocalDate()).max(java.time.LocalDate::compareTo).orElseThrow().toString();
    }

    @Test
    void unknownModelIsRejected() {
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> new Harness(new ForecastRunner(new CapacityRule(40), 2), new CapacityRule(40))
                .evaluate(data, new EvalConfig(SeededData.asOf(), 1, List.of("gbm"), List.of()))).code());
    }
}
