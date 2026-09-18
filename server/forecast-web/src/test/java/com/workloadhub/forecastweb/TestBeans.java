package com.workloadhub.forecastweb;

import com.workloadhub.forecast.ai.CopilotGateway;
import com.workloadhub.forecast.ai.FakeGateway;
import com.workloadhub.forecast.testing.SeededData;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** The seeded in-memory database of the core test jar (36 users, 30 weeks) and a scripted Copilot gateway. */
@TestConfiguration(proxyBeanMethods = false)
public class TestBeans {

    public static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Bean
    DataSource dataSource() {
        return SeededData.dataSource();
    }

    @Bean
    CopilotGateway copilotGateway() {
        return new FakeGateway();
    }
}
