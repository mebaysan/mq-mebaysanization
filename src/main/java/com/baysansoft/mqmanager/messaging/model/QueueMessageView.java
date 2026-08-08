package com.baysansoft.mqmanager.messaging.model;

import java.time.Instant;
import java.util.Map;

/**
 * One message as shown in the UI.
 *
 * @param body          the text body, cut to the configured preview size in list responses and returned
 *                      in full by the single-message endpoint
 * @param bodyTruncated true when {@code body} is not the whole payload
 * @param note          a human-readable caveat, e.g. that an ObjectMessage body is deliberately not
 *                      deserialized
 */
public record QueueMessageView(
        String messageId,
        String correlationId,
        Instant enqueueTime,
        Integer priority,
        Boolean redelivered,
        String type,
        String body,
        boolean bodyTruncated,
        Map<String, String> headers,
        Map<String, String> properties,
        String note) {
}
