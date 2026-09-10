package com.workloadhub.forecast.ai;

import com.github.copilot.AllowCopilotExperimental;
import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.SystemMessageMode;
import com.github.copilot.ffi.NativeRuntimeLoader;
import com.github.copilot.generated.AssistantIntentEvent;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.AssistantReasoningDeltaEvent;
import com.github.copilot.generated.AssistantReasoningEvent;
import com.github.copilot.generated.AssistantUsageEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.ToolExecutionCompleteEvent;
import com.github.copilot.generated.ToolExecutionStartEvent;
import com.github.copilot.generated.rpc.AccountGetQuotaParams;
import com.github.copilot.generated.rpc.AccountGetQuotaResult;
import com.github.copilot.generated.rpc.AccountQuotaSnapshot;
import com.github.copilot.generated.rpc.SessionUsageGetMetricsResult;
import com.github.copilot.generated.rpc.UsageMetricsModelMetric;
import com.github.copilot.rpc.ClientInfo;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.GetAuthStatusResponse;
import com.github.copilot.rpc.InfiniteSessionConfig;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SystemMessageConfig;
import com.github.copilot.rpc.ToolDefinition;
import com.github.copilot.rpc.ToolSet;
import com.github.copilot.tool.Param;
import com.workloadhub.forecast.api.ForecastException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gateway over copilot-sdk-java. One client per token, started with the token as the only credential and
 * COPILOT_HOME under the module's work directory; the in-process runtime by default, a CLI subprocess when a
 * path is configured. Nothing here is reachable from tests except the pure mappings.
 */
public final class SdkCopilotGateway implements CopilotGateway {

    private static final Logger LOG = LoggerFactory.getLogger(SdkCopilotGateway.class);
    static final Duration START_TIMEOUT = Duration.ofSeconds(90);
    static final Duration RPC_TIMEOUT = Duration.ofSeconds(30);
    static final String APPLICATION = "workloadhub-forecast";
    static final String REJECTION = "the narrator may only read the run facts";

    private final Path copilotHome;
    private final String cliPath;
    private final Function<CopilotClientOptions, CopilotClient> clientFactory;

    public SdkCopilotGateway(Path copilotHome, String cliPath) {
        this(copilotHome, cliPath, CopilotClient::new);
    }

    /** Test-only seam: lets a test fail client construction without touching the runtime. */
    SdkCopilotGateway(Path copilotHome, String cliPath, Function<CopilotClientOptions, CopilotClient> clientFactory) {
        this.copilotHome = copilotHome;
        this.cliPath = cliPath == null ? "" : cliPath.trim();
        this.clientFactory = clientFactory;
    }

    // ----- pure mappings ----------------------------------------------------------------------

    static CopilotClientOptions options(String token, Path copilotHome, String cliPath) {
        CopilotClientOptions o = new CopilotClientOptions();
        o.setGitHubToken(token);
        o.setUseLoggedInUser(false);
        o.setCopilotHome(copilotHome.toAbsolutePath().toString());
        o.setLogLevel("error");
        o.setClientInfo(new ClientInfo().setApplicationName(APPLICATION).setApplicationVersion(moduleVersion()).setIntegrationVersion(sdkVersion()));
        if (cliPath != null && !cliPath.isBlank()) {
            o.setCliPath(cliPath.trim());
        }
        return o;
    }

    static SessionConfig sessionConfig(SessionSpec spec, Consumer<NarrationEvent> events) {
        SessionConfig cfg = new SessionConfig();
        if (spec.model() != null && !spec.model().isBlank()) {
            cfg.setModel(spec.model());
        }
        cfg.setSystemMessage(new SystemMessageConfig().setMode(SystemMessageMode.REPLACE).setContent(spec.systemMessage()));
        cfg.setTools(toolDefinitions(spec.tools()));
        cfg.setAvailableTools(new ToolSet().addCustom("*"));
        cfg.setStreaming(true);
        cfg.setEnableSkills(false);
        cfg.setEnableConfigDiscovery(false);
        cfg.setEnableSessionStore(false);
        cfg.setSkipCustomInstructions(true);
        cfg.setInfiniteSessions(new InfiniteSessionConfig().setEnabled(false));
        cfg.setOnPermissionRequest(permissionHandler(spec.tools().stream().map(ToolSpec::name).collect(Collectors.toSet())));
        cfg.setOnEvent(ev -> {
            NarrationEvent mapped = mapEvent(ev);
            if (mapped != null) {
                events.accept(mapped);
            }
        });
        return cfg;
    }

