package com.baysansoft.mqmanager.web.dto;

import java.util.Map;

import com.baysansoft.mqmanager.messaging.model.CreateTopicCommand;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * A request to create one Kafka topic.
 *
 * <p>{@code partitions} and {@code replicationFactor} are {@code @Min(1)} rather than defaulted: a
 * primitive left out of the JSON arrives as 0, and 0 is not a valid choice for either, so the caller is
 * told to state one instead of inheriting a silent default. The UI seeds both at 1.
 *
 * @param configs optional topic-level overrides ({@code retention.ms}, {@code cleanup.policy}, …).
 *                Absent is an empty map, never null, so the operation can iterate it without a guard
 */
public record CreateTopicRequest(
        @NotBlank(message = "is required") String name,
        @Min(value = 1, message = "must be at least 1") int partitions,
        @Min(value = 1, message = "must be at least 1") short replicationFactor,
        Map<String, String> configs) {

    public CreateTopicCommand toCommand() {
        return new CreateTopicCommand(name.trim(), partitions, replicationFactor,
                configs == null ? Map.of() : configs);
    }
}
