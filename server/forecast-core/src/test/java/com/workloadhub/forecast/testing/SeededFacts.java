package com.workloadhub.forecast.testing;

import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.facts.FactsBuilder;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.run.Prepared;
import com.workloadhub.forecast.run.TeamOutcome;
import com.workloadhub.forecast.seed.AbsencePlanner;
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
            UUID team = data.teams().stream().filter(t -> !data.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).findFirst().orElseThrow();
            // windows pinned to 2, the default, so these facts stay put across window-count experiments elsewhere.
            ForecastRunner runner = new ForecastRunner(new CapacityRule(AbsencePlanner.BASE_HOURS), 2);
            Prepared prepared = runner.prepare(data, SeededData.asOf(), (phase, percent, message) -> { });
            TeamOutcome outcome = runner.forTeam(prepared, team);
            json = FactsBuilder.toJson(FactsBuilder.build(outcome, RUN_ID, LocalDateTime.of(2026, 9, 6, 12, 0)));
        }
        return json;
    }

    public static JsonNode facts() {
        return ExportFiles.mapper().readTree(factsJson());
    }
}
