package com.baysansoft.mqmanager.messaging.model;

import java.util.Locale;

/**
 * How a message body is put on the wire.
 *
 * <p>Not cosmetic, and not a JMS detail that could just as well be hidden: the two produce different
 * AMQP 1.0 body sections when a broker converts them. A {@code TextMessage} becomes an
 * {@code amqp-value(String)}, which a Python 2 Qpid client hands to the application as {@code unicode};
 * a {@code BytesMessage} becomes a {@code Data} (binary) section, which arrives as {@code str}. A reader
 * that requires bytes rejects the first outright — the Jeppesen/Carmen DIG framework raises
 * {@code TypeError: Content must be str, found <type 'unicode'>} and stops its reader thread. No charset
 * or property on the producer changes that; only the body type does. So this is the caller's choice and
 * is never made for them.
 *
 * <p>Meaningful only for the JMS providers. Every Kafka record value is already {@code byte[]}, so Kafka
 * has no such choice and refuses one rather than accepting a field it will not act on.
 */
public enum MessageType {

    /** {@code jakarta.jms.TextMessage}. The default, and what every message sent before this existed was. */
    TEXT,

    /** {@code jakarta.jms.BytesMessage}, carrying the UTF-8 encoding of the body. */
    BYTES;

    /** Lenient parse for a request field, so a bad value is a 400 rather than a 500. */
    public static MessageType parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "Unknown message type '" + value + "'. Use TEXT or BYTES.");
        }
    }

    /**
     * Null for an absent value, so "the caller did not ask" stays distinguishable from "the caller asked
     * for TEXT". That distinction is what lets Kafka reject an explicit choice without breaking every
     * send that was written before the choice existed.
     */
    public static MessageType parseOptional(String value) {
        return value == null || value.isBlank() ? null : parse(value);
    }
}
