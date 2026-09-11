package com.workloadhub.forecast;

import com.workloadhub.forecast.ai.CopilotGateway;
import com.workloadhub.forecast.ai.Narrator;
import com.workloadhub.forecast.ai.Prompts;
import com.workloadhub.forecast.ai.SdkCopilotGateway;
import com.workloadhub.forecast.api.ForecastService;
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
import com.workloadhub.forecast.web.ForecastWebConfiguration;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Registers the module's beans on top of the host's DataSource; nothing else is required of the host. */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(ForecastProperties.class)
@Import(ForecastWebConfiguration.class)
public class ForecastAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Dialect forecastDialect(DataSource dataSource) {
        return Dialect.of(dataSource);
    }

    /** Runs the module's migrations before any other bean touches the tables. */
    @Bean
    ForecastMigrationsRunner forecastMigrationsRunner(DataSource dataSource, ForecastProperties properties) {
        if (properties.getFlyway().isEnabled()) {
            ForecastMigrations.run(dataSource);
        }
        return new ForecastMigrationsRunner();
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcClient forecastJdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    GitHubTokenStore gitHubTokenStore(JdbcClient jdbc, Dialect dialect, ForecastProperties properties,
            ForecastMigrationsRunner migrated) {
        String key = properties.getTokenKey();
        AesGcmCipher cipher = key == null || key.isBlank() ? null : AesGcmCipher.fromBase64Key(key);
        return new JdbcGitHubTokenStore(jdbc, dialect, cipher);
    }

    @Bean
    @ConditionalOnMissingBean
    CapacityRule capacityRule(ForecastProperties properties) {
        return new CapacityRule(properties.getDefaultWeeklyHours());
    }

    @Bean
    @ConditionalOnMissingBean
    ForecastRunner forecastRunner(CapacityRule capacityRule, ForecastProperties properties) {
        return new ForecastRunner(capacityRule, properties.getPlannedWork().isEnabled());
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcRunStore jdbcRunStore(DataSource dataSource, Dialect dialect, ForecastMigrationsRunner migrated) {
        return new JdbcRunStore(dataSource, dialect);
    }

    @Bean
    @ConditionalOnMissingBean
    RunProgressTracker runProgressTracker(Clock clock) {
        return new RunProgressTracker(clock);
    }

    @Bean
    @ConditionalOnMissingBean
    CopilotGateway copilotGateway(ForecastProperties properties) {
        return new SdkCopilotGateway(Path.of(properties.getWorkDir(), "copilot"), properties.getCopilot().getCliPath());
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcNarrativeStore jdbcNarrativeStore(DataSource dataSource, Dialect dialect, ForecastMigrationsRunner migrated) {
        return new JdbcNarrativeStore(dataSource, dialect);
    }

    @Bean
    @ConditionalOnMissingBean
    Narrator narrator(CopilotGateway gateway, ForecastProperties properties) {
        return new Narrator(gateway, Prompts.load(), Duration.ofSeconds(properties.getCopilot().getTimeoutSeconds()), properties.getCopilot().getModel());
    }

    /** The run day is today by this clock; a host that keeps its own time zone or pins time in tests provides its own bean. */
    @Bean
    @ConditionalOnMissingBean
    Clock forecastClock() {
        return Clock.systemDefaultZone();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    ForecastService forecastService(DataSource dataSource, Dialect dialect, ForecastRunner runner, JdbcRunStore store, RunProgressTracker progress,
            ForecastProperties properties, GitHubTokenStore tokens, JdbcNarrativeStore narratives, Narrator narrator, CopilotGateway gateway, Clock clock) {
        return new DefaultForecastService(dataSource, dialect, runner, store, progress, properties.getRunThreads(),
                properties.getPlannedWork().isEnabled(), tokens, narratives, narrator, gateway, clock);
    }

    /** Reconciles the interrupted runs once every singleton exists, so a host that owns the tables has migrated them first. */
    @Bean
    ForecastStartupReconciliation forecastStartupReconciliation(ForecastService forecastService) {
        return new ForecastStartupReconciliation(forecastService);
    }

    /** Marker bean so that beans needing the tables can depend on the migrations having run. */
    public static final class ForecastMigrationsRunner {
    }

    /**
     * Fails the runs the previous process left {@code QUEUED} or {@code RUNNING} (design 2026-09-11, section 4.2),
     * once the whole context is up: with {@code whf.flyway.enabled=false} the host's own migrations run first, and a
     * database that cannot answer is logged and never stops the host from starting.
     */
    public static final class ForecastStartupReconciliation implements SmartInitializingSingleton {

        private static final Logger LOG = LoggerFactory.getLogger(ForecastStartupReconciliation.class);

        private final ForecastService service;

        ForecastStartupReconciliation(ForecastService service) {
            this.service = service;
        }

        @Override
        public void afterSingletonsInstantiated() {
            if (!(service instanceof DefaultForecastService defaultService)) {
                return; // the host replaced the bean: reconciliation is then its own business
            }
            try {
                defaultService.recoverInterruptedRuns();
            } catch (RuntimeException e) {
                LOG.warn("could not reconcile interrupted forecast runs at start-up: {}", e.toString());
            }
        }
    }
}
