package com.baysansoft.mqmanager.jms.provider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
import org.apache.activemq.advisory.DestinationSource;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;

/**
 * Lists ActiveMQ Classic destinations from the broker's advisory topics.
 *
 * <p>Advisories ride the TCP connection this tool already has. The obvious alternative, JMX, was
 * rejected: it needs a JMX/RMI port that is usually closed, a second set of credentials, and RMI is a
 * deserialization attack surface in a build with no authentication.
 *
 * <p>The awkward part is that advisories arrive <em>asynchronously</em> and there is no completion
 * signal — the broker replays what it has and then simply stops. So the scan waits for the count to
 * stop growing, and an empty answer is reported as {@link DestinationListing#unavailable} rather than
 * as "this broker has no queues": with {@code advisorySupport=false} the two look identical from a
 * client, and guessing between them would be exactly the kind of lie the rest of this codebase avoids.
 */
@Component
public class ActiveMqClassicDestinationLister implements DestinationLister {

    /**
     * Names the broker owns.
     *
     * <p>Belt and braces: ActiveMQ does not publish advisories <em>about</em> advisory destinations, so
     * in practice they never reach this class and the flag never fires. It stays because the cost is a
     * string comparison and the alternative is a listing that shows the plumbing if that ever changes.
     */
    private static final String ADVISORY_PREFIX = "ActiveMQ.Advisory.";

    private static final String SOURCE = "advisory topics";

    private final ConnectionFactoryRegistry factories;
    private final MqManagerProperties properties;

    public ActiveMqClassicDestinationLister(ConnectionFactoryRegistry factories,
            MqManagerProperties properties) {
        this.factories = factories;
        this.properties = properties;
    }

    @Override
    public Provider provider() {
        return Provider.ACTIVE_MQ;
    }

    @Override
    public DestinationListing list(DestinationListRequest request) throws JMSException {
        ConnectionFactory factory = factories.forProvider(Provider.ACTIVE_MQ)
                .build(request.profile(), request.plainPassword());

        List<DestinationEntry> found = new ArrayList<>();
        try (Connection connection = factory.createConnection()) {
            connection.start();

            // Constructed rather than taken from connection.getDestinationSource(): that accessor needs
            // the concrete org.apache.activemq.ActiveMQConnection — a simple name Artemis also uses, and
            // one ImportGuardTest refuses — and it caches a started source on the connection with no
            // deterministic way to stop it. This constructor takes the jakarta.jms.Connection interface
            // and the source never casts it, so no ambiguous type is named here at all.
            DestinationSource source = new DestinationSource(connection);
            source.start();
            try {
                settle(source, request.timeout());
                collect(source, found);
            } finally {
                source.stop();
            }
        }

        if (found.isEmpty()) {
            long settleMs = settleBudget(request.timeout()).toMillis();
            return DestinationListing.unavailable(request.limit(), SOURCE,
                    DestinationListing.INDISTINGUISHABLE,
                    "ActiveMQ Classic reports its destinations on advisory topics. Nothing arrived "
                            + "within " + settleMs + " ms, and from a client that is indistinguishable "
                            + "between a broker with no destinations and one running with "
                            + "advisorySupport=false. Type a name instead — everything else still works.");
        }

        return DestinationListings.finish(found, request.kind(), request.prefix(), request.limit(),
                SOURCE, "Advisories are a snapshot of what arrived while this page asked. A broker with "
                        + "very many destinations, or a slow link, may need a longer "
                        + "mqmanager.destinations.advisory-settle.");
    }

    /**
     * Waits until the destination count stops growing, or the budget runs out.
     *
     * <p>There is no "that is all of them" message to wait for, so quiescence is the only signal
     * available. Two consecutive unchanged polls, not one, because the first advisory can arrive a beat
     * after the subscription is established.
     */
    private void settle(DestinationSource source, Duration timeout) {
        Duration quiet = properties.getDestinations().getAdvisoryQuietPeriod();
        long deadline = System.nanoTime() + settleBudget(timeout).toNanos();
        int previous = -1;
        int unchanged = 0;

        while (System.nanoTime() < deadline && unchanged < 2) {
            try {
                Thread.sleep(Math.max(1, quiet.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            int current = source.getQueues().size() + source.getTopics().size();
            unchanged = current == previous ? unchanged + 1 : 0;
            previous = current;
        }
    }

    /** The settle window never outlives the caller's overall budget. */
    private Duration settleBudget(Duration timeout) {
        Duration configured = properties.getDestinations().getAdvisorySettle();
        return configured.compareTo(timeout) <= 0 ? configured : timeout;
    }

    /**
     * Temporary destinations are deliberately not collected: {@code DestinationSource} keeps them in
     * separate accessors, and they are per-connection scratch space rather than anything a user
     * configured.
     */
    private static void collect(DestinationSource source, List<DestinationEntry> found)
            throws JMSException {
        // Iterated as the jakarta.jms interfaces. The element types are ActiveMQQueue/ActiveMQTopic,
        // whose simple names Artemis also uses, so they are never written down.
        for (jakarta.jms.Queue queue : source.getQueues()) {
            String name = queue.getQueueName();
            found.add(new DestinationEntry(name, DestinationKind.QUEUE, isInternal(name)));
        }
        for (jakarta.jms.Topic topic : source.getTopics()) {
            String name = topic.getTopicName();
            found.add(new DestinationEntry(name, DestinationKind.TOPIC, isInternal(name)));
        }
    }

    static boolean isInternal(String name) {
        return name != null && name.startsWith(ADVISORY_PREFIX);
    }
}
