package com.workloadhub.forecastweb;

import com.workloadhub.forecast.ai.CopilotGateway;
import com.workloadhub.forecast.ai.FakeGateway;
import com.workloadhub.forecast.testing.SeededData;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** A dedicated Testcontainers PostgreSQL seeded from the core test jar's committed fixture (36 users, 30 weeks) and a scripted Copilot gateway. */
@TestConfiguration(proxyBeanMethods = false)
public class TestBeans {

    public static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Bean
    DataSource dataSource() {
        // A dedicated database, not the JVM-wide shared one: this test writes extensively (runs, tokens,
        // role flips, a moved demo clock), and sharing would let a sibling test see rows it did not expect.
        return SeededData.freshDataSource();
    }

    @Bean
    CopilotGateway copilotGateway() {
        return new FakeGateway();
    }
}
