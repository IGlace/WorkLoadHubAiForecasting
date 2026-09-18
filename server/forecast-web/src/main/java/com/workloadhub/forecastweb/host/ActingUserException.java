package com.workloadhub.forecastweb.host;

/** The request named no acting user, or one that does not exist: HTTP 401 with a stable code. */
public final class ActingUserException extends RuntimeException {

    public static final String HEADER = "X-Acting-User";

    private final String code;

    private ActingUserException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ActingUserException missing() {
        return new ActingUserException("ACTING_USER_MISSING", "send the acting user's id in the " + HEADER + " header");
    }

    public static ActingUserException unknown(String value) {
        return new ActingUserException("ACTING_USER_UNKNOWN", "no user " + value);
    }
}
