package com.baysansoft.mqmanager.messaging.model;

/**
 * What a topic creation actually produced, echoed back so the UI states the result rather than the
 * request.
 *
 * <p>The values are the ones the broker accepted, which is why they are returned at all: a create can
 * succeed with the partition count asked for and no config surprises, and saying so plainly is the
 * honest confirmation.
 *
 * @param note provider-specific caveat appended to the message the UI shows, in the same spirit as
 *             {@link PurgeOutcome#note()}
 */
public record CreateTopicOutcome(String name, int partitions, short replicationFactor, String note) {
}
