package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.AccuracyScore;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.eval.AccuracyReport;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "accuracy", description = "Compare the forecasts made before each past weekday with the logged hours: scores per team, member and lead.")
public class AccuracyCommand implements Callable<Integer> {

    /** The default range is this many days ending yesterday, both ends included. */
    static final int DEFAULT_DAYS = 20;
    static final int KEY_WIDTH = 36;

    @Mixin DbOptions db;

    @Option(names = "--team", required = true, description = "Team name or id")
    String team;

    @Option(names = "--from", description = "First day, ISO (default: the last 20 days ending yesterday)")
    String from;

    @Option(names = "--to", description = "Last day, ISO (default: yesterday)")
    String to;

    @Option(names = "--out", description = "Write accuracy.csv and summary.md into this folder")
    Path out;

    @Option(names = "--json", description = "Print the result as JSON")
    boolean json;

    @Override
    public Integer call() throws Exception {
        try (Services s = Services.open(db.dataSource())) {
            UUID teamId;
            LocalDate first;
            LocalDate last;
            try {
                teamId = TeamArg.resolve(s.jdbc(), s.dialect(), team);
                LocalDate yesterday = LocalDate.now().minusDays(1);
                last = to == null ? yesterday : LocalDate.parse(to);
                first = from == null ? last.minusDays(DEFAULT_DAYS - 1) : LocalDate.parse(from);
            } catch (IllegalArgumentException | DateTimeParseException e) {
                System.err.println("error: " + e.getMessage());
                return 2;
            }
            AccuracyResult result;
            try {
                result = s.service().accuracy(teamId, first, last);
            } catch (ForecastException e) {
                System.err.println("error: " + e.code() + ": " + e.getMessage());
                return "INVALID_REQUEST".equals(e.code()) || "TEAM_NOT_FOUND".equals(e.code()) ? 2 : 1;
            }
            if (out != null) {
                AccuracyReport.write(result, out);
                System.err.println("wrote " + out.resolve("accuracy.csv") + " and " + out.resolve("summary.md"));
            }
            if (json) {
                System.out.println(RunCommand.JSON_MAPPER.writeValueAsString(result));
                return 0;
            }
            Map<UUID, String> names = Names.of(s.jdbc());
            System.out.println("accuracy of team " + team + ", " + result.from() + " to " + result.to() + ", " + result.current().size() + " member-days");
            System.out.println("mase: daily hours against the same weekday a week earlier (not the run's weekly MASE); lead rows pool every finished run");
            System.out.printf("%-7s %-36s %5s %7s %7s %7s %7s %10s %10s%n", "scope", "key", "n", "mae", "bias", "mase", "mase_n", "over_prec", "over_rec");
            for (AccuracyScore sc : result.scores()) {
                String key = column(sc.scope().equals("member") ? names.getOrDefault(UUID.fromString(sc.key()), sc.key()) : sc.key());
                System.out.printf("%-7s %-36s %5d %7s %7s %7s %7d %10s %10s%n", sc.scope(), key, sc.n(), cell(sc.mae()), cell(sc.bias()), cell(sc.mase()),
                        sc.maseN(), cell(sc.overloadPrecision()), cell(sc.overloadRecall()));
            }
            return 0;
        }
    }

    /** The key column is 36 characters wide: a longer name is cut so the columns stay in line. */
    static String column(String key) {
        return key.length() <= KEY_WIDTH ? key : key.substring(0, KEY_WIDTH - 1) + "\u2026";
    }

    static String cell(double v) {
        return Double.isNaN(v) ? "-" : String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
