package com.workloadhub.forecastweb.api;

import tools.jackson.databind.JsonNode;

/** Parses the JSON strings the module stores (facts, narrative, verification, usage) so the response carries objects. */
final class Json {

    private Json() {
    }

    static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        // Fully qualified: this class's own name shadows the module's, which cannot therefore be imported.
        return com.workloadhub.forecast.Json.mapper().readTree(json);
    }
}
