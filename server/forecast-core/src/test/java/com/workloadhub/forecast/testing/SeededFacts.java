package com.workloadhub.forecast.testing;

import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.Json;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.facts.FactsBuilder;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.run.Prepared;
import com.workloadhub.forecast.run.TeamOutcome;
import java.time.LocalDateTime;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** The facts of one real forecast for the first team with members of {@link SeededData}. */
public final class SeededFacts {

    public static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static String json;

    private SeededFacts() {
    }

    public static synchronized String factsJson() {
        if (json == null) {
            ForecastData data = SeededData.data();
            UUID team = SeededData.anyTeam(data);
            // windows pinned to 2, the default, so these facts stay put across window-count experiments elsewhere.
            // 44 h, the seed's own week and the module's default
            ForecastRunner runner = new ForecastRunner(new CapacityRule(44.0), 2);
            Prepared prepared = runner.prepare(data, SeededData.asOf(), (phase, percent, message) -> { });
            TeamOutcome outcome = runner.forTeam(prepared, team);
            json = FactsBuilder.toJson(FactsBuilder.build(outcome, RUN_ID, LocalDateTime.of(2026, 9, 6, 12, 0)));
        }
        return json;
    }

    public static JsonNode facts() {
        return Json.mapper().readTree(factsJson());
    }
}
