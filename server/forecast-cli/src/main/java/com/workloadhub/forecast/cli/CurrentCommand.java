package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.data.ExportFiles;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "current", description = "Print the team's current forecast per member and day: the latest run that covered each day.")
public class CurrentCommand implements Callable<Integer> {

    static final int DEFAULT_DAYS = 20;

    @Mixin DbOptions db;

    @Option(names = "--team", required = true, description = "Team name or id")
    String team;

    @Option(names = "--from", description = "First day, ISO (default: today)")
    String from;

    @Option(names = "--to", description = "Last day, ISO (default: today + 20 days)")
    String to;

    @Option(names = "--json", description = "Print the rows as JSON")
    boolean json;

    @Override
    public Integer call() throws Exception {
        try (Services s = Services.open(db.dataSource())) {
            UUID teamId;
            LocalDate first;
            LocalDate last;
            try {
                teamId = TeamArg.resolve(s.jdbc(), s.dialect(), team);
                first = from == null ? LocalDate.now() : LocalDate.parse(from);
                last = to == null ? LocalDate.now().plusDays(DEFAULT_DAYS) : LocalDate.parse(to);
            } catch (IllegalArgumentException | DateTimeParseException e) {
                System.err.println("error: " + e.getMessage());
                return 2;
            }
            List<CurrentDayForecast> rows;
            try {
                rows = s.service().currentForecast(teamId, first, last);
            } catch (ForecastException e) {
                System.err.println("error: " + e.code() + ": " + e.getMessage());
                return "INVALID_REQUEST".equals(e.code()) || "TEAM_NOT_FOUND".equals(e.code()) ? 2 : 1;
            }
            if (json) {
                System.out.println(ExportFiles.mapper().writeValueAsString(rows));
                return 0;
            }
            Map<UUID, String> names = Names.of(s.jdbc());
            System.out.printf("%-28s %-10s %-36s %7s %7s %7s %7s %8s %8s%n", "member", "day", "run", "open", "new", "planned", "demand", "capacity", "overload");
            for (CurrentDayForecast c : rows) {
                System.out.printf("%-28s %-10s %-36s %7.1f %7.1f %7.1f %7.1f %8.1f %8.1f%n", names.getOrDefault(c.userId(), c.userId().toString()), c.day(),
                        c.runId(), c.openHrs(), c.newHrs(), c.plannedHrs(), c.demandHrs(), c.capacityHrs(), c.overloadHrs());
            }
            return 0;
        }
    }
}
