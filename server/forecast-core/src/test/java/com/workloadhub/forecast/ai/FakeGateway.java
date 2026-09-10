package com.workloadhub.forecast.ai;

import com.workloadhub.forecast.api.ForecastException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/** A scripted stand-in for the SDK: replies in order, streamed events before each reply, recorded calls. */
public final class FakeGateway implements CopilotGateway {

    /** Put this in {@link #replies} to make the next ask time out. */
    public static final Object TIMEOUT = new Object();

    public final List<Object> replies;
    public boolean authenticated = true;
    public RuntimeException openError;
    public RuntimeException authError;
    public RuntimeException sessionError;
    public boolean finalMessage = true;
    /** Overrides {@link #finalMessage} per attempt (0-based); an attempt beyond the list falls back to it. */
    public List<Boolean> finalMessagePerAttempt;
    public boolean replyNull;
    /** Emitted as an ERROR event on the next {@code ask} call only, then cleared: a session error does not outlive its attempt. */
    public String sessionErrorText;
    public boolean closeThrows;
    /** When true, the usage event is delivered from a background thread (joined before {@code ask} returns), exercising the state lock across threads. */
    public boolean usageFromWorkerThread;
    public UsageMetrics metrics = metrics(1.5e9);
    public RuntimeException metricsError;
    public List<String> intents = new ArrayList<>();
    public List<String[]> reasoningDeltas = new ArrayList<>();
    public List<String[]> reasoningFull = new ArrayList<>();
    public List<String> messageDeltas = new ArrayList<>();
    public String unmatchedToolDoneId;
    public Map<String, Object> quota;
    public RuntimeInfo runtimeInfo = new RuntimeInfo(true, "/tmp/runtime.node", "1.0.13-preview.6", "in-process runtime");

    public boolean opened;
    public boolean closed;
    public String tokenSeen;
    public FakeSession session;
    public SessionSpec spec;

    public FakeGateway(Object... replies) {
        this.replies = new ArrayList<>(List.of(replies));
    }

    public static UsageMetrics metrics(Double nanoAiu) {
        Map<String, UsageMetrics.ModelMetric> models = new LinkedHashMap<>();
        models.put("gpt-5", new UsageMetrics.ModelMetric(1, 100, 50, 0, 0L));
        return new UsageMetrics(nanoAiu, 1L, 1.0, 2500L, models);
    }

    /** A narrative that cites, for every member, the demand and capacity of their first forecast row. */
    public static String goodNarrative(JsonNode facts) {
        StringBuilder members = new StringBuilder();
        for (JsonNode m : facts.path("members")) {
            JsonNode row = m.path("forecast").get(0);
            if (members.length() > 0) {
                members.append(',');
            }
            members.append("{\"member_id\": \"").append(m.path("id").asText()).append("\", \"name\": \"").append(m.path("name").asText().replace("\"", ""))
                    .append("\", \"risk_level\": \"low\", \"summary\": \"Demand ").append(row.path("demand").asDouble()).append(" h against ")
                    .append(row.path("capacity").asDouble()).append(" h capacity in the window starting ").append(row.path("start").asText())
                    .append(".\", \"patterns\": [], \"warnings\": []}");
        }
        return "{\"run_summary\": \"All members within capacity.\", \"members\": [" + members + "], \"team_risks\": [], \"rebalancing\": [],"
                + " \"suggested_adjustments\": [], \"model_notes\": \"\"}";
    }

    @Override
    public CopilotConnection open(String token) {
        if (openError != null) {
            throw ForecastException.of("COPILOT_UNAVAILABLE", openError.getMessage());
        }
        opened = true;
        tokenSeen = token;
        return new FakeConnection();
    }

    @Override
    public RuntimeInfo runtime() {
        return runtimeInfo;
    }

    final class FakeConnection implements CopilotConnection {
        @Override
        public AuthStatus authStatus() {
            if (authError != null) {
                throw authError;
            }
            return new AuthStatus(authenticated, authenticated ? "sara" : null, authenticated ? null : "not signed in");
        }

        @Override
        public NarrationSession createSession(SessionSpec s, Consumer<NarrationEvent> events) {
            if (sessionError != null) {
                throw sessionError;
            }
            spec = s;
            session = new FakeSession(events);
            return session;
        }

        @Override
        public Optional<Map<String, Object>> quota(Duration timeout) {
            return Optional.ofNullable(quota);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    final class FakeSession implements NarrationSession {
        final Consumer<NarrationEvent> events;
        final List<String> prompts = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        boolean closed;

        FakeSession(Consumer<NarrationEvent> events) {
            this.events = events;
        }

        @Override
        public String ask(String prompt, Duration timeout) throws TimeoutException {
            prompts.add(prompt);
            int attemptIndex = prompts.size() - 1;
            List<ToolSpec> tools = spec.tools();
            for (int i = 0; i < Math.min(2, tools.size()); i++) {
                events.accept(NarrationEvent.toolStart("c" + i, tools.get(i).name()));
                tools.get(i).handler().apply(tools.get(i).memberScoped() ? "nobody" : null);
                events.accept(NarrationEvent.toolDone("c" + i));
            }
            if (unmatchedToolDoneId != null) {
                events.accept(NarrationEvent.toolDone(unmatchedToolDoneId));
            }
            for (String intent : intents) {
                events.accept(NarrationEvent.intent(intent));
            }
            for (String[] d : reasoningDeltas) {
                events.accept(NarrationEvent.thinkingDelta(d[0], d[1]));
            }
            for (String[] f : reasoningFull) {
                events.accept(NarrationEvent.thinkingFull(f[0], f[1]));
            }
            for (String chunk : messageDeltas) {
                events.accept(NarrationEvent.answerDelta(chunk));
            }
            if (replyNull) {
                return null;
            }
            if (sessionErrorText != null) {
                events.accept(NarrationEvent.error(sessionErrorText));
                sessionErrorText = null; // one-shot: a session error does not outlive the attempt that raised it
            }
            Object reply = replies.remove(0);
            if (reply == TIMEOUT) {
                throw new TimeoutException("no answer within " + timeout.toSeconds() + " s");
            }
            if (reply instanceof RuntimeException e) {
                throw e;
            }
            UsageEvent usage = new UsageEvent("gpt-5", 100L, 50L, 20L, 0L);
            if (usageFromWorkerThread) {
                ExecutorService pool = Executors.newSingleThreadExecutor();
                try {
                    Future<?> f = pool.submit(() -> events.accept(NarrationEvent.usage(usage)));
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                } finally {
                    pool.shutdown();
                }
            } else {
                events.accept(NarrationEvent.usage(usage));
            }
            String text = (String) reply;
            boolean useFinalMessage = finalMessagePerAttempt != null && attemptIndex < finalMessagePerAttempt.size()
                    ? finalMessagePerAttempt.get(attemptIndex)
                    : finalMessage;
            if (!useFinalMessage) {
                int half = text.length() / 2;
                events.accept(NarrationEvent.answerDelta(text.substring(0, half)));
                events.accept(NarrationEvent.answerDelta(text.substring(half)));
                return null;
            }
            events.accept(NarrationEvent.message(text, "gpt-5"));
            return text;
        }

        @Override
        public Optional<UsageMetrics> usage(Duration timeout) {
            calls.add("usage");
            if (metricsError != null) {
                throw metricsError;
            }
            return Optional.of(metrics);
        }

        @Override
        public void close() {
            calls.add("close");
            closed = true;
            if (closeThrows) {
                throw new IllegalStateException("close failed: connection already closed");
            }
        }
    }
}