    static List<ToolDefinition> toolDefinitions(List<ToolSpec> specs) {
        List<ToolDefinition> out = new ArrayList<>();
        for (ToolSpec s : specs) {
            ToolDefinition def = s.memberScoped()
                    ? ToolDefinition.<String, Object>from(s.name(), s.description(),
                            Param.of(String.class, "member_id", "The member id from get_run_overview, copied exactly"), s.handler()::apply)
                    : ToolDefinition.<Object>from(s.name(), s.description(), () -> s.handler().apply(null));
            out.add(def.skipPermission(true));
        }
        return out;
    }

    static PermissionHandler permissionHandler(Set<String> toolNames) {
        return (request, invocation) -> {
            Map<String, Object> ext = request.getExtensionData();
            boolean ours = "custom-tool".equals(request.getKind()) && ext != null && toolNames.contains(String.valueOf(ext.get("toolName")));
            return CompletableFuture.completedFuture(ours ? PermissionRequestResult.approveOnce() : PermissionRequestResult.reject(REJECTION));
        };
    }

    static NarrationEvent mapEvent(SessionEvent ev) {
        if (ev instanceof AssistantMessageEvent e && e.getData() != null) {
            return NarrationEvent.message(e.getData().content(), e.getData().model());
        }
        if (ev instanceof AssistantMessageDeltaEvent e && e.getData() != null) {
            return NarrationEvent.answerDelta(e.getData().deltaContent());
        }
        if (ev instanceof AssistantIntentEvent e && e.getData() != null) {
            return NarrationEvent.intent(e.getData().intent());
        }
        if (ev instanceof AssistantReasoningDeltaEvent e && e.getData() != null) {
            return NarrationEvent.thinkingDelta(e.getData().reasoningId(), e.getData().deltaContent());
        }
        if (ev instanceof AssistantReasoningEvent e && e.getData() != null) {
            return NarrationEvent.thinkingFull(e.getData().reasoningId(), e.getData().content());
        }
        if (ev instanceof ToolExecutionStartEvent e && e.getData() != null) {
            return NarrationEvent.toolStart(e.getData().toolCallId(), e.getData().toolName());
        }
        if (ev instanceof ToolExecutionCompleteEvent e && e.getData() != null) {
            return NarrationEvent.toolDone(e.getData().toolCallId());
        }
        if (ev instanceof AssistantUsageEvent e && e.getData() != null) {
            var d = e.getData();
            return NarrationEvent.usage(new UsageEvent(d.model(), d.inputTokens(), d.outputTokens(), d.cacheReadTokens(), d.reasoningTokens()));
        }
        if (ev instanceof SessionErrorEvent e && e.getData() != null) {
            return NarrationEvent.error(e.getData().message());
        }
        return null;
    }

