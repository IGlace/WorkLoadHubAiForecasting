package com.workloadhub.forecastweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The showcase host: a Spring Boot application with the module on its classpath, its own {@code DataSource}, and
 * the host code of design 2026-09-11 (role rules, run registry, narration executor) in {@code host}. Everything in
 * {@code demo} exists so the application runs alone on a seeded SQLite file; the production server deletes it.
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
