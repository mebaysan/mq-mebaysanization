package com.baysansoft.mqmanager.domain;

import java.util.Locale;

/**
 * What a destination is.
 *
 * <p>Lives in {@code domain} rather than {@code messaging.model} because it is also persisted, as the
 * {@code kind} column of a remembered destination.
 */
public enum DestinationKind {

    QUEUE,
    TOPIC,

    /**
     * The broker reported the name but not what it is — an Artemis address with no matching queue, or a
     * remembered destination saved before its kind was known. Never a guess dressed up as a fact.
     */
    UNKNOWN;

    /** Lenient parse for a query parameter, so a bad value is a 400 rather than a 500. */
    public static DestinationKind parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "Unknown destination kind '" + value + "'. Use QUEUE, TOPIC or UNKNOWN.");
        }
    }
}
