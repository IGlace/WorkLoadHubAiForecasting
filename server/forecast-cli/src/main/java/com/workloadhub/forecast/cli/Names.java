package com.workloadhub.forecast.cli;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The display name of every user, read once for a command's table. A user with no name is shown by id. */
final class Names {

    private Names() {
    }

    static Map<UUID, String> of(JdbcClient jdbc) {
        Map<UUID, String> names = new HashMap<>();
        for (Map<String, Object> row : jdbc.sql("SELECT id, full_name FROM users").query().listOfRows()) {
            UUID id = UUID.fromString(row.get("id").toString());
            Object name = row.get("full_name");
            names.put(id, name == null ? id.toString() : name.toString());
        }
        return names;
    }
}
