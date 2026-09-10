package com.workloadhub.forecast.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** scores.csv, demand.csv and summary.md in the columns of the Python harness. */
public final class Report {

    static final List<String> LEVEL_A = List.of("mae", "mase", "beats_naive", "coverage80", "wql", "seconds");
    static final String LEVEL_A_CAPTION = "`coverage80` and `wql` are scored on the leave-one-origin-out residual band the run would show, so every model"
            + " is measured on the interval a user actually sees. `seconds` is fit plus predict for the whole backtest divided by the origins that model"
            + " scored; it is reported on the first horizon row and left empty on the others.";
    static final List<String> ASSUMPTIONS = List.of(
            "Truth is the hours logged in time_logs, summed per member and Monday week; work still in progress at export time has few logs, so the"
                    + " most recent origins are deflated and every model looks high there.",
            "The replay is a Monday-morning evaluation: each origin is replayed as of the Monday after it, so a task assigned on the first forecast"
                    + " Monday counts as open work rather than as an arrival.",
            "Only horizons 1 and 2 are scored, the two weeks a run forecasts.",
            "A single-origin run reports NaN interval coverage and NaN weighted quantile loss: the leave-one-origin-out band needs another origin.");

    private Report() {
    }

    public static Path write(EvalResult result, EvalConfig config, Map<String, String> fingerprint, Map<String, String> versions, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        StringBuilder scores = new StringBuilder("model,horizon,origin,metric,value\n");
        for (ScoreRow r : result.scores()) {
            scores.append(r.model()).append(',').append(r.horizon()).append(',').append(r.origin()).append(',').append(r.metric()).append(',').append(csv(r.value())).append('\n');
        }
        Files.writeString(outDir.resolve("scores.csv"), scores.toString(), StandardCharsets.UTF_8);
        StringBuilder demand = new StringBuilder(
                "model,origin,team_id,member_id,window,window_start,window_end,forecast,truth,capacity,open_hours,new_hours,planned_hours\n");
        for (DemandRow r : result.demand()) {
            demand.append(r.model()).append(',').append(r.origin()).append(',').append(r.teamId()).append(',').append(r.memberId()).append(',')
                    .append(r.windowIndex()).append(',').append(r.windowStart()).append(',').append(r.windowEnd())
                    .append(',').append(csv(r.forecast())).append(',').append(csv(r.truth())).append(',').append(csv(r.capacity())).append(',').append(csv(r.openHours()))
                    .append(',').append(csv(r.newHours())).append(',').append(csv(r.plannedHours())).append('\n');
        }
        Files.writeString(outDir.resolve("demand.csv"), demand.toString(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("summary.md"), summary(result, config, fingerprint, versions), StandardCharsets.UTF_8);
        return outDir;
    }

    static String summary(EvalResult result, EvalConfig config, Map<String, String> fingerprint, Map<String, String> versions) {
        List<String> parts = new ArrayList<>();
        parts.add("# Forecast evaluation, as of " + config.asOf());
        parts.add("");
        parts.add("Truth: " + result.truthSource() + ". Origins: " + (result.origins().isEmpty() ? "none"
                : result.origins().stream().map(Object::toString).collect(Collectors.joining(", "))) + ".");
        parts.add("Models requested: " + (config.models().isEmpty() ? "all" : String.join(", ", config.models())) + ". Teams: "
                + (config.teams().isEmpty() ? "all" : config.teams().stream().map(Object::toString).collect(Collectors.joining(", "))) + ".");
        parts.add(String.format(Locale.ROOT, "Elapsed: %.1f s on %s, %d logical CPUs.", result.elapsedSeconds(), cpuName(), Runtime.getRuntime().availableProcessors()));
        parts.add("");
        parts.add("## Level A: arrival accuracy per model and horizon (means over origins)");
        parts.add("");
        parts.add(LEVEL_A_CAPTION);
        parts.add("");
        parts.add(levelA(result));
        parts.add("## Level B: demand accuracy per model, per member-window (all origins, teams, members, windows)");
        parts.add("");
        parts.add(levelB(result));
        parts.add("## Skipped models");
        parts.add("");
        parts.add(result.skipped().isEmpty() ? "none" : result.skipped().entrySet().stream().map(e -> "- " + e.getKey() + ": " + e.getValue()).collect(Collectors.joining("\n")));
        parts.add("");
        parts.add("## Truth and replay assumptions");
        parts.add("");
        parts.add(ASSUMPTIONS.stream().map(a -> "- " + a).collect(Collectors.joining("\n")));
        parts.add("");
        parts.add("## Data fingerprint");
        parts.add("");
        parts.add(fingerprint.entrySet().stream().map(e -> "- " + e.getKey() + ": " + e.getValue()).collect(Collectors.joining("\n")));
        parts.add("");
        parts.add("## Versions");
        parts.add("");
        parts.add(versions.entrySet().stream().map(e -> "- " + e.getKey() + ": " + e.getValue()).collect(Collectors.joining("\n")));
        parts.add("");
        parts.add("Generated " + LocalDateTime.now().withNano(0) + ".");
        parts.add("");
        return String.join("\n", parts);
    }

    public static String levelA(EvalResult result) {
        Map<String, Map<String, double[]>> acc = new TreeMap<>();
        for (ScoreRow r : result.scores()) {
            if (Double.isNaN(r.value())) {
                continue;
            }
            double[] cell = acc.computeIfAbsent(r.model() + "|" + r.horizon(), k -> new LinkedHashMap<>()).computeIfAbsent(r.metric(), k -> new double[2]);
            cell[0] += r.value();
            cell[1] += 1;
        }
        if (acc.isEmpty()) {
            return "(no rows)\n";
        }
        StringBuilder sb = new StringBuilder("| model | horizon | " + String.join(" | ", LEVEL_A) + " |\n|---|---|" + "---|".repeat(LEVEL_A.size()) + "\n");
        acc.forEach((key, metrics) -> {
            String[] mh = key.split("\\|");
            sb.append("| ").append(mh[0]).append(" | ").append(mh[1]);
            for (String metric : LEVEL_A) {
                double[] cell = metrics.get(metric);
                sb.append(" | ").append(cell == null ? "nan" : fmt(cell[0] / cell[1]));
            }
            sb.append(" |\n");
        });
        return sb.toString();
    }

    static String levelB(EvalResult result) {
        Map<String, List<DemandRow>> byModel = result.demand().stream().collect(Collectors.groupingBy(DemandRow::model, TreeMap::new, Collectors.toList()));
        if (byModel.isEmpty()) {
            return "(no rows)\n";
        }
        StringBuilder sb = new StringBuilder("| model | mae | bias | open_only_mae | overload_precision | overload_recall | rows |\n|---|---|---|---|---|---|---|\n");
        byModel.forEach((model, rows) -> {
            double[] y = rows.stream().mapToDouble(DemandRow::truth).toArray();
            double[] p = rows.stream().mapToDouble(DemandRow::forecast).toArray();
            double[] open = rows.stream().mapToDouble(DemandRow::openHours).toArray();
            boolean[] trueOver = new boolean[rows.size()];
            boolean[] predOver = new boolean[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                trueOver[i] = y[i] > rows.get(i).capacity();
                predOver[i] = p[i] > rows.get(i).capacity();
            }
            double[] pr = Metrics.overloadPrecisionRecall(trueOver, predOver);
            sb.append("| ").append(model).append(" | ").append(fmt(Metrics.mae(y, p))).append(" | ").append(fmt(Metrics.bias(y, p))).append(" | ")
                    .append(fmt(Metrics.mae(y, open))).append(" | ").append(fmt(pr[0])).append(" | ").append(fmt(pr[1])).append(" | ").append(rows.size()).append(" |\n");
        });
        return sb.toString();
    }

    static String fmt(double v) {
        return Double.isNaN(v) ? "nan" : String.format(Locale.ROOT, "%.3f", v);
    }

    static String csv(double v) {
        if (Double.isNaN(v)) {
            return "";
        }
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.format(Locale.ROOT, "%.1f", v).replaceAll("\\.0$", "") : Double.toString(v);
    }

    static String cpuName() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                if (line.startsWith("model name")) {
                    return line.substring(line.indexOf(':') + 1).trim();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // not Linux, or unreadable: fall through
        }
        return System.getProperty("os.arch");
    }
}
