package com.baysansoft.mqmanager.web;

import org.springframework.http.HttpStatus;

/**
 * A broker-side failure that has already been translated into something a human can act on.
 *
 * <p>Carries both the stable code and the status so the exception advice stays dumb. Broker faults
 * default to 502 rather than 500: the fault is downstream of this application, and the UI renders the
 * two differently.
 */
public class MqOperationException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public MqOperationException(String code, String message) {
        this(code, HttpStatus.BAD_GATEWAY, message, null);
    }

    public MqOperationException(String code, HttpStatus status, String message) {
        this(code, status, message, null);
    }

    public MqOperationException(String code, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
