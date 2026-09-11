package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.NarrativeRequest;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.NarrativeStatus;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.data.ExportFiles;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

@Command(name = "narrate", description = "Store the user's GitHub token, narrate a finished run through their Copilot seat, and print the verified narrative.")
public class NarrateCommand implements Callable<Integer> {

    private static final Set<String> USAGE_CODES = Set.of("RUN_NOT_FOUND", "INVALID_REQUEST", "USER_NOT_FOUND", "TOKEN_KEY_MISSING");
    static final long POLL_MILLIS = 300;

    @Mixin DbOptions db;

    @Option(names = "--run", required = true, description = "Run id")
    String run;

    @Option(names = "--user", required = true, description = "Requesting user, name or id; the token is stored for this user")
    String user;

    @Option(names = "--lang", defaultValue = "en", description = "en or fr (default: ${DEFAULT-VALUE})")
    String lang;

    @Option(names = "--model", description = "Copilot model (default: the account's default, or WHF_COPILOT_MODEL)")
    String model;

    @Option(names = "--token-env", defaultValue = "GITHUB_TOKEN", description = "Environment variable holding the GitHub token (default: ${DEFAULT-VALUE})")
    String tokenEnv;

    @Option(names = "--json", description = "Print the stored result as JSON")
    boolean json;

    @Override
    public Integer call() throws Exception {
        if (!Services.tokenKeyConfigured()) {
            System.err.println("error: " + Services.TOKEN_KEY_ENV + " is not set; it must hold a base64 AES-256 key (openssl rand -base64 32)");
            return 2;
        }
        UUID runId;
        try {
            runId = UUID.fromString(run.trim());
        } catch (IllegalArgumentException e) {
            System.err.println("error: --run must be a run id (UUID)");
            return 2;
        }
        String language = lang.trim().toLowerCase(java.util.Locale.ROOT);
        if (!language.equals("en") && !language.equals("fr")) {
            System.err.println("error: --lang must be en or fr");
            return 2;
        }
        String token = System.getenv(tokenEnv);
        if (token == null || token.isBlank()) {
            System.err.println("error: environment variable " + tokenEnv + " is empty; it must hold the user's GitHub token");
            return 2;
        }
        try (Services s = Services.open(db.dataSource())) {
            UUID userId;
            try {
                userId = TeamArg.resolveUser(s.jdbc(), s.dialect(), user);
            } catch (IllegalArgumentException e) {
                System.err.println("error: " + e.getMessage());
                return 2;
            }
            try {
                s.tokens().save(userId, token);
            } catch (ForecastException e) {
                System.err.println("error: " + e.code() + ": " + e.getMessage());
                return 2;
            }
            Thread printer = progressPrinter(s, runId);
            printer.start();
            NarrativeResult result;
            try {
                result = s.service().narrate(new NarrativeRequest(runId, userId, language, model));
            } catch (ForecastException e) {
                System.err.println("error: " + e.code() + ": " + e.getMessage());
                return USAGE_CODES.contains(e.code()) ? 2 : 1;
            } finally {
                printer.interrupt();
                printer.join(2_000);
            }
            if (json) {
                System.out.println(ExportFiles.mapper().writeValueAsString(result));
            } else {
                print(result);
            }
            return switch (result.status()) {
                case OK -> 0;
                case UNVERIFIED -> 3;
                case FAILED -> 1;
            };
        }
    }

    /**
     * Polls the tracker and prints each new step to stderr, with the label of that moment. A step is new when the
     * phase or the message changes: the label rotates every four seconds for a page that polls, which in a
     * terminal log would only repeat the same step under three names.
     */
    private static Thread progressPrinter(Services s, UUID runId) {
        Thread t = new Thread(() -> {
            String lastStep = "";
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Optional<RunProgress> p = s.progress().get(runId);
                    if (p.isPresent() && p.get().phase().startsWith("NARRAT")) {
                        RunProgress rp = p.get();
                        String step = rp.phase() + "|" + rp.message();
                        if (!step.equals(lastStep)) {
                            System.err.println("[" + rp.percent() + "%] " + rp.label().en() + " (" + rp.message() + ")");
                            lastStep = step;
                        }
                    }
                    Thread.sleep(POLL_MILLIS);
                }
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
        }, "narrate-progress");
        t.setDaemon(true);
        return t;
    }

    private static void print(NarrativeResult r) {
        System.out.println("Narrative " + r.id() + " for run " + r.runId() + " (" + r.language() + "): " + r.status()
                + (r.model() == null ? "" : ", model " + r.model()) + ", " + r.attempts() + " attempt(s), " + r.toolCalls() + " tool call(s)");
        JsonNode usage = ExportFiles.mapper().readTree(r.usageJson());
        System.out.println("Cost (" + usage.path("source").asText() + "): input " + usage.path("input_tokens").asText("?") + " tokens, output "
                + usage.path("output_tokens").asText("?") + " tokens, credits " + usage.path("ai_credits").asText("?") + ", usd " + usage.path("usd").asText("?"));
        if (r.status() == NarrativeStatus.FAILED) {
            System.out.println("Error: " + r.error());
            if (r.rawText() != null && !r.rawText().isBlank()) {
                System.out.println("Last answer:");
                System.out.println(r.rawText());
            }
            return;
        }
        JsonNode verification = ExportFiles.mapper().readTree(r.verificationJson());
        if (r.status() == NarrativeStatus.UNVERIFIED) {
            System.out.println("Unverified numbers:");
            verification.path("unverified").forEach(u -> System.out.println("  - " + u.asText()));
        } else {
            System.out.println("Verified: " + verification.path("checked").asInt() + " number(s) checked against the facts");
        }
        System.out.println();
        System.out.println(ExportFiles.mapper().writeValueAsString(ExportFiles.mapper().readTree(r.narrativeJson())));
    }
}