    /** The module's own version, read from the jar's Maven descriptor; "dev" when it runs from a class directory. */
    static String moduleVersion() {
        try (InputStream in = SdkCopilotGateway.class.getClassLoader()
                .getResourceAsStream("META-INF/maven/com.workloadhub/workloadhub-forecast-core/pom.properties")) {
            if (in != null) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                    if (line.startsWith("version=")) {
                        return line.substring("version=".length()).trim();
                    }
                }
            }
        } catch (IOException e) {
            LOG.debug("module version unreadable: {}", e.getMessage());
        }
        return "dev";
    }

    static String sdkVersion() {
        for (String line : SkillTexts.resource("copilot-runtime.properties").lines().toList()) {
            if (line.startsWith("version=")) {
                return line.substring("version=".length()).trim();
            }
        }
        return "unknown";
    }

    @AllowCopilotExperimental
    static UsageMetrics toMetrics(SessionUsageGetMetricsResult r) {
        Map<String, UsageMetrics.ModelMetric> models = new LinkedHashMap<>();
        if (r.modelMetrics() != null) {
            for (Map.Entry<String, UsageMetricsModelMetric> e : r.modelMetrics().entrySet()) {
                UsageMetricsModelMetric m = e.getValue();
                long requests = m.requests() == null || m.requests().count() == null ? 0 : m.requests().count();
                var u = m.usage();
                models.put(e.getKey(), new UsageMetrics.ModelMetric(requests, zero(u == null ? null : u.inputTokens()), zero(u == null ? null : u.outputTokens()),
                        zero(u == null ? null : u.cacheReadTokens()), u == null ? null : u.reasoningTokens()));
            }
        }
        return new UsageMetrics(r.totalNanoAiu(), r.totalUserRequests(), r.totalPremiumRequestCost(), r.totalApiDurationMs(), models);
    }

    private static long zero(Long v) {
        return v == null ? 0 : v;
    }

    @AllowCopilotExperimental
    static Map<String, Object> toQuota(AccountGetQuotaResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (r.quotaSnapshots() != null) {
            for (Map.Entry<String, AccountQuotaSnapshot> e : r.quotaSnapshots().entrySet()) {
                AccountQuotaSnapshot s = e.getValue();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("used", s.usedRequests());
                m.put("entitlement", s.entitlementRequests());
                m.put("unlimited", s.isUnlimitedEntitlement());
                m.put("remaining_percentage", s.remainingPercentage());
                m.put("overage", s.overage());
                m.put("reset_date", s.resetDate() == null ? null : s.resetDate().toString());
                out.put(e.getKey(), m);
            }
        }
        return out;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    // ----- the live side ----------------------------------------------------------------------

    @Override
    public RuntimeInfo runtime() {
        String version = sdkVersion();
        if (!cliPath.isEmpty()) {
            Path p = Path.of(cliPath);
            boolean ok = Files.isExecutable(p);
            return new RuntimeInfo(ok, cliPath, version, ok ? "CLI subprocess" : "configured CLI is not an executable file: " + cliPath);
        }
        try {
            Path p = NativeRuntimeLoader.resolve();
            return new RuntimeInfo(true, p.toString(), version, "in-process runtime");
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            return new RuntimeInfo(false, null, version, "runtime unavailable: " + rootMessage(e));
        }
    }

    @Override
    public CopilotConnection open(String token) {
        try {
            Files.createDirectories(copilotHome);
        } catch (IOException e) {
            throw ForecastException.of("COPILOT_UNAVAILABLE", "cannot create the Copilot home " + copilotHome + ": " + e.getMessage());
        }
        CopilotClient client;
        try {
            client = clientFactory.apply(options(token, copilotHome, cliPath));
        } catch (RuntimeException | LinkageError e) {
            throw ForecastException.of("COPILOT_UNAVAILABLE", "Copilot client could not be created: " + rootMessage(e));
        }
        try {
            client.start().get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            try {
                client.forceStop().get(RPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // the start already failed; the message below is the one that matters
            }
            throw ForecastException.of("COPILOT_UNAVAILABLE", "Copilot runtime could not start: " + rootMessage(e));
        }
        return new Connection(client, token);
    }

    private static <T> T await(CompletableFuture<T> future, Duration timeout, String what) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(what + " interrupted");
        } catch (ExecutionException e) {
            throw new IllegalStateException(what + " failed: " + rootMessage(e));
        } catch (TimeoutException e) {
            throw new IllegalStateException(what + " timed out after " + timeout.toSeconds() + " s");
        }
    }

    private static final class Connection implements CopilotConnection {
        private final CopilotClient client;
        private final String token;

        Connection(CopilotClient client, String token) {
            this.client = client;
            this.token = token;
        }

        @Override
        public AuthStatus authStatus() {
            GetAuthStatusResponse r = await(client.getAuthStatus(), RPC_TIMEOUT, "auth status");
            return new AuthStatus(r.isAuthenticated(), r.getLogin(), r.getStatusMessage());
        }

        @Override
        public NarrationSession createSession(SessionSpec spec, Consumer<NarrationEvent> events) {
            CopilotSession s = await(client.createSession(sessionConfig(spec, events)), START_TIMEOUT, "session creation");
            return new Session(s);
        }

        @Override
        public Optional<Map<String, Object>> quota(Duration timeout) {
            try {
                AccountGetQuotaResult r = client.getRpc().account.getQuota(new AccountGetQuotaParams(null, token)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return Optional.of(toQuota(r));
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.warn("copilot quota unavailable: {}", rootMessage(e));
                return Optional.empty();
            }
        }

        @Override
        public void close() {
            try {
                client.stop().get(RPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                client.forceStop();
                throw new IllegalStateException("copilot client stop failed: " + rootMessage(e));
            }
        }
    }

    private static final class Session implements NarrationSession {
        private final CopilotSession session;

        Session(CopilotSession session) {
            this.session = session;
        }

        @Override
        public String ask(String prompt, Duration timeout) throws TimeoutException {
            try {
                AssistantMessageEvent ev = session.sendAndWait(new MessageOptions().setPrompt(prompt), timeout.toMillis())
                        .get(timeout.toMillis() + 5_000, TimeUnit.MILLISECONDS);
                return ev == null || ev.getData() == null ? null : ev.getData().content();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for Copilot");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof TimeoutException) {
                    throw new TimeoutException(rootMessage(e));
                }
                throw new IllegalStateException(rootMessage(e));
            }
        }

        @Override
        public Optional<UsageMetrics> usage(Duration timeout) {
            try {
                return Optional.of(toMetrics(session.getRpc().usage.getMetrics().get(timeout.toMillis(), TimeUnit.MILLISECONDS)));
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.warn("copilot usage metrics unavailable: {}", rootMessage(e));
                return Optional.empty();
            }
        }

        @Override
        public void close() {
            session.close();
        }
    }
}
