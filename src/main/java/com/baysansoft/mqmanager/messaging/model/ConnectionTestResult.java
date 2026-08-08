package com.baysansoft.mqmanager.messaging.model;

/**
 * Outcome of a connectivity check. A failed test is a successful HTTP call with {@code success=false},
 * not an error response — "this connection is misconfigured" is exactly what the caller asked to find out.
 */
public record ConnectionTestResult(
        boolean success,
        String code,
        String message,
        long durationMs) {

    public static ConnectionTestResult ok(long durationMs, String message) {
        return new ConnectionTestResult(true, "OK", message, durationMs);
    }

    public static ConnectionTestResult failed(long durationMs, String code, String message) {
        return new ConnectionTestResult(false, code, message, durationMs);
    }
}
