package com.baysansoft.mqmanager.messaging.model;

import com.baysansoft.mqmanager.domain.DestinationKind;

/**
 * One destination on a broker.
 *
 * <p><strong>There is deliberately no depth.</strong> Getting one would mean a browse-and-count per
 * queue on the three JMS providers — turning one click into thousands of broker round trips — while
 * Kafka and IBM MQ could answer cheaply. A number that is exact on two providers, ruinous on one and
 * absent on another is worse than no number at all, and this codebase does not ship results that mean
 * different things depending on the broker. Open a destination to see its depth.
 *
 * @param internal a name the broker owns rather than the user: {@code SYSTEM.*} on IBM MQ,
 *                 {@code ActiveMQ.Advisory.*} on Classic, {@code __consumer_offsets} on Kafka,
 *                 {@code activemq.management} on Artemis. Hidden unless the user asks for it
 */
public record DestinationEntry(String name, DestinationKind kind, boolean internal) {
}
