package com.workloadhub.forecast;

import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.store.AesGcmCipher;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.ForecastMigrations;
import com.workloadhub.forecast.store.JdbcGitHubTokenStore;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Registers the module's beans on top of the host's DataSource; nothing else is required of the host. */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(ForecastProperties.class)
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

    /** Marker bean so that beans needing the tables can depend on the migrations having run. */
    public static final class ForecastMigrationsRunner {
    }
}
