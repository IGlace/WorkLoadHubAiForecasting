package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.ai.Narrator;
import com.workloadhub.forecast.ai.Prompts;
import com.workloadhub.forecast.ai.SdkCopilotGateway;
import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.service.DefaultForecastService;
import com.workloadhub.forecast.service.RunProgressTracker;
import com.workloadhub.forecast.store.AesGcmCipher;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.ForecastMigrations;
import com.workloadhub.forecast.store.JdbcGitHubTokenStore;
import com.workloadhub.forecast.store.JdbcNarrativeStore;
import com.workloadhub.forecast.store.JdbcRunStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The module's services on a CLI-owned SQLite file: one thread, planned work on, default capacity 40. Narration
 * reads its settings from the environment: WHF_TOKEN_KEY (base64, 32 bytes; required to store or read a token),
 * WHF_COPILOT_CLI_PATH (blank: the in-process runtime) and WHF_COPILOT_MODEL (blank: the account default).
 */
record Services(DefaultForecastService service, Dialect dialect, JdbcClient jdbc, ForecastRunner runner, GitHubTokenStore tokens, RunProgressTracker progress)
        implements AutoCloseable {

    static final String TOKEN_KEY_ENV = "WHF_TOKEN_KEY";
    static final String CLI_PATH_ENV = "WHF_COPILOT_CLI_PATH";
    static final String MODEL_ENV = "WHF_COPILOT_MODEL";
    static final Duration NARRATION_TIMEOUT = Duration.ofSeconds(300);

    static boolean tokenKeyConfigured() {
        String key = System.getenv(TOKEN_KEY_ENV);
        return key != null && !key.isBlank();
    }

    /** The system property wins over the environment, so tests can point at a stub without touching the environment. */
    static String cliPath() {
        String fromProperty = System.getProperty("whf.copilot.cli-path");
        return fromProperty != null && !fromProperty.isBlank() ? fromProperty : System.getenv(CLI_PATH_ENV);
    }

    static Services open(DataSource ds) {
        ForecastMigrations.run(ds);
        Dialect dialect = Dialect.of(ds);
        JdbcClient jdbc = JdbcClient.create(ds);
        ForecastRunner runner = new ForecastRunner(new CapacityRule(40), true);
        String key = System.getenv(TOKEN_KEY_ENV);
        JdbcGitHubTokenStore tokens = new JdbcGitHubTokenStore(jdbc, dialect, key == null || key.isBlank() ? null : AesGcmCipher.fromBase64Key(key.trim()));
        Path home = Path.of(System.getProperty("user.home"), ".workloadhub-forecast", "copilot");
        SdkCopilotGateway gateway = new SdkCopilotGateway(home, cliPath());
        Narrator narrator = new Narrator(gateway, Prompts.load(), NARRATION_TIMEOUT, System.getenv(MODEL_ENV));
        RunProgressTracker progress = new RunProgressTracker();
        DefaultForecastService service = new DefaultForecastService(ds, dialect, runner, new JdbcRunStore(ds, dialect), progress, 1, true, tokens,
                new JdbcNarrativeStore(ds, dialect), narrator, gateway, Clock.systemDefaultZone());
        return new Services(service, dialect, jdbc, runner, tokens, progress);
    }

    @Override
    public void close() throws Exception {
        service.close();
    }
}
