package com.baysansoft.mqmanager.logs;

import java.util.Locale;

import ch.qos.logback.classic.Level;

/**
 * The log levels this application reports, in increasing order of severity.
 *
 * <p>Declared here rather than reusing logback's {@code Level} so the API contract does not depend on
 * the logging backend, and so "at least this severe" is a plain {@link #ordinal()} comparison rather
 * than logback's integer scale.
 */
public enum LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR;

    /** Anything more exotic than the five (logback's ALL/OFF) is reported at its nearest neighbour. */
    static LogLevel from(Level level) {
        if (level == null) {
            return INFO;
        }
        return switch (level.toInt()) {
            case Level.TRACE_INT, Level.ALL_INT -> TRACE;
            case Level.DEBUG_INT -> DEBUG;
            case Level.WARN_INT -> WARN;
            case Level.ERROR_INT, Level.OFF_INT -> ERROR;
            default -> INFO;
        };
    }

    /** Lenient parse for a query parameter, so a bad value is a 400 rather than a 500. */
    public static LogLevel parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "Unknown log level '" + value + "'. Use one of TRACE, DEBUG, INFO, WARN, ERROR.");
        }
    }

    public boolean atLeast(LogLevel minimum) {
        return ordinal() >= minimum.ordinal();
    }
}
