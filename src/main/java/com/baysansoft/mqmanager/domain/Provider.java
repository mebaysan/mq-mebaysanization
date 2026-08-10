package com.baysansoft.mqmanager.domain;

import java.util.EnumSet;
import java.util.Set;

import static com.baysansoft.mqmanager.domain.Provider.Capability.AUTO_CREATES_DESTINATIONS;
import static com.baysansoft.mqmanager.domain.Provider.Capability.BROWSE_IS_COMPLETE;
import static com.baysansoft.mqmanager.domain.Provider.Capability.JMS;
import static com.baysansoft.mqmanager.domain.Provider.Capability.REQUIRES_QUEUE_MANAGER;
import static com.baysansoft.mqmanager.domain.Provider.Capability.SELECTOR_REACHES_ALL_MESSAGES;
import static com.baysansoft.mqmanager.domain.Provider.Capability.SUPPORTS_BROKER_URL_OVERRIDE;
import static com.baysansoft.mqmanager.domain.Provider.Capability.SUPPORTS_SINGLE_MESSAGE_DELETE;
import static com.baysansoft.mqmanager.domain.Provider.Capability.SUPPORTS_TARGET_CLIENT;
import static com.baysansoft.mqmanager.domain.Provider.Capability.USES_BOOTSTRAP_SERVERS;

/**
 * The supported brokers, plus the behavioural differences the UI has to be honest about.
 *
 * <p>Three of the four are written once against {@code jakarta.jms}; these flags exist because the
 * brokers genuinely differ for browse, delete-by-id and destination creation, and pretending otherwise
 * would make the tool lie to its user. Kafka is not JMS at all and differs far more than the others do
 * from each other.
 *
 * <p>Capabilities are a {@link Set} rather than a constructor full of positional booleans: at nine
 * flags a positional list is unreadable and one transposed argument is a silent behaviour change.
 */
public enum Provider {

    /**
     * Apache ActiveMQ Classic 6.x. Its main {@code activemq-client} artifact is natively Jakarta.
     *
     * <p>Browse is capped at the destination's {@code maxBrowsePageSize} (default 400) whenever a policy
     * entry matches, and the enumeration simply ends — no exception, no flag. A selector-based consumer
     * only ever evaluates messages the broker has paged in ({@code maxPageSize}, default 200), so
     * delete-by-id cannot reach a message deeper than that.
     */
    ACTIVE_MQ("ActiveMQ Classic", EnumSet.of(
            JMS, AUTO_CREATES_DESTINATIONS, SUPPORTS_BROKER_URL_OVERRIDE, SUPPORTS_SINGLE_MESSAGE_DELETE)),

    /**
     * Apache ActiveMQ Artemis. Browsers walk the whole queue including paged messages, and a
     * {@code JMSMessageID} selector is rewritten to the core {@code AMQUserID} filter and evaluated
     * across the paging cursor — so both browse and delete reach any depth.
     *
     * <p>Auto-created queues are also auto-<em>deleted</em> once idle and empty, which looks
     * nondeterministic to a user who is not expecting it.
     */
    ARTEMIS("ActiveMQ Artemis", EnumSet.of(
            JMS, AUTO_CREATES_DESTINATIONS, BROWSE_IS_COMPLETE, SELECTOR_REACHES_ALL_MESSAGES,
            SUPPORTS_BROKER_URL_OVERRIDE, SUPPORTS_SINGLE_MESSAGE_DELETE)),

    /**
     * IBM MQ over the pure-Java client in client (TCP) mode.
     *
     * <p>A {@code JMSMessageID} selector is translated into an {@code MQGET} with
     * {@code MQMO_MATCH_MSG_ID} — a native queue-manager match that reaches any depth. Queues are never
     * auto-created: an unknown name fails with reason code 2085.
     *
     * <p>It is also the only provider that writes a header of its own ahead of the body: a JMS put
     * carries an MQRFH2 unless the destination's {@code TARGCLIENT} says otherwise, which is invisible
     * to a JMS reader and the first thing a native {@code MQGET} reader trips over.
     */
    IBM_MQ("IBM MQ", EnumSet.of(
            JMS, BROWSE_IS_COMPLETE, SELECTOR_REACHES_ALL_MESSAGES, REQUIRES_QUEUE_MANAGER,
            SUPPORTS_SINGLE_MESSAGE_DELETE, SUPPORTS_TARGET_CLIENT)),

