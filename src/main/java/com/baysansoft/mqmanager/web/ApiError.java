package com.baysansoft.mqmanager.web;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The single error shape for every API failure.
 *
 * <p>{@code status}, {@code error} and {@code message} are the contract and are always non-null, so the
 * client never has to defend against a missing field. {@code code} is a stable machine-readable string
 * (for example {@code BROKER_AUTH_FAILED}) that the UI maps to provider-specific help text.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        int status,
        String error,
        String message,
        String code,
        String path,
        Instant timestamp) {

    public static ApiError of(int status, String error, String message, String code, String path) {
        return new ApiError(
                status,
                error == null ? "Error" : error,
                message == null || message.isBlank() ? "Request failed." : message,
                code,
                path,
                Instant.now());
    }
}
