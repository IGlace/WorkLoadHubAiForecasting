package com.workloadhub.forecast.cli;

import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "teams", description = "List the teams with their counted member count.")
public class TeamsCommand implements Callable<Integer> {

    @Mixin DbOptions db;

    @Override
    public Integer call() throws Exception {
        try (Services s = Services.open(db.dataSource())) {
            System.out.printf("%-36s  %-40s  %s%n", "id", "name", "members");
            for (Map<String, Object> r : s.jdbc().sql("SELECT t.id, t.name, (SELECT COUNT(*) FROM team_members tm JOIN users u ON u.id = tm.user_id"
                    + " WHERE tm.team_id = t.id AND u.active = " + s.dialect().boolLiteral(true) + " AND u.role IN ('MEMBER', 'TEAM_LEADER')) AS members"
                    + " FROM teams t ORDER BY t.name").query().listOfRows()) {
                System.out.printf("%-36s  %-40s  %s%n", r.get("id"), r.get("name"), r.get("members"));
            }
            return 0;
        }
    }
}
