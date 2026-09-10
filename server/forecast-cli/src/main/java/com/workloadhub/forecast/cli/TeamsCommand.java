package com.workloadhub.forecast.cli;

import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "teams", description = "List the teams a forecast can run for, with their counted member count.")
public class TeamsCommand implements Callable<Integer> {

    @Mixin DbOptions db;

    @Override
    public Integer call() throws Exception {
        try (Services s = Services.open(db.dataSource())) {
            System.out.printf("%-36s  %-40s  %s%n", "id", "name", "members");
            // Only teams with a counted member: a department/rollup team (e.g. a skill team leader's
            // grouping) has none and `run` always refuses it with TEAM_NOT_FOUND, so it does not belong
            // in a list meant to feed --team.
            for (Map<String, Object> r : s.jdbc().sql("SELECT id, name, members FROM (SELECT t.id, t.name,"
                    + " (SELECT COUNT(*) FROM team_members tm JOIN users u ON u.id = tm.user_id WHERE tm.team_id = t.id AND u.active = "
                    + s.dialect().boolLiteral(true) + " AND u.role IN ('MEMBER', 'TEAM_LEADER')) AS members FROM teams t) WHERE members > 0"
                    + " ORDER BY name").query().listOfRows()) {
                System.out.printf("%-36s  %-40s  %s%n", r.get("id"), r.get("name"), r.get("members"));
            }
            return 0;
        }
    }
}
