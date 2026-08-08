package com.baysansoft.mqmanager.web;

/** A requested resource (currently only a connection profile) does not exist. */
public class NotFoundException extends RuntimeException {

    private final String code;

    public NotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static NotFoundException connectionProfile(Long id) {
        return new NotFoundException("CONNECTION_NOT_FOUND", "No connection profile with id " + id + ".");
    }

    public String getCode() {
        return code;
    }
}
