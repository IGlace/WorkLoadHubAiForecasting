package com.workloadhub.forecast.samplehost;

/** What the real host maps to HTTP 403: the signed-in user may not do this. */
public final class HostForbidden extends RuntimeException {
    public HostForbidden(String message) {
        super(message);
    }
}
