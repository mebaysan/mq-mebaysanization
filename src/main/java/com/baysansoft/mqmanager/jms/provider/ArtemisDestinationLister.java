package com.baysansoft.mqmanager.jms.provider;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.DestinationKind;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.jms.ConnectionFactoryRegistry;
import com.baysansoft.mqmanager.jms.DestinationListRequest;
import com.baysansoft.mqmanager.jms.DestinationLister;
import com.baysansoft.mqmanager.messaging.model.DestinationEntry;
import com.baysansoft.mqmanager.messaging.model.DestinationListing;
import com.baysansoft.mqmanager.messaging.model.DestinationListings;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.api.jms.management.JMSManagementHelper;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.InvalidDestinationException;
import jakarta.jms.JMSException;
import jakarta.jms.JMSSecurityException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;

/**
 * Lists Artemis destinations by sending a management request to the broker's management address.
 *
 * <p>Two calls, because Artemis's model is addresses and queues rather than JMS queues and topics:
 * {@code getQueueNames} for the queues, then {@code getAddressNames} for everything else. An address
 * with a queue of the same name is reported as a queue; an address without one is reported as a topic.
 *
 * <p>Either call can be refused — the management address can be disabled, and a user can lack
 * permission to send to it. A refusal is reported as unavailable or partial, never thrown: the
 * connection is fine, and broker policy is an answer. A security failure on <em>connect</em> is a
 * different thing entirely and is still thrown, because that is a bad password.
 */
@Component
public class ArtemisDestinationLister implements DestinationLister {

    private static final String SOURCE = "the broker management address";

    /** Artemis's own scratch addresses. */
    private static final String INTERNAL_PREFIX = "$.artemis.internal";

    /**
     * The address the broker publishes its own lifecycle events on. Not something a user configured, so
     * it is marked rather than shown as an ordinary topic. (The management address is not in this list
     * because Artemis does not report it back at all.)
     */
    private static final String NOTIFICATIONS_ADDRESS = "activemq.notifications";

    private static final String MODEL_NOTE =
            "Artemis has addresses and queues, not JMS queues and topics. An address with a queue of "
                    + "the same name is shown here as a queue; an address without one is shown as a "
                    + "topic. Durable subscription queues on a multicast address are not listed "
                    + "separately.";

    private final ConnectionFactoryRegistry factories;
    private final MqManagerProperties properties;

    public ArtemisDestinationLister(ConnectionFactoryRegistry factories,
            MqManagerProperties properties) {
        this.factories = factories;
        this.properties = properties;
    }

    @Override
    public Provider provider() {
        return Provider.ARTEMIS;
    }

