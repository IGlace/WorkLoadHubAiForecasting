package com.workloadhub.forecastweb.host;

/** What the host maps to HTTP 403: the acting user may not do this. The message is the refusal's reason. */
public final class HostForbidden extends RuntimeException {
    public HostForbidden(String message) {
        super(message);
    }
}
