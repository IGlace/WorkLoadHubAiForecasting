package com.workloadhub.forecastweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The showcase host: a Spring Boot application with the module on its classpath, its own {@code DataSource}
 * (Spring Boot's own, from {@code spring.datasource.*}), and the host code of design 2026-09-11 (role rules,
 * narration executor) in {@code host}. Everything in {@code demo} is showcase wiring on top of the module;
 * the production server deletes it. The PostgreSQL database it connects to is filled beforehand by a
 * separate command-line tool ({@code server/tools/experiment.sh}), never seeded by this application.
 *
 * <p>The package is deliberately not under {@code com.workloadhub.forecast}: component scanning would otherwise
 * pick up the module's own optional controller, which trusts {@code requestedBy} as given.
 */
@SpringBootApplication
@EnableConfigurationProperties(ForecastWebProperties.class)
public class ForecastWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForecastWebApplication.class, args);
    }
}