    /**
     * Apache Kafka, over {@code kafka-clients} directly. There is no JMS API and no connection factory:
     * {@code KafkaMessagingOperations} drives a producer, a consumer and an admin client.
     *
     * <p>The differences are not cosmetic. A "queue" is a topic with N partitions. A record has no id —
     * identity is {@code (topic, partition, offset)}. A browse reads from the start of every partition
     * without committing, so it is naturally non-destructive. Depth is {@code end - start} summed across
     * partitions, which counts records <em>retained</em>, not records unconsumed. And a single record
     * cannot be deleted at all: the log is immutable.
     *
     * <p>{@link #autoCreatesDestinations()} is false because auto-creation depends on the broker's
     * {@code auto.create.topics.enable}, which most production clusters turn off. Claiming true would be
     * a guess, so the UI says a topic "may not exist" instead.
     */
    KAFKA("Apache Kafka", EnumSet.of(BROWSE_IS_COMPLETE, USES_BOOTSTRAP_SERVERS));

    /** The behavioural facts the rest of the application and the UI branch on. */
    public enum Capability {
        /** Speaks {@code jakarta.jms}, so its connection comes from a {@code ConnectionFactoryBuilder}. */
        JMS,
        /** Sending to an unknown destination silently creates it, rather than failing. */
        AUTO_CREATES_DESTINATIONS,
        /** A browse can see every message, with no broker-side count cap. */
        BROWSE_IS_COMPLETE,
        /** A message-id selector can match at any depth, not just an initial window. */
        SELECTOR_REACHES_ALL_MESSAGES,
        /** A queue manager name and channel are mandatory. */
        REQUIRES_QUEUE_MANAGER,
        /** A raw broker URL may be supplied instead of host/port. */
        SUPPORTS_BROKER_URL_OVERRIDE,
        /** One message can be removed without touching the rest. */
        SUPPORTS_SINGLE_MESSAGE_DELETE,
        /** A send can choose whether the provider writes a header of its own ahead of the body. */
        SUPPORTS_TARGET_CLIENT,
        /** Addressed by a comma-separated bootstrap list instead of a single host and port. */
        USES_BOOTSTRAP_SERVERS
    }

    private final String displayName;
    private final Set<Capability> capabilities;

    Provider(String displayName, Set<Capability> capabilities) {
        this.displayName = displayName;
        this.capabilities = capabilities;
    }

    public String displayName() {
        return displayName;
    }

    public boolean has(Capability capability) {
        return capabilities.contains(capability);
    }

    /** This provider is reached through {@code JmsMessagingOperations} and has a connection factory. */
    public boolean isJms() {
        return has(JMS);
    }

    /** Sending to an unknown queue name silently creates it, rather than failing. */
    public boolean autoCreatesDestinations() {
        return has(AUTO_CREATES_DESTINATIONS);
    }

    /** A browse can see every message on the destination, with no broker-side count cap. */
    public boolean browseIsComplete() {
        return has(BROWSE_IS_COMPLETE);
    }

    /** A {@code JMSMessageID} selector can match a message at any depth, not just an initial window. */
    public boolean selectorReachesAllMessages() {
        return has(SELECTOR_REACHES_ALL_MESSAGES);
    }

    /** A queue manager name and channel are mandatory for this provider. */
    public boolean requiresQueueManager() {
        return has(REQUIRES_QUEUE_MANAGER);
    }

    /** A raw broker URL may be supplied instead of host/port. */
    public boolean supportsBrokerUrlOverride() {
        return has(SUPPORTS_BROKER_URL_OVERRIDE);
    }

    /** One message can be deleted on its own. False for Kafka, whose log is immutable. */
    public boolean supportsSingleMessageDelete() {
        return has(SUPPORTS_SINGLE_MESSAGE_DELETE);
    }

    /**
     * A send can say whether the provider writes its own header ahead of the body. IBM MQ only, where
     * that header is the MQRFH2 and its absence is what a native {@code MQGET} reader needs.
     */
    public boolean supportsTargetClient() {
        return has(SUPPORTS_TARGET_CLIENT);
    }

    /** Addressed by a comma-separated {@code host:port} bootstrap list rather than a single host/port. */
    public boolean usesBootstrapServers() {
        return has(USES_BOOTSTRAP_SERVERS);
    }
}
