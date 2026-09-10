package com.workloadhub.forecast.web;

import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.GitHubTokenStore;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The REST surface, only when the host asks for it and runs Spring MVC. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "whf.web", name = "enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
public class ForecastWebConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ForecastController forecastController(ForecastService service, GitHubTokenStore tokens, Clock clock) {
        return new ForecastController(service, tokens, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    ForecastExceptionHandler forecastExceptionHandler() {
        return new ForecastExceptionHandler();
    }
}
