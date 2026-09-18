package com.workloadhub.forecastweb.demo;

import com.workloadhub.forecast.seed.SeedConfig;
import com.workloadhub.forecastweb.ForecastWebProperties;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Demo only: the beans that let this host run alone. Production has a PostgreSQL pool from {@code spring.datasource.*},
 * keeps the module's default clock and serves no front end from here.
 */
@Configuration(proxyBeanMethods = false)
public class DemoConfiguration {

    /** The seeded SQLite file, only when {@code forecast-web.database} names one; otherwise Spring Boot's own DataSource applies. */
    @Bean
    @ConditionalOnExpression("'${forecast-web.database:}' != ''")
    DemoDatabase demoDatabase(ForecastWebProperties properties) {
        ForecastWebProperties.Seed s = properties.getSeed();
        return DemoDatabase.open(Path.of(properties.getDatabase()), new SeedConfig(s.getWeeks(), s.getEnd(), s.getSeed(), true, s.getUsers()));
    }

    @Bean
    @ConditionalOnExpression("'${forecast-web.database:}' != ''")
    DataSource dataSource(DemoDatabase demoDatabase) {
        return demoDatabase.dataSource();
    }

    /** Replaces the module's default clock; the date is pinned to {@code forecast-web.clock.today} when set. */
    @Bean
    DemoClock forecastClock(ForecastWebProperties properties) {
        String today = properties.getClock().getToday();
        return new DemoClock(ZoneId.systemDefault(), today == null || today.isBlank() ? null : LocalDate.parse(today.trim()));
    }

    @Bean
    UiResources uiResources(ForecastWebProperties properties) {
        return new UiResources(Path.of(properties.getUiDir()));
    }
}
