package com.baysansoft.mqmanager.web.dto;

import com.baysansoft.mqmanager.messaging.model.DepthOutcome;
import com.baysansoft.mqmanager.messaging.model.PurgeOutcome;

/** Response shapes for the queue endpoints. */
public final class QueueResponses {

    private QueueResponses() {
    }

    /** @param messageId the id the broker assigned, so the UI can highlight the row it just created */
    public record SendMessageResponse(String messageId) {
    }

    /**
     * @param deleted false means the message is provably not on the queue. Cases where deletion was
     *                merely impossible are errors, not a {@code false} here.
     */
    public record DeleteMessageResponse(boolean deleted, String message) {

        public static DeleteMessageResponse wasDeleted() {
            return new DeleteMessageResponse(true, "Message deleted.");
        }

        public static DeleteMessageResponse wasNotFound() {
            return new DeleteMessageResponse(false,
                    "That message is no longer on the queue — it may already have been consumed or expired.");
        }
    }

    /**
     * @param complete false when a cap stopped the purge, so the UI must not say the queue is empty
     * @param note     provider-specific caveat, null when there is nothing to add. Kept separate from
     *                 {@code message} so the UI can render it in its own, quieter place
     */
    public record PurgeResponse(long purged, String stopReason, boolean complete, String message,
            String note) {

        public static PurgeResponse from(PurgeOutcome outcome) {
            String message = switch (outcome.stopReason()) {
                // Deliberately not "the queue is now empty": on Kafka this is a topic, and on every
                // provider it is a claim about the instant the purge finished, not a lasting one.
                case QUEUE_EMPTY -> "Removed " + outcome.purged()
                        + " message(s). Nothing was left to remove.";
                case MESSAGE_CAP -> "Removed " + outcome.purged() + " message(s), then stopped at the "
                        + "configured limit. More messages may remain — run the purge again.";
                case TIME_CAP -> "Removed " + outcome.purged() + " message(s), then stopped at the time "
                        + "limit. More messages may remain — run the purge again.";
            };
            return new PurgeResponse(outcome.purged(), outcome.stopReason().name(), outcome.complete(),
                    message, outcome.note());
        }
    }

    /** @param exact false renders in the UI as "N+", never as "N" */
    public record DepthResponse(long count, boolean exact, String note) {

        public static DepthResponse from(DepthOutcome outcome) {
            return new DepthResponse(outcome.count(), outcome.exact(), outcome.note());
        }
    }
}
