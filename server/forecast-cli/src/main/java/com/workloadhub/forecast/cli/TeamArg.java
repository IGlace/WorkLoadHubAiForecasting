package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.store.Dialect;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** --team accepts a UUID or a team name; --user accepts a UUID, a full name or an email. */
final class TeamArg {

    private TeamArg() {
    }

    static UUID resolve(JdbcClient jdbc, Dialect dialect, String nameOrId) {
        UUID asUuid = tryUuid(nameOrId);
        if (asUuid != null) {
            boolean exists = !jdbc.sql("SELECT id FROM teams WHERE id = " + dialect.placeholder("uuid"))
                    .param(asUuid.toString()).query().listOfRows().isEmpty();
            if (!exists) {
                throw new IllegalArgumentException("no team with id " + asUuid);
            }
            return asUuid;
        }
        List<Map<String, Object>> rows = jdbc.sql("SELECT id, name FROM teams WHERE LOWER(name) = LOWER(?) ORDER BY name").param(nameOrId.trim())
                .query().listOfRows();
        if (rows.size() == 1) {
            return UUID.fromString(rows.get(0).get("id").toString());
        }
        List<String> names = jdbc.sql("SELECT name FROM teams ORDER BY name").query().listOfRows().stream().map(r -> r.get("name").toString()).toList();
        throw new IllegalArgumentException(rows.isEmpty() ? "no team named '" + nameOrId + "'; teams: " + names
                : rows.size() + " teams named '" + nameOrId + "', use the id");
    }

    static UUID resolveUser(JdbcClient jdbc, Dialect dialect, String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return null;
        }
        UUID asUuid = tryUuid(nameOrId);
        if (asUuid != null) {
            return asUuid;
        }
        List<Map<String, Object>> rows = jdbc.sql("SELECT id FROM users WHERE LOWER(full_name) = LOWER(?) OR LOWER(email) = LOWER(?)")
                .param(nameOrId.trim()).param(nameOrId.trim()).query().listOfRows();
        if (rows.size() != 1) {
            throw new IllegalArgumentException(rows.isEmpty() ? "no user '" + nameOrId + "'" : "several users match '" + nameOrId + "', use the id");
        }
        return UUID.fromString(rows.get(0).get("id").toString());
    }

    private static UUID tryUuid(String nameOrId) {
        try {
            return UUID.fromString(nameOrId.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
