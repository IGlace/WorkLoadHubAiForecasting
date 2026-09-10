package com.workloadhub.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.util.Base64;
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
                    assertEquals(40.0, p.getDefaultWeeklyHours());
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
                });
    }
}
