package com.workloadhub.forecastweb.host;

import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.store.Dialect;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The host's beans on top of the module's: the directory, the access rules, the registry, the facade and the acting-user resolver. */
@Configuration(proxyBeanMethods = false)
public class HostConfiguration {

    @Bean
    Directory directory(JdbcClient jdbc, Dialect dialect) {
        return new Directory(jdbc, dialect);
    }

    @Bean
    ForecastAccess forecastAccess(JdbcClient jdbc, Dialect dialect) {
        return new ForecastAccess(jdbc, dialect);
    }

    @Bean
    RunRegistry runRegistry(JdbcClient jdbc) {
        return new RunRegistry(jdbc);
    }

    @Bean(destroyMethod = "close")
    HostForecastFacade hostForecastFacade(ForecastService service, ForecastAccess access, GitHubTokenStore tokens, RunRegistry registry, Clock clock) {
        return new HostForecastFacade(service, access, tokens, registry, clock);
    }

    @Bean
    WebMvcConfigurer actingUserArguments(Directory directory) {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new ActingUserResolver(directory));
            }
        };
    }
}
