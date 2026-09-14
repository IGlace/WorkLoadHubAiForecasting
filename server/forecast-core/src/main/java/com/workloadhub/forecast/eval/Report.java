package com.workloadhub.forecast.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import ml.dmlc.xgboost4j.java.XGBoost;

/** scores.csv, demand.csv and summary.md in the columns of the Python harness. */
public final class Report {

    static final List<String> LEVEL_A = List.of("mae", "coverage80", "wql", "seconds");
    static final String LEVEL_A_CAPTION = "`coverage80` and `wql` are scored on the leave-one-origin-out residual band the run would show, so the"
            + " interval is the one a user actually sees. `seconds` is fit plus predict for the whole backtest divided by the origins scored; it is"
            + " reported on the first horizon row and left empty on the others.";
    static final List<String> ASSUMPTIONS = List.of(
            "Level B truth is the hours logged in time_logs on the window's five weekdays, summed per member and window; work still in progress at"
                    + " export time has few logs, so the most recent origins are deflated and the forecast looks high there.",
            "The replay is a Monday-morning evaluation: each origin is replayed as of the Monday after it, so a task assigned on the first forecast"
                    + " Monday counts as open work rather than as an arrival.",
            "The arrival level scores horizons 1..windows at every origin, windows being the count the evaluation was asked for (default 2); a"
                    + " replayed run itself touches every horizon week its windows of five weekdays cover.",
            "A single-origin run reports NaN interval coverage and NaN weighted quantile loss: the leave-one-origin-out band needs another origin.");

    private Report() {
    }

    /**
     * What produced a report, for its "Versions" section: the JVM, the gradient-boosting library and this module.
     * Read at runtime from the jar each class came from rather than hand-copied, so a version in a report cannot
     * drift from the one that computed the numbers under it — which is the whole point of recording it.
     */
    public static Map<String, String> versions() {
        Map<String, String> versions = new LinkedHashMap<>();
        versions.put("java", System.getProperty("java.version"));
        versions.put("xgboost4j", versionOf(XGBoost.class));
        versions.put("forecast-core", versionOf(Report.class));
        return versions;
    }

