package com.baysansoft.mqmanager.web.dto;

import java.util.Map;

import jakarta.validation.constraints.NotNull;

/**
 * @param payload     the message body; may be empty but not null
 * @param properties  optional string properties — custom JMS properties on the JMS providers, record
 *                    headers on Kafka. Both round-trip as text through a browse
 * @param key         Kafka only: the record key, which decides the partition. The JMS providers have no
 *                    equivalent and reject a non-null value rather than dropping it
 * @param messageType JMS only: TEXT, the default when omitted, or BYTES. Kafka rejects any value, since
 *                    its record values are bytes already. A String rather than the enum so an unknown
 *                    value becomes a 400 naming the two that are valid, instead of Jackson's own
 *                    deserialization failure naming an internal type
 * @param targetClient IBM MQ only: JMS, the default when omitted, or MQ — whether an MQRFH2 header is
 *                    written ahead of the body. The other three providers reject any value. MQ
 *                    together with a non-empty {@code properties} map is refused, since the header it
 *                    suppresses is the only thing that could have carried them. A String for the same
 *                    reason {@code messageType} is one
 */
public record SendMessageRequest(
        @NotNull(message = "is required") String payload,
        Map<String, String> properties,
        String key,
        String messageType,
        String targetClient) {
}
