package com.baysansoft.mqmanager.web.dto;

import java.util.Map;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A message-send request to remember for a destination.
 *
 * <p>The same shape as {@link SendMessageRequest} — the thing composed in the send panel — plus an
 * optional {@code label}. A blank or absent label records an automatic <em>history</em> entry (trimmed
 * to a cap); a non-blank label saves a <em>named</em> request that is never evicted.
 *
 * @param payload      the message body, exactly as composed; may be empty but not null
 * @param properties   the custom properties/headers; null is treated as empty
 * @param key          Kafka record key, or null
 * @param messageType  JMS body type name (TEXT/BYTES), or null
 * @param targetClient IBM MQ target client name (JMS/MQ), or null
 * @param label        the name to save under, or null/blank for an automatic history entry
 */
public record SavedRequestRequest(
        @NotNull(message = "is required") String payload,
        Map<String, String> properties,
        String key,
        String messageType,
        String targetClient,
        @Size(max = 120, message = "A name may be at most 120 characters.") String label) {
}
