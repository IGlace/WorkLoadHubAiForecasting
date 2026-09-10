package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.ForecastRepository;
import com.workloadhub.forecast.eval.EvalConfig;
import com.workloadhub.forecast.eval.EvalResult;
import com.workloadhub.forecast.eval.Harness;
import com.workloadhub.forecast.eval.Report;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "eval", description = "Score every model at every origin (arrival level) and replay whole runs per team (demand level); writes scores.csv, demand.csv and summary.md.")
public class EvalCommand implements Callable<Integer> {

    @Mixin DbOptions db;

    @Option(names = "--as-of", description = "As-of date, ISO (default: the latest task creation date)")
    String asOf;

    @Option(names = "--origins", defaultValue = "6", description = "Backtest origins, two weeks apart (default: ${DEFAULT-VALUE})")
    int origins;

    @Option(names = "--models", description = "Comma-separated model names (default: all)")
    String models;

    @Option(names = "--teams", description = "Comma-separated team names or ids (default: all)")
    String teams;

    @Option(names = "--out", description = "Output folder (default: ./eval/<as-of>)")
    Path out;

    @Override
    public Integer call() throws Exception {
        try (Services s = Services.open(db.dataSource())) {
            ForecastData data = new ForecastRepository(s.jdbc(), s.dialect()).loadAll();
            LocalDate date;
            List<UUID> teamIds = new ArrayList<>();
            List<String> modelNames = models == null ? List.of() : Arrays.stream(models.split(",")).map(String::trim).filter(m -> !m.isEmpty()).toList();
            try {
                date = asOf != null ? LocalDate.parse(asOf) : data.tasks().stream().map(t -> t.createdDate().toLocalDate()).max(LocalDate::compareTo).orElse(LocalDate.now());
                if (teams != null) {
                    for (String t : teams.split(",")) {
                        teamIds.add(TeamArg.resolve(s.jdbc(), s.dialect(), t.trim()));
                    }
                }
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
                System.err.println("error: " + e.getMessage());
                return 2;
            }
            Path outDir = out != null ? out : Path.of("eval", date.toString());
            System.out.println("Evaluating as of " + date + " with " + origins + " origins, models " + (modelNames.isEmpty() ? "all" : modelNames) + ", teams "
                    + (teamIds.isEmpty() ? "all" : teamIds.size()));
            EvalResult result;
            try {
                result = new Harness(s.runner(), new CapacityRule(40)).evaluate(data, new EvalConfig(date, origins, modelNames, teamIds));
            } catch (ForecastException e) {
                System.err.println("error: " + e.code() + ": " + e.getMessage());
                return "INVALID_REQUEST".equals(e.code()) ? 2 : 1;
            }
            Map<String, String> fingerprint = new LinkedHashMap<>();
            fingerprint.put("members", String.valueOf(data.members().size()));
            fingerprint.put("teams", String.valueOf(data.teams().size()));
            fingerprint.put("tasks", String.valueOf(data.tasks().size()));
            fingerprint.put("time_logs", String.valueOf(data.timeLogs().size()));
            fingerprint.put("first_created", data.tasks().stream().map(t -> t.createdDate().toLocalDate()).min(LocalDate::compareTo).map(Object::toString).orElse("none"));
            fingerprint.put("last_created", data.tasks().stream().map(t -> t.createdDate().toLocalDate()).max(LocalDate::compareTo).map(Object::toString).orElse("none"));
            Map<String, String> versions = new LinkedHashMap<>();
            versions.put("java", System.getProperty("java.version"));
            versions.put("xgboost4j", "3.4.0");
            versions.put("forecast-cli", "0.1.0");
            Report.write(result, new EvalConfig(date, origins, modelNames, teamIds), fingerprint, versions, outDir);
            System.out.println(Report.levelA(result));
            System.out.println("Wrote " + outDir.toAbsolutePath());
            return 0;
        }
    }
}
