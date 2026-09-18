package com.workloadhub.forecastweb.demo;

import com.workloadhub.forecastweb.ForecastWebProperties;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Showcase only: the two beans that let this host serve a front end and move its own clock. The DataSource
 * is Spring Boot's own, from spring.datasource.*, exactly as it will be on the WorkloadHub server: this
 * application never seeds and never creates a database. The work tables are filled beforehand by
 * `server/tools/experiment.sh` (server/forecast-web/README.md).
 */
@Configuration(proxyBeanMethods = false)
public class ShowcaseConfiguration {

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
