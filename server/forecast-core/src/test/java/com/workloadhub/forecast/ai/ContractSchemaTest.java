package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.workloadhub.forecast.data.ExportFiles;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ContractSchemaTest {

    static Set<String> keys(JsonNode object) {
        Set<String> out = new TreeSet<>();
        object.path("properties").propertyNames().forEach(out::add);
        return out;
    }

    @Test
    void theValidatorAndTheSchemaNameTheSameFields() {
        JsonNode schema = ExportFiles.mapper().readTree(Prompts.load().contractSchema());
        Map<String, Set<String>> fields = NarrativeContract.FIELDS;
        assertEquals(fields.get("narrative"), keys(schema));
        for (String def : new String[] {"member", "pattern", "likely_work", "team_risk", "move", "adjustment"}) {
            assertEquals(fields.get(def), keys(schema.path("$defs").path(def)), def);
        }
        Set<String> levels = new TreeSet<>();
        schema.path("$defs").path("level").path("enum").forEach(n -> levels.add(n.asText()));
        assertEquals(new TreeSet<>(NarrativeContract.LEVELS), levels);
        Set<String> kinds = new TreeSet<>();
        schema.path("$defs").path("pattern").path("properties").path("kind").path("enum").forEach(n -> kinds.add(n.asText()));
        assertEquals(new TreeSet<>(NarrativeContract.KINDS), kinds);
    }
}
