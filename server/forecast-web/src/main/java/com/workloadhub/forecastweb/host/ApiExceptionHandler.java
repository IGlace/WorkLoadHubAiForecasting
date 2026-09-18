package com.workloadhub.forecastweb.host;

import com.workloadhub.forecast.api.ForecastException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Every error is {@code {code, message}}: 401 for a missing or unknown acting user, 403 for a refused role check,
 * and the module's own mapping for a {@code ForecastException} (404 for what is not found, 400 for a bad request,
 * 409 for a state that forbids the action).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ErrorBody(String code, String message) {
    }

    static HttpStatus status(String code) {
        if (code.endsWith("_NOT_FOUND")) {
            return HttpStatus.NOT_FOUND;
        }
        if (code.equals("INVALID_REQUEST")) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.CONFLICT;
    }

    @ExceptionHandler(ActingUserException.class)
    public ResponseEntity<ErrorBody> actingUser(ActingUserException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorBody(e.code(), e.getMessage()));
    }

    @ExceptionHandler(HostForbidden.class)
    public ResponseEntity<ErrorBody> forbidden(HostForbidden e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorBody("FORBIDDEN", e.getMessage()));
    }

    @ExceptionHandler(ForecastException.class)
    public ResponseEntity<ErrorBody> forecast(ForecastException e) {
        return ResponseEntity.status(status(e.code())).body(new ErrorBody(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorBody> missingParameter(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST", firstLine(e.getMessage())));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorBody> unreadable(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root instanceof ForecastException fe ? fe.getMessage() : "malformed request: " + firstLine(root.getMessage());
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST", message));
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }
}
