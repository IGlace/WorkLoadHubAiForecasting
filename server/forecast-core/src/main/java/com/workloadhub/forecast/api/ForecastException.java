package com.workloadhub.forecast.api;

/** An error the host can act on: a stable code plus a message safe to show. */
public final class ForecastException extends RuntimeException {

    private final String code;

    public ForecastException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ForecastException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ForecastException of(String code, String message) {
        return new ForecastException(code, message);
    }

    public static ForecastException invalidRequest(String message) {
        return new ForecastException("INVALID_REQUEST", message);
    }
}
