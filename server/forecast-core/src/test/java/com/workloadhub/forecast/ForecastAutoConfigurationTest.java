package com.workloadhub.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.api.RunSummary;
import com.workloadhub.forecast.calendar.Horizon;
import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.data.ExportImporter;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.service.DefaultForecastService;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.ForecastMigrations;
import com.workloadhub.forecast.store.JdbcRunStore;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import com.workloadhub.forecast.testing.SeededData;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ForecastAutoConfigurationTest {

    static final DataSource DS = DatabaseTestSupport.sqliteInMemory();

    @Configuration
    static class HostConfig {
        @Bean
        DataSource dataSource() {
            return DS;
        }
    }

    @Test
    void registersBeansAndRunsMigrations() {
        WorkloadHubSchema.createSqlite(DS);
        new ApplicationContextRunner()
                .withUserConfiguration(HostConfig.class)
                .withConfiguration(AutoConfigurations.of(ForecastAutoConfiguration.class))
                .withPropertyValues("whf.token-key=" + Base64.getEncoder().encodeToString(new byte[32]))
                .run(ctx -> {
                    assertEquals(Dialect.SQLITE, ctx.getBean(Dialect.class));
                    assertNotNull(ctx.getBean(GitHubTokenStore.class));
                    ForecastProperties p = ctx.getBean(ForecastProperties.class);
                    assertEquals(44.0, p.getDefaultWeeklyHours());
                    assertTrue(p.getFlyway().isEnabled());
                    // migrations ran: the users table has the token column
                    var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(DS);
                    jdbc.sql("SELECT github_token FROM users WHERE 1 = 0").query().listOfRows();
                });
    }

    @Test
    void registersTheServiceOnTopOfTheHostDataSource() {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ForecastAutoConfiguration.class))
                .withBean(DataSource.class, () -> ds)
                .withPropertyValues("whf.run-threads=1")
                .run(context -> {
                    assertNotNull(context.getBean(ForecastService.class));
                    assertNotNull(context.getBean(com.workloadhub.forecast.run.ForecastRunner.class));
                    assertNotNull(context.getBean(com.workloadhub.forecast.ai.CopilotGateway.class));
                    assertNotNull(context.getBean(com.workloadhub.forecast.ai.Narrator.class));
                    assertNotNull(context.getBean(com.workloadhub.forecast.store.JdbcNarrativeStore.class));
                    assertTrue(context.getBean(com.workloadhub.forecast.ai.CopilotGateway.class) instanceof com.workloadhub.forecast.ai.SdkCopilotGateway);
                });
    }

    @Test
    void aHostSuppliedGatewayReplacesTheSdkOne() {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        com.workloadhub.forecast.ai.FakeGateway fake = new com.workloadhub.forecast.ai.FakeGateway();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ForecastAutoConfiguration.class))
                .withBean(DataSource.class, () -> ds)
                .withBean(com.workloadhub.forecast.ai.CopilotGateway.class, () -> fake)
                .run(context -> assertTrue(context.getBean(com.workloadhub.forecast.ai.CopilotGateway.class) == fake));
    }

    /** Reconciliation happens once the context is up, not inside the service's factory method (design 2026-09-11, section 4.2). */
    @Test
    void interruptedRunsAreFailedOnceTheContextIsUp() {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        ForecastMigrations.run(ds);
        JdbcRunStore store = new JdbcRunStore(ds, Dialect.of(ds));
        UUID run = store.create(new RunRequest(UUID.randomUUID(), null), LocalDate.of(2026, 9, 7), LocalDateTime.of(2026, 9, 7, 9, 0));
        store.markRunning(run);
        assertEquals(RunStatus.RUNNING, store.find(run).orElseThrow().status());
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ForecastAutoConfiguration.class))
                .withBean(DataSource.class, () -> ds)
                .withPropertyValues("whf.run-threads=1")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    RunSummary after = store.find(run).orElseThrow();
                    assertEquals(RunStatus.FAILED, after.status());
                    assertEquals(JdbcRunStore.INTERRUPTED, after.error());
                    assertNotNull(after.finishedAt());
                });
    }

    /** A database the module's tables are missing from is the host's business, not a reason to refuse to start. */
    @Test
    void startUpSurvivesAMissingTable() {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ForecastAutoConfiguration.class))
                .withBean(DataSource.class, () -> ds)
                .withPropertyValues("whf.flyway.enabled=false", "whf.run-threads=1")
                .run(context -> {
                    assertNull(context.getStartupFailure(), "a failed reconciliation is logged, never fatal");
                    assertNotNull(context.getBean(ForecastService.class));
                });
    }

    @Test
    void aWindowCountOutsideTheRangeRefusesToStart() {
        for (int bad : new int[] {0, -1, 7, 99}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> startContextWith(bad));
            assertTrue(e.getMessage().contains("whf.forecast.windows"), "the message names the property");
            assertTrue(e.getMessage().contains("1") && e.getMessage().contains("6"), "and the range");
        }
    }

    @Test
    void sixWindowsProduceThirtyDaysAndSevenHorizons() {
        // asOf is pinned to a MONDAY: the horizon count is run-day dependent (a run fits between
        // w and w + 1 boosters), so a Friday would fit 6 and this case would fail for the wrong reason.
        LocalDate asOf = LocalDate.of(2026, 9, 7);
        ForecastData data = SeededData.data();
        UUID teamId = data.teams().stream().filter(t -> !data.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).findFirst().orElseThrow();
        RunResult r = serviceWith(6, asOf).runNow(new RunRequest(teamId, null));
        long members = r.memberWindows().stream().map(MemberWindowForecast::userId).distinct().count();
        assertEquals(6 * members, r.memberWindows().size());
        assertEquals(30 * members, r.memberDays().size());
        assertEquals(7, Horizon.maxHorizon(Weeks.lastCompleteWeek(asOf), 6));
    }

    /** Starts the auto-configured context on a fresh database with {@code whf.forecast.windows} set, throwing whatever
     * IllegalStateException the {@code forecastRunner} bean's validation raises rather than swallowing it as a startup failure. */
    private static void startContextWith(int windows) {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ForecastAutoConfiguration.class))
                .withBean(DataSource.class, () -> ds)
                .withPropertyValues("whf.run-threads=1", "whf.forecast.windows=" + windows)
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    if (failure == null) {
                        return;
                    }
                    Throwable cause = failure;
                    while (cause.getCause() != null && !(cause instanceof IllegalStateException)) {
                        cause = cause.getCause();
                    }
                    if (cause instanceof IllegalStateException ise) {
                        throw ise;
                    }
                    throw new RuntimeException(failure);
                });
    }

    /** An auto-configured service over an isolated copy of the seeded database, run day fixed at {@code asOf}. */
    private static DefaultForecastService serviceWith(int windows, LocalDate asOf) {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        new ExportImporter(ds).importAll(SeededData.envelope(), true);
        Clock clock = Clock.fixed(asOf.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        AtomicReference<DefaultForecastService> ref = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ForecastAutoConfiguration.class))
                .withBean(DataSource.class, () -> ds)
                .withBean(Clock.class, () -> clock)
                .withPropertyValues("whf.run-threads=1", "whf.forecast.windows=" + windows)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    ref.set(context.getBean(DefaultForecastService.class));
                });
        return ref.get();
    }
}
