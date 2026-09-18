package com.workloadhub.forecastweb.api;

import com.workloadhub.forecast.data.ExportFiles;
import tools.jackson.databind.JsonNode;

/** Parses the JSON strings the module stores (facts, narrative, verification, usage) so the response carries objects. */
final class Json {

    private Json() {
    }

    static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return ExportFiles.mapper().readTree(json);
    }
}