    /**
     * The manifest first, then Maven's own {@code pom.properties} inside the same jar, because a jar built by the
     * jar plugin's defaults carries the coordinates but no {@code Implementation-Version} — xgboost4j's does not.
     * A class loaded from a directory has neither and reports {@code unknown}: that is a working copy, not a release.
     */
    private static String versionOf(Class<?> type) {
        Package pkg = type.getPackage();
        if (pkg != null && pkg.getImplementationVersion() != null) {
            return pkg.getImplementationVersion();
        }
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null || !source.getLocation().getPath().endsWith(".jar")) {
            return "unknown";
        }
        try (JarFile jar = new JarFile(Path.of(source.getLocation().toURI()).toFile())) {
            JarEntry entry = jar.stream().filter(e -> e.getName().startsWith("META-INF/maven/") && e.getName().endsWith("/pom.properties"))
                    .findFirst().orElse(null);
            if (entry == null) {
                return "unknown";
            }
            Properties coordinates = new Properties();
            try (var in = jar.getInputStream(entry)) {
                coordinates.load(in);
            }
            return coordinates.getProperty("version", "unknown");
        } catch (Exception unreadable) {
            return "unknown";
        }
    }

    /**
     * The config and the data fingerprint come off {@code result}, so the report can only ever describe the
     * evaluation that produced it.
     */
    public static Path write(EvalResult result, Map<String, String> versions, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        StringBuilder scores = new StringBuilder("horizon,origin,metric,value\n");
        for (ScoreRow r : result.scores()) {
            scores.append(r.horizon()).append(',').append(r.origin()).append(',').append(r.metric()).append(',').append(csv(r.value())).append('\n');
        }
        Files.writeString(outDir.resolve("scores.csv"), scores.toString(), StandardCharsets.UTF_8);
        StringBuilder demand = new StringBuilder(
                "origin,team_id,member_id,window,window_start,window_end,forecast,truth,capacity,backlog_excess_hrs,due_excess_hrs\n");
        for (DemandRow r : result.demand()) {
            demand.append(r.origin()).append(',').append(r.teamId()).append(',').append(r.memberId()).append(',')
                    .append(r.windowIndex()).append(',').append(r.windowStart()).append(',').append(r.windowEnd())
                    .append(',').append(csv(r.forecast())).append(',').append(csv(r.truth())).append(',').append(csv(r.capacity()))
                    .append(',').append(csv(r.backlogExcessHrs())).append(',').append(csv(r.dueExcessHrs())).append('\n');
        }
        Files.writeString(outDir.resolve("demand.csv"), demand.toString(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("summary.md"), summary(result, versions), StandardCharsets.UTF_8);
        return outDir;
    }

    static String summary(EvalResult result, Map<String, String> versions) {
        EvalConfig config = result.resolved();
        List<String> parts = new ArrayList<>();
        parts.add("# Forecast evaluation, as of " + config.asOf());
        parts.add("");
        parts.add("Truth: " + result.truthSource() + ". Origins: " + (result.origins().isEmpty() ? "none"
                : result.origins().stream().map(Object::toString).collect(Collectors.joining(", "))) + ".");
        parts.add("Windows: " + config.windows() + ". Teams: "
                + (config.teams().isEmpty() ? "all" : config.teams().stream().map(Object::toString).collect(Collectors.joining(", "))) + ".");
        parts.add(String.format(Locale.ROOT, "Elapsed: %.1f s on %s, %d logical CPUs.", result.elapsedSeconds(), cpuName(), Runtime.getRuntime().availableProcessors()));
        parts.add("");
        parts.add("## Level A: arrival accuracy per horizon (means over origins)");
        parts.add("");
        parts.add(LEVEL_A_CAPTION);
        parts.add("");
        parts.add(levelA(result));
        parts.add("## Level B: demand accuracy per member-window (all origins, teams, members, windows)");
        parts.add("");
        parts.add(levelB(result));
        parts.add("## Truth and replay assumptions");
        parts.add("");
        parts.add(ASSUMPTIONS.stream().map(a -> "- " + a).collect(Collectors.joining("\n")));
        parts.add("");
        parts.add("## Data fingerprint");
        parts.add("");
        parts.add(result.fingerprint().entrySet().stream().map(e -> "- " + e.getKey() + ": " + e.getValue()).collect(Collectors.joining("\n")));
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
        Map<Integer, Map<String, double[]>> acc = new TreeMap<>();
        for (ScoreRow r : result.scores()) {
            if (Double.isNaN(r.value())) {
                continue;
            }
            double[] cell = acc.computeIfAbsent(r.horizon(), k -> new LinkedHashMap<>()).computeIfAbsent(r.metric(), k -> new double[2]);
            cell[0] += r.value();
            cell[1] += 1;
        }
        if (acc.isEmpty()) {
            return "(no rows)\n";
        }
        StringBuilder sb = new StringBuilder("| horizon | " + String.join(" | ", LEVEL_A) + " |\n|---|" + "---|".repeat(LEVEL_A.size()) + "\n");
        acc.forEach((horizon, metrics) -> {
            sb.append("| ").append(horizon);
            for (String metric : LEVEL_A) {
                double[] cell = metrics.get(metric);
                sb.append(" | ").append(cell == null ? "nan" : fmt(cell[0] / cell[1]));
            }
            sb.append(" |\n");
        });
        return sb.toString();
    }

    static String levelB(EvalResult result) {
        List<DemandRow> rows = result.demand();
        if (rows.isEmpty()) {
            return "(no rows)\n";
        }
        // open_only_mae compared the forecast against a naive "predict the open backlog" baseline; that baseline
        // was DemandRow.openHours, which went with the open/new/planned split (task 5). No replacement baseline
        // is defined by the 2026-09-13 design, so the column is dropped rather than fed a figure that would not
        // mean what its header claims.
        StringBuilder sb = new StringBuilder("| mae | bias | overload_precision | overload_recall | rows |\n|---|---|---|---|---|\n");
        double[] y = rows.stream().mapToDouble(DemandRow::truth).toArray();
        double[] p = rows.stream().mapToDouble(DemandRow::forecast).toArray();
        boolean[] trueOver = new boolean[rows.size()];
        boolean[] predOver = new boolean[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            trueOver[i] = y[i] > rows.get(i).capacity();
            predOver[i] = p[i] > rows.get(i).capacity();
        }
        double[] pr = Metrics.overloadPrecisionRecall(trueOver, predOver);
        sb.append("| ").append(fmt(Metrics.mae(y, p))).append(" | ").append(fmt(Metrics.bias(y, p))).append(" | ")
                .append(fmt(pr[0])).append(" | ").append(fmt(pr[1])).append(" | ").append(rows.size()).append(" |\n");
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
