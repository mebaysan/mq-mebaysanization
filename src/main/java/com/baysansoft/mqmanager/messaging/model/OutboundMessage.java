package com.baysansoft.mqmanager.messaging.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One message on its way to a broker, before anything provider-specific happens to it.
 *
 * <p>A parameter object rather than a longer argument list, for the same reason
 * {@link DestinationQuery} is one: two of these fields apply to one provider and not the others, and a
 * positional list of nullable strings is a transposition waiting to happen.
 *
 * @param body        never null once constructed. An empty body is a real, sendable message; on Kafka a
 *                    null value would be a compaction tombstone, so an empty textarea must never become
 *                    one
 * @param properties  custom JMS properties on the JMS providers, record headers on Kafka. Never null
 *                    once constructed, and in the order they were given so a browse shows them as typed
 * @param key         Kafka only: the record key, which decides the partition. Null everywhere else — the
 *                    JMS providers reject a non-null one rather than dropping it
 * @param messageType JMS only. Null means the caller did not ask, which the JMS providers read as
 *                    {@link MessageType#TEXT} and Kafka reads as "nothing to object to"
 * @param targetClient IBM MQ only: whether an MQRFH2 header is written ahead of the body. Null means
 *                    the caller did not ask, which leaves IBM MQ at its own default — a header,
 *                    exactly as every send before this choice existed. The other three providers
 *                    reject a non-null one rather than dropping it
 */
public record OutboundMessage(String body, Map<String, String> properties, String key,
        MessageType messageType, TargetClient targetClient) {

    public OutboundMessage {
        body = body == null ? "" : body;
        // Not Map.copyOf: it rejects null values, and {"properties":{"a":null}} is a request a user can
        // send and that both implementations already handle. LinkedHashMap keeps the order they typed.
        properties = properties == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /** The only form every provider supports unconditionally, and what most callers want. */
    public static OutboundMessage text(String body, Map<String, String> properties) {
        // Null target client, not JMS: this factory says nothing about the header, so IBM MQ is left
        // at its own default rather than being told to keep one.
        return new OutboundMessage(body, properties, null, MessageType.TEXT, null);
    }

    /** TEXT when the caller did not ask, which is what every send did before the choice existed. */
    public MessageType messageTypeOrDefault() {
        return messageType == null ? MessageType.TEXT : messageType;
    }
}
