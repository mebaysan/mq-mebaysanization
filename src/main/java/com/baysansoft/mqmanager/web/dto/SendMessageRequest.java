package com.baysansoft.mqmanager.web.dto;

import java.util.Map;

import jakarta.validation.constraints.NotNull;

/**
 * @param payload    the message body; may be empty but not null
 * @param properties optional string properties — custom JMS properties on the JMS providers, record
 *                   headers on Kafka. Both round-trip as text through a browse
 * @param key        Kafka only: the record key, which decides the partition. The JMS providers have no
 *                   equivalent and reject a non-null value rather than dropping it
 */
public record SendMessageRequest(
        @NotNull(message = "is required") String payload,
        Map<String, String> properties,
        String key) {
}
