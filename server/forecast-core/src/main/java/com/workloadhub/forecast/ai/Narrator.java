package com.workloadhub.forecast.ai;

import com.workloadhub.forecast.ai.NarrationProgress.Step;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.NarrativeStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * One narration: open a connection for the user's token, check the token, create a session over the facts tools,
 * ask (twice at most), validate, verify, read the cost, close. Failures before a session exists are thrown as
 * {@link ForecastException}; once a session exists the outcome is returned whatever happened, with its cost.
 */
public final class Narrator {

    private static final Logger LOG = LoggerFactory.getLogger(Narrator.class);
    public static final Duration METRICS_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_ATTEMPTS = 2;

    private final CopilotGateway gateway;
    private final Prompts prompts;
    private final Duration timeout;
    private final String defaultModel;

    public Narrator(CopilotGateway gateway, Prompts prompts, Duration timeout, String defaultModel) {
        this.gateway = gateway;
        this.prompts = prompts;
        this.timeout = timeout;
        this.defaultModel = defaultModel == null ? "" : defaultModel.trim();
    }

    /** What the streamed events left behind: the final messages, the model, the usage events, the tools, this attempt's deltas. */
    private static final class State {
        final List<String> messages = new ArrayList<>();
        String model;
        final List<UsageEvent> usageEvents = new ArrayList<>();
        final List<String> tools = new ArrayList<>();
        final StringBuilder deltas = new StringBuilder();
        final Set<String> streamedReasoning = new HashSet<>();
        final Map<String, String> toolNames = new HashMap<>();
        String lastError;
    }

    public NarrationOutcome narrate(JsonNode facts, String language, String modelOverride, String token, NarrationProgress progress) {
        progress.step(Step.STARTING, null);
        CopilotConnection connection = gateway.open(token);
        try {
            AuthStatus auth;
            try {
                auth = connection.authStatus();
            } catch (RuntimeException e) {
                throw ForecastException.of("TOKEN_REJECTED", "could not read Copilot sign-in status: " + e.getMessage());
            }
            if (!auth.authenticated()) {
                throw ForecastException.of("TOKEN_REJECTED", auth.message() != null ? auth.message() : "the token is not accepted by Copilot");
            }
            State state = new State();
            FactsTools tools = new FactsTools(facts);
            String model = modelOverride != null && !modelOverride.isBlank() ? modelOverride.trim() : defaultModel.isEmpty() ? null : defaultModel;
            progress.step(Step.SESSION, null);
            NarrationSession session;
            try {
                session = connection.createSession(new SessionSpec(model, prompts.systemMessage(), tools.specs()), e -> onEvent(e, state, progress));
            } catch (RuntimeException e) {
                throw ForecastException.of("COPILOT_UNAVAILABLE", "could not create Copilot session: " + e.getMessage());
            }
            try {
                return converse(session, facts, language, state, progress);
            } finally {
                try {
                    session.close();
                } catch (RuntimeException e) {
                    LOG.warn("copilot session close failed: {}", e.getMessage());
                }
            }
        } finally {
            try {
                connection.close();
            } catch (RuntimeException e) {
                LOG.warn("copilot client close failed: {}", e.getMessage());
            }
        }
    }

