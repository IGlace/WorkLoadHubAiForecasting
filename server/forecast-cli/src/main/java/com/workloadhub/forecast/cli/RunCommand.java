package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.api.ModelScore;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.data.ExportFiles;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

@Command(name = "run", description = "Run a forecast for one team and print the champion, the scores and the member-window table.")
public class RunCommand implements Callable<Integer> {

    private static final Set<String> USAGE_CODES = Set.of("TEAM_NOT_FOUND", "INVALID_REQUEST");

    private static final ValueSerializer<Double> NON_FINITE_AS_NULL = new ValueSerializer<Double>() {
        @Override
        public void serialize(Double value, JsonGenerator gen, SerializationContext ctxt) {
            if (value == null || value.isNaN() || value.isInfinite()) {
                gen.writeNull();
            } else {
                gen.writeNumber(value);
            }
        }
    };

    /**
     * {@code ExportFiles.mapper()} plus one override: a non-finite double (NaN or infinite, as an unscorable
     * MASE or MAE can still be, in principle, upstream of this mapper) serialises as JSON {@code null} rather
     * than Jackson's default of a quoted {@code "NaN"} string, which is not the shape any consumer of
     * {@code --json} expects. Registered for both the boxed type ({@code Double.class}, used by fields that
     * are unscorable rather than merely non-finite) and the primitive one ({@code Double.TYPE}, i.e.
     * {@code double.class}, since Jackson looks up a property's serializer by its declared type and a record
     * accessor returning {@code double} never goes through the boxed lookup).
     */
    static final JsonMapper JSON_MAPPER = ExportFiles.mapper().rebuild()
            .addModule(new SimpleModule().addSerializer(Double.class, NON_FINITE_AS_NULL).addSerializer(Double.TYPE, NON_FINITE_AS_NULL))
            .build();

    /** Package-private for direct testing without a full CLI run. */
    static String toJson(RunResult result) {
        return JSON_MAPPER.writeValueAsString(result);
    }

    @Mixin DbOptions db;

    @Option(names = "--team", required = true, description = "Team name or id")
    String team;

    @Option(names = "--as-of", description = "Run day, ISO (default: today; an experiment override, the server always uses today)")
    String asOf;

    @Option(names = "--model", description = "Force a model: xgboost or seasonal_naive")
    String model;

    @Option(names = "--user", description = "Requesting user, name or id (optional)")
    String user;

    @Option(names = "--no-planned", description = "Switch the planned-work allocation off for this run")
    boolean noPlanned;

    @Option(names = "--json", description = "Print the result as JSON")
    boolean json;

    @Override
    public Integer call() throws Exception {
        try (Services s = Services.open(db.dataSource())) {
            // This command owns the runs: a run left QUEUED or RUNNING by an earlier crash cannot still be alive
            // (design 2026-09-11, section 4.2). The read-only commands leave such rows alone.
            s.service().recoverInterruptedRuns();
            UUID teamId;
            UUID userId;
            LocalDate date;
            try {
                teamId = TeamArg.resolve(s.jdbc(), s.dialect(), team);
                userId = TeamArg.resolveUser(s.jdbc(), s.dialect(), user);
                date = asOf == null ? LocalDate.now() : LocalDate.parse(asOf);
            } catch (IllegalArgumentException | DateTimeParseException e) {
                System.err.println("error: " + e.getMessage());
                return 2;
            }
            RunResult result;
            try {
                result = s.service().runNow(new RunRequest(teamId, userId, model, noPlanned ? Boolean.FALSE : null), date);
            } catch (ForecastException e) {
                System.err.println("error: " + e.code() + ": " + e.getMessage());
                return USAGE_CODES.contains(e.code()) ? 2 : 1;
            }
            if (json) {
                System.out.println(toJson(result));
                return 0;
            }
            print(result, s);
            return 0;
        }
    }

    /** Same convention as {@code DefaultForecastService}'s {@code finite}: null (unscorable) rows are excluded, not zeroed. */
    private static String meanMase(List<Double> mases) {
        double[] finite = mases.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).filter(m -> !Double.isNaN(m)).toArray();
        return finite.length == 0 ? "n/a" : String.format("%.3f", java.util.stream.DoubleStream.of(finite).average().orElseThrow());
    }

    private void print(RunResult r, Services s) {
        Map<UUID, String> names = new HashMap<>();
        s.jdbc().sql("SELECT id, full_name FROM users").query().listOfRows()
                .forEach(row -> names.put(UUID.fromString(row.get("id").toString()), String.valueOf(row.get("full_name"))));
        String mase = r.run().championMase() == null ? "n/a" : String.format("%.2f", r.run().championMase());
        long members = r.memberWindows().stream().map(MemberWindowForecast::userId).distinct().count();
        StringJoiner windows = new StringJoiner(", ");
        r.memberWindows().stream().filter(w -> !r.memberWindows().isEmpty() && w.userId().equals(r.memberWindows().get(0).userId()))
                .forEach(w -> windows.add("window " + w.windowIndex() + " " + w.windowStart() + ".." + w.windowEnd()));
        System.out.printf("Run %s: champion %s (MASE %s), %d members, %s%n", r.run().id(), r.run().championModel(), mase, members,
                windows.length() == 0 ? "no windows" : windows);
        System.out.println();
        System.out.printf("%-16s %8s %10s%n", "model", "horizon", "mean MASE");
        r.scores().stream().collect(Collectors.groupingBy(sc -> sc.model() + "|" + sc.horizon(), java.util.TreeMap::new,
                Collectors.mapping(ModelScore::mase, Collectors.toList())))
                .forEach((key, mases) -> System.out.printf("%-16s %8s %10s%n", key.split("\\|")[0], key.split("\\|")[1], meanMase(mases)));
        if (!r.unavailable().isEmpty()) {
            r.unavailable().forEach((k, v) -> System.out.println("unavailable: " + k + ": " + v));
        }
        System.out.println();
        System.out.printf("%-28s %-6s %-10s %-10s %7s %7s %7s %7s %7s %7s %8s %8s%n", "member", "window", "start", "end", "open", "new", "planned", "demand",
                "low", "high", "capacity", "overload");
        for (MemberWindowForecast w : r.memberWindows()) {
            System.out.printf("%-28s %-6d %-10s %-10s %7.1f %7.1f %7.1f %7.1f %7.1f %7.1f %8.1f %8.1f%n", names.getOrDefault(w.userId(), w.userId().toString()),
                    w.windowIndex(), w.windowStart(), w.windowEnd(), w.openHrs(), w.newHrs(), w.plannedHrs(), w.demandHrs(), w.lowHrs(), w.highHrs(),
                    w.capacityHrs(), w.overloadHrs());
        }
    }
}
