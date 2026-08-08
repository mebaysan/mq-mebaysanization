package com.baysansoft.mqmanager.messaging.model;

/**
 * How many messages a purge actually removed, and why it stopped.
 *
 * <p>The stop reason is not decoration. A purge that hit the time or message cap looks exactly like a
 * purge that emptied the queue if you only report a count, and the UI would then tell the user the queue
 * is empty when it is not.
 *
 * @param note optional provider-specific caveat appended to the message the UI shows. Kafka uses it to
 *             say that a purge advances the log start offset rather than draining a queue; the JMS
 *             providers leave it null
 */
public record PurgeOutcome(long purged, StopReason stopReason, String note) {

    public enum StopReason {
        /** The queue drained: a receive returned nothing. */
        QUEUE_EMPTY,
        /** Stopped at the configured message cap; more messages may remain. */
        MESSAGE_CAP,
        /** Stopped at the configured time budget; more messages may remain. */
        TIME_CAP
    }

    /** A purge with nothing provider-specific to add. */
    public static PurgeOutcome of(long purged, StopReason stopReason) {
        return new PurgeOutcome(purged, stopReason, null);
    }

    public boolean complete() {
        return stopReason == StopReason.QUEUE_EMPTY;
    }
}