    private NarrationOutcome converse(NarrationSession session, JsonNode facts, String language, State state, NarrationProgress progress) {
        String prompt = prompts.userPrompt(facts, language);
        String raw = "";
        List<String> problems = List.of();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            progress.step(Step.ASKING, String.valueOf(attempt));
            progress.resetAnswer();
            synchronized (state) {
                state.deltas.setLength(0);
                state.messages.clear();
                state.lastError = null;
            }
            String content;
            try {
                content = session.ask(prompt, timeout);
            } catch (TimeoutException e) {
                return failed(session, state, attempt, "timeout", "no answer within " + timeout.toSeconds() + " s", null);
            } catch (RuntimeException e) {
                String error = e.getMessage();
                String sessionError;
                synchronized (state) {
                    sessionError = state.lastError;
                }
                if (sessionError != null) {
                    error += "; session error: " + sessionError;
                }
                return failed(session, state, attempt, "model_error", error, null);
            }
            synchronized (state) {
                raw = contentOf(content, state);
            }
            progress.step(Step.CHECKING, null);
            try {
                NarrativeContract.Narrative narrative = NarrativeContract.parse(raw);
                problems = NarrativeContract.validateAgainstFacts(narrative, facts);
                if (problems.isEmpty()) {
                    NumberVerifier.Report report = NumberVerifier.verify(narrative, facts);
                    String usage = usageOf(session, state);
                    synchronized (state) {
                        return new NarrationOutcome(report.ok() ? NarrativeStatus.OK : NarrativeStatus.UNVERIFIED, null, narrative.toJson(), null,
                                report.toJson(), usage, state.model, attempt, state.tools, null);
                    }
                }
            } catch (NarrativeContract.ContractException e) {
                problems = e.problems();
            }
            LOG.info("narrative rejected on attempt {}: {}", attempt, problems);
            prompt = prompts.retryPrompt(problems);
        }
        return failed(session, state, MAX_ATTEMPTS, "invalid_output", String.join("; ", problems), raw);
    }

    private NarrationOutcome failed(NarrationSession session, State state, int attempts, String reason, String detail, String rawText) {
        String usage = usageOf(session, state);
        synchronized (state) {
            return new NarrationOutcome(NarrativeStatus.FAILED, reason, null, rawText, "{}", usage, state.model, attempts, state.tools, reason + ": " + detail);
        }
    }

    /** The cost, read while the session is still open: its own metrics, else the streamed events, else nothing. */
    private static String usageOf(NarrationSession session, State state) {
        try {
            Optional<UsageMetrics> metrics = session.usage(METRICS_TIMEOUT);
            if (metrics.isPresent()) {
                return Usage.toJson(Usage.fromMetrics(metrics.get()));
            }
        } catch (RuntimeException e) {
            LOG.warn("copilot usage metrics unavailable: {}", e.getMessage());
        }
        synchronized (state) {
            if (state.usageEvents.isEmpty()) {
                return Usage.toJson(Usage.empty());
            }
            return Usage.toJson(Usage.fromEvents(List.copyOf(state.usageEvents)));
        }
    }

    private static String contentOf(String content, State state) {
        if (content != null) {
            return content;
        }
        if (!state.messages.isEmpty()) {
            return state.messages.get(state.messages.size() - 1);
        }
        return state.deltas.toString();
    }

    /** Events arrive on the SDK's threads while {@code ask} blocks the caller: every touch of the state is under its lock. */
    private static void onEvent(NarrationEvent e, State state, NarrationProgress progress) {
        synchronized (state) {
            handle(e, state, progress);
        }
    }

    private static void handle(NarrationEvent e, State state, NarrationProgress progress) {
        switch (e.kind()) {
            case MESSAGE -> {
                state.messages.add(e.text());
                if (e.model() != null) {
                    state.model = e.model();
                }
            }
            case ANSWER_DELTA -> {
                state.deltas.append(e.text());
                progress.answer(e.text());
            }
            case INTENT -> progress.thinking(e.text() + "\n");
            case THINKING_DELTA -> {
                state.streamedReasoning.add(e.id());
                progress.thinking(e.text());
            }
            case THINKING_FULL -> {
                if (!state.streamedReasoning.contains(e.id())) {
                    progress.thinking(e.text() + "\n");
                }
            }
            case TOOL_START -> {
                state.tools.add(e.toolName());
                state.toolNames.put(e.id(), e.toolName());
                progress.step(Step.TOOL, e.toolName());
            }
            case TOOL_DONE -> progress.step(Step.TOOL_DONE, state.toolNames.get(e.id()));
            case USAGE -> state.usageEvents.add(e.usage());
            case ERROR -> state.lastError = e.text();
        }
    }
}
