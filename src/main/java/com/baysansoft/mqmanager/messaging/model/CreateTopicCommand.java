package com.baysansoft.mqmanager.messaging.model;

import java.util.Map;

/**
 * A request to create one topic on a cluster that can create them.
 *
 * <p>Kafka is the only provider that creates a destination as an explicit admin operation: the JMS
 * providers either create a queue silently on first send or do not create one at all, so there is
 * nothing here they could act on. An operation reaching a provider that cannot perform it is refused,
 * never quietly ignored — see {@code MessagingOperations#createTopic}.
 *
 * @param partitions        never defaulted here: a partition count is a decision with lasting
 *                          consequences (it can be raised later but never lowered), so the caller states
 *                          it rather than inheriting a guess
 * @param replicationFactor short because that is the Kafka wire type; a single-broker cluster can only
 *                          honour 1
 * @param configs           topic-level overrides such as {@code retention.ms} or {@code cleanup.policy}.
 *                          Copied defensively and never null, so the operation can iterate it freely
 */
public record CreateTopicCommand(String name, int partitions, short replicationFactor,
        Map<String, String> configs) {

    public CreateTopicCommand {
        configs = configs == null ? Map.of() : Map.copyOf(configs);
    }
}
