package com.workloadhub.forecastweb.host;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses to start with the module's own REST surface switched on. That controller trusts {@code requestedBy}
 * as the caller gives it and needs no acting user at all, so with {@code whf.web.enabled=true} anyone who can
 * reach the port could start a run for any team and — worse — narrate with another user's stored token and
 * Copilot seat, going around every check in {@link HostForecastFacade}. This application decides
 * {@code requestedBy} itself and mounts its own routes under {@code /api}, so the two must never both be up.
 *
 * <p>Checked while the environment is prepared (registered in {@code META-INF/spring.factories}), which is
 * before any bean definition: the two controllers also happen to clash on the bean name
 * {@code forecastController}, so the application would fail anyway, but with a message about bean overriding
 * that says nothing about why the two must not coexist.
 */
public final class WebSurfaceGuard implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        check(Boolean.TRUE.equals(environment.getProperty("whf.web.enabled", Boolean.class, Boolean.FALSE)));
    }

    /** Throws when the module's controller would be registered next to this host's own. */
    public static void check(boolean moduleWebEnabled) {
        if (moduleWebEnabled) {
            throw new IllegalStateException("whf.web.enabled must stay false in forecast-web: the module's own controller trusts "
                    + "requestedBy as given, so it would let any caller act as any user (design 2026-09-18, section 2.2). "
                    + "This host serves its own routes under /api and decides requestedBy from the acting user.");
        }
    }
}
