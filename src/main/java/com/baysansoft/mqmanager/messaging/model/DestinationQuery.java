package com.baysansoft.mqmanager.messaging.model;

import com.baysansoft.mqmanager.domain.DestinationKind;

/**
 * How a destination listing is narrowed.
 *
 * @param kind   null for both kinds
 * @param prefix a case-insensitive NAME PREFIX, never a substring. IBM MQ pushes it down to the queue
 *               manager as {@code prefix*}, which is genuinely a prefix; the others apply it locally.
 *               One meaning on all four providers is the point — a substring on three and a prefix on
 *               the fourth would be a lie. Free-text substring search stays in the UI, over the page
 *               that came back, and says so
 * @param limit  0 for the configured default
 */
public record DestinationQuery(DestinationKind kind, String prefix, int limit) {

    public static DestinationQuery all() {
        return new DestinationQuery(null, null, 0);
    }
}
