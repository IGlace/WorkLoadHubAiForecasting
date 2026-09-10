package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.api.RunSummary;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "runs", description = "List the runs of a team, newest first.")
public class RunsCommand implements Callable<Integer> {

    @Mixin DbOptions db;

    @Option(names = "--team", required = true, description = "Team name or id")
    String team;

    @Option(names = "--limit", defaultValue = "20", description = "Rows to print (default: ${DEFAULT-VALUE})")
    int limit;

    @Override
    public Integer call() throws Exception {
        try (Services s = Services.open(db.dataSource())) {
            UUID teamId;
            try {
                teamId = TeamArg.resolve(s.jdbc(), s.dialect(), team);
            } catch (IllegalArgumentException e) {
                System.err.println("error: " + e.getMessage());
                return 2;
            }
            System.out.printf("%-36s %-10s %-8s %-15s %6s %-19s %s%n", "id", "as_of", "status", "champion", "mase", "created_at", "error");
            for (RunSummary r : s.service().listRuns(teamId, limit)) {
                System.out.printf("%-36s %-10s %-8s %-15s %6s %-19s %s%n", r.id(), r.asOf(), r.status(), r.championModel() == null ? "-" : r.championModel(),
                        r.championMase() == null ? "-" : String.format("%.2f", r.championMase()), r.createdAt(), r.error() == null ? "" : r.error());
            }
            return 0;
        }
    }
}