    @Override
    public DestinationListing list(DestinationListRequest request) throws JMSException {
        String managementAddress = properties.getDestinations().getManagementAddress();
        long timeoutMs = Math.max(1, request.timeout().toMillis());

        ConnectionFactory factory = factories.forProvider(Provider.ARTEMIS)
                .build(request.profile(), request.plainPassword());

        try (Connection connection = factory.createConnection()) {
            connection.start();
            try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Queue management = session.createQueue(managementAddress);

                // ONE temporary reply queue for both calls, and its name is remembered.
                //
                // Both are load-bearing. A management request needs somewhere to be answered, and that
                // reply queue is itself a queue on the broker while the listing runs — so the broker
                // reports it back to us, and the picker would show a UUID that disappears seconds
                // later. Sharing one makes there be exactly one such name to filter out.
                TemporaryQueue replyTo = session.createTemporaryQueue();
                String replyName = replyTo.getQueueName();
                try (MessageProducer producer = session.createProducer(management);
                        MessageConsumer consumer = session.createConsumer(replyTo)) {

                    Outcome queues = invoke(session, producer, consumer, replyTo, "getQueueNames",
                            timeoutMs);
                    if (!queues.succeeded()) {
                        return DestinationListing.unavailable(request.limit(), SOURCE, queues.reason(),
                                queues.note(managementAddress, timeoutMs));
                    }

                    Set<String> queueNames = new LinkedHashSet<>(queues.names());
                    List<DestinationEntry> found = new ArrayList<>();
                    for (String name : queueNames) {
                        if (!name.equals(replyName)) {
                            found.add(new DestinationEntry(name, DestinationKind.QUEUE,
                                    isInternal(name, managementAddress)));
                        }
                    }

                    // The second call is where graceful degradation earns its keep: queues are already
                    // in hand, so a refusal here narrows the answer rather than destroying it.
                    Outcome addresses = invoke(session, producer, consumer, replyTo, "getAddressNames",
                            timeoutMs);
                    if (!addresses.succeeded()) {
                        return DestinationListings.finish(found, request.kind(), request.prefix(),
                                        request.limit(), SOURCE, MODEL_NOTE)
                                .toPartial(addresses.reason(),
                                        "Queues were listed, but the broker would not return its "
                                                + "address names, so topics are missing from this "
                                                + "list. " + MODEL_NOTE);
                    }

                    for (String name : addresses.names()) {
                        if (!queueNames.contains(name) && !name.equals(replyName)) {
                            found.add(new DestinationEntry(name, DestinationKind.TOPIC,
                                    isInternal(name, managementAddress)));
                        }
                    }

                    return DestinationListings.finish(found, request.kind(), request.prefix(),
                            request.limit(), SOURCE, MODEL_NOTE);
                } finally {
                    deleteQuietly(replyTo);
                }
            }
        }
    }

    /**
     * One management operation, with every "the broker will not answer" case turned into an outcome
     * rather than an exception.
     */
    private static Outcome invoke(Session session, MessageProducer producer, MessageConsumer consumer,
            TemporaryQueue replyTo, String operation, long timeoutMs) throws JMSException {
        try {
            Message request = session.createMessage();
            JMSManagementHelper.putOperationInvocation(request, ResourceNames.BROKER, operation);
            request.setJMSReplyTo(replyTo);
            producer.send(request);

            Message reply = consumer.receive(timeoutMs);
            if (reply == null) {
                return Outcome.failed(DestinationListing.TIMED_OUT, operation, null);
            }
            if (!JMSManagementHelper.hasOperationSucceeded(reply)) {
                return Outcome.failed(DestinationListing.NOT_PERMITTED, operation,
                        brokerText(reply));
            }
            return Outcome.succeeded(names(reply));

        } catch (JMSSecurityException e) {
            // Sending to, or consuming a reply from, the management address was refused. Connecting
            // already succeeded, so this is authorisation and not a bad password.
            return Outcome.failed(DestinationListing.NOT_PERMITTED, operation, e.getMessage());
        } catch (InvalidDestinationException e) {
            // The management address is not there at all, on a broker that does not auto-create it.
            return Outcome.failed(DestinationListing.NOT_AVAILABLE, operation, e.getMessage());
        } catch (JMSException e) {
            // Anything else at the JMS level is a genuine connection failure and belongs to the caller.
            throw e;
        } catch (Exception e) {
            // JMSManagementHelper.getResult declares plain Exception. A malformed reply is the broker
            // declining in an unhelpful way, not a connection failure.
            return Outcome.failed(DestinationListing.NOT_AVAILABLE, operation, e.getMessage());
        }
    }

    /** A reply queue that will not delete must not mask the answer, or the failure, we already have. */
    private static void deleteQuietly(TemporaryQueue replyTo) {
        try {
            replyTo.delete();
        } catch (JMSException e) {
            // The connection is closing anyway; the broker reaps temporary queues with it.
        }
    }

    /**
     * The names out of a successful management reply.
     *
     * <p>Deliberately the untyped {@code getResult(Message)}. The typed
     * {@code getResult(reply, String[].class)} is the obvious call and it <em>throws</em>
     * {@code ArrayStoreException} here: Artemis decodes the JSON array into an {@code Object[]} and
     * then tries to store it into a {@code String[]}. The untyped form hands back that
     * {@code Object[]} directly, which is all this needs.
     */
    private static List<String> names(Message reply) throws Exception {
        Object result = JMSManagementHelper.getResult(reply);
        List<String> names = new ArrayList<>();
        if (result instanceof Object[] values) {
            for (Object value : values) {
                if (value != null) {
                    names.add(String.valueOf(value));
                }
            }
        }
        return names;
    }

    private static String brokerText(Message reply) {
        try {
            Object result = JMSManagementHelper.getResult(reply, String.class);
            return result == null ? null : String.valueOf(result);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isInternal(String name, String managementAddress) {
        return name != null
                && (name.equals(managementAddress)
                        || name.equals(NOTIFICATIONS_ADDRESS)
                        || name.startsWith(INTERNAL_PREFIX));
    }

    /** What one management call came back with, so the caller can decide between partial and nothing. */
    private record Outcome(boolean succeeded, List<String> names, String reason, String operation,
            String brokerMessage) {

        static Outcome succeeded(List<String> names) {
            return new Outcome(true, names, null, null, null);
        }

        static Outcome failed(String reason, String operation, String brokerMessage) {
            return new Outcome(false, List.of(), reason, operation, brokerMessage);
        }

        String note(String managementAddress, long timeoutMs) {
            String base;
            if (DestinationListing.TIMED_OUT.equals(reason)) {
                base = "No answer from '" + managementAddress + "' within " + timeoutMs + " ms. The "
                        + "broker may have its management address disabled, or this user may not be "
                        + "permitted to send to it.";
            } else if (DestinationListing.NOT_PERMITTED.equals(reason)) {
                base = "The broker refused " + operation + " on '" + managementAddress + "'. This user "
                        + "needs permission to send to the management address.";
            } else {
                base = "The broker's reply to " + operation + " on '" + managementAddress + "' could "
                        + "not be read.";
            }
            return brokerMessage == null || brokerMessage.isBlank() ? base : base + " " + brokerMessage;
        }
    }
}
