package com.baysansoft.mqmanager.jms;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.messaging.ProviderMessagingOperations;
import com.baysansoft.mqmanager.messaging.model.BrowseResult;
import com.baysansoft.mqmanager.messaging.model.ConnectionTestResult;
import com.baysansoft.mqmanager.messaging.model.DeleteOutcome;
import com.baysansoft.mqmanager.messaging.model.DepthOutcome;
import com.baysansoft.mqmanager.messaging.model.DestinationListing;
import com.baysansoft.mqmanager.messaging.model.DestinationQuery;
import com.baysansoft.mqmanager.messaging.model.PurgeOutcome;
import com.baysansoft.mqmanager.messaging.model.QueueMessageView;
import com.baysansoft.mqmanager.web.MqOperationException;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

/**
 * One implementation of every queue operation, shared by the three providers that speak
 * {@code jakarta.jms}. Kafka is not one of them and is handled by its own implementation.
 *
 * <p>Nothing here branches on {@link Provider} to decide <em>how</em> to talk to a broker — that is the
 * job of the three {@link ConnectionFactoryBuilder}s. Provider capability flags are consulted only to
 * describe honestly what the result does and does not mean.
 *
 * <p>A fresh connection is opened per operation and closed with try-with-resources. This is a
 * human-driven admin tool; a pool would add failure modes for no benefit.
 */
@Service
public class JmsMessagingOperations implements ProviderMessagingOperations {

    private static final Set<Provider> PROVIDERS =
            EnumSet.of(Provider.ACTIVE_MQ, Provider.ARTEMIS, Provider.IBM_MQ);

    private static final Logger log = LoggerFactory.getLogger(JmsMessagingOperations.class);

    private final ConnectionFactoryRegistry registry;
    private final DestinationListerRegistry listers;
    private final BrokerPasswordResolver passwordResolver;
    private final JmsErrorTranslator errorTranslator;
    private final MessageMapper messageMapper;
    private final MqManagerProperties properties;

    public JmsMessagingOperations(ConnectionFactoryRegistry registry,
                                  DestinationListerRegistry listers,
                                  BrokerPasswordResolver passwordResolver,
                                  JmsErrorTranslator errorTranslator,
                                  MessageMapper messageMapper,
                                  MqManagerProperties properties) {
        this.registry = registry;
        this.listers = listers;
        this.passwordResolver = passwordResolver;
        this.errorTranslator = errorTranslator;
        this.messageMapper = messageMapper;
        this.properties = properties;
    }

    @Override
    public Set<Provider> providers() {
        return PROVIDERS;
    }

    @Override
    public ConnectionTestResult testConnection(ConnectionProfile profile) {
        return runConnectionTest(profile, () -> passwordResolver.resolve(profile));
    }

    @Override
    public ConnectionTestResult testConnection(ConnectionProfile profile, String plainPassword) {
        return runConnectionTest(profile, () -> plainPassword);
    }

    private ConnectionTestResult runConnectionTest(ConnectionProfile profile,
                                                   Supplier<String> password) {
        long startedAt = System.nanoTime();
        try {
            execute(profile, password, "connect", Session.AUTO_ACKNOWLEDGE, (session, builder) -> null);
            return ConnectionTestResult.ok(elapsedMs(startedAt),
                    "Connected to " + BrokerUrls.describe(profile) + ".");
        } catch (MqOperationException e) {
            // A failed test is a successful answer to the question the caller asked, so it comes back as
            // a 200 with success=false rather than as an error response.
            return ConnectionTestResult.failed(elapsedMs(startedAt), e.getCode(), e.getMessage());
        }
    }

    @Override
    public String send(ConnectionProfile profile, String queueName, String body,
                       Map<String, String> propertiesToSet, String key) {
        if (key != null) {
            // Rejected rather than dropped. A key decides the Kafka partition; silently discarding one
            // would look like it had been honoured.
            throw new MqOperationException("OPERATION_NOT_SUPPORTED", HttpStatus.BAD_REQUEST,
                    "A message key applies only to Kafka, where it selects the partition. "
                            + profile.getProvider().displayName() + " has no equivalent.");
        }
        return execute(profile, "send a message", Session.AUTO_ACKNOWLEDGE, (session, builder) -> {
            Queue queue = session.createQueue(queueName);
            builder.tuneDestination(queue);

            try (MessageProducer producer = session.createProducer(queue)) {
                TextMessage message = session.createTextMessage(body == null ? "" : body);
                if (propertiesToSet != null) {
                    for (Map.Entry<String, String> property : propertiesToSet.entrySet()) {
                        // setStringProperty, never setObjectProperty: what the user typed is text, and
                        // the value must round-trip as text when the message is browsed back.
                        message.setStringProperty(property.getKey(), property.getValue());
                    }
                }
                producer.send(message);
                logSend(queueName, body);
                return message.getJMSMessageID();
            }
        });
    }

    /**
     * Delegates to the provider's {@link DestinationLister}, which is the second provider-specific
     * seam and the reason this method still does not branch on {@link Provider}.
     *
     * <p>The exception handling is the whole contract in three lines: anything a lister throws is a
     * broker that could not be reached, so it is translated into the standard error response. A broker
     * that answered but declined has already returned a listing saying so, and never arrives here.
     */
    @Override
    public DestinationListing listDestinations(ConnectionProfile profile, DestinationQuery query) {
        MqManagerProperties.Destinations settings = properties.getDestinations();
        DestinationListRequest request = new DestinationListRequest(
                profile,
                passwordResolver.resolve(profile),
                query.kind(),
                query.prefix(),
                clampDestinationLimit(query.limit()),
                settings.getTimeout());
        try {
            return listers.forProvider(profile.getProvider()).list(request);
        } catch (MqOperationException e) {
            throw e;
        } catch (Exception e) {
            throw errorTranslator.translate(e, profile, "list destinations");
        }
    }

    private int clampDestinationLimit(int requested) {
        MqManagerProperties.Destinations settings = properties.getDestinations();
        if (requested <= 0) {
            return settings.getDefaultLimit();
        }
        return Math.min(requested, settings.getMaxLimit());
    }

    @Override
    public BrowseResult browse(ConnectionProfile profile, String queueName, int limit) {
        int effectiveLimit = clampLimit(limit);
        int previewBytes = properties.getBrowse().getPreviewBytes();

        return execute(profile, "browse the queue", Session.AUTO_ACKNOWLEDGE, (session, builder) -> {
            Queue queue = session.createQueue(queueName);
            builder.tuneDestination(queue);

            List<QueueMessageView> messages = new ArrayList<>();
            try (QueueBrowser browser = session.createBrowser(queue)) {
                Enumeration<?> enumeration = browser.getEnumeration();
                while (enumeration.hasMoreElements() && messages.size() < effectiveLimit) {
                    messages.add(messageMapper.toView((Message) enumeration.nextElement(), previewBytes));
                }
            }

            // Only claim what is knowable. Hitting our own limit definitely means there may be more;
            // beyond that the per-provider note explains what a browse can and cannot see.
            boolean truncated = messages.size() >= effectiveLimit;
            return new BrowseResult(messages, messages.size(), effectiveLimit, truncated,
                    browseNote(profile.getProvider()));
        });
    }

    @Override
    public QueueMessageView browseOne(ConnectionProfile profile, String queueName, String jmsMessageId) {
        String messageId = requireValidMessageId(jmsMessageId);
        int maxBodyBytes = properties.getBrowse().getMaxBodyBytes();
        int scanLimit = properties.getDelete().getDisambiguationLimit();

        return execute(profile, "read the message", Session.AUTO_ACKNOWLEDGE, (session, builder) -> {
            Queue queue = session.createQueue(queueName);
            builder.tuneDestination(queue);

            try (QueueBrowser browser = session.createBrowser(queue)) {
                Enumeration<?> enumeration = browser.getEnumeration();
                int scanned = 0;
                while (enumeration.hasMoreElements() && scanned < scanLimit) {
                    Message message = (Message) enumeration.nextElement();
                    scanned++;
                    if (messageId.equals(message.getJMSMessageID())) {
                        return messageMapper.toView(message, maxBodyBytes);
                    }
                }
            }
            throw new MqOperationException("MESSAGE_NOT_FOUND", HttpStatus.NOT_FOUND,
                    "That message is no longer on the queue, or is beyond the first " + scanLimit
                            + " messages this broker allows a browse to see.");
        });
    }

    @Override
    public DepthOutcome depthDetailed(ConnectionProfile profile, String queueName) {
        int ceiling = properties.getDepth().getCeiling();

        return execute(profile, "read the queue depth", Session.AUTO_ACKNOWLEDGE, (session, builder) -> {
            Queue queue = session.createQueue(queueName);
            builder.tuneDestination(queue);

            long count = 0;
            try (QueueBrowser browser = session.createBrowser(queue)) {
                Enumeration<?> enumeration = browser.getEnumeration();
                while (enumeration.hasMoreElements() && count < ceiling) {
                    enumeration.nextElement();
                    count++;
                }
            }

            boolean hitCeiling = count >= ceiling;
            // Zero is trustworthy on every provider: a browse cap can cut a long enumeration short, but
            // it cannot turn a queue that holds messages into an empty one. Reporting "0+" with a
            // truncation warning would be both wrong and alarming.
            boolean exact = count == 0
                    || (profile.getProvider().browseIsComplete() && !hitCeiling);
            String note;
            if (hitCeiling) {
                note = "Counting stopped at " + ceiling + " messages; the queue holds at least that many.";
            } else if (exact) {
                note = null;
            } else {
                note = browseNote(profile.getProvider());
            }
            return new DepthOutcome(count, exact, note);
        });
    }

    @Override
    public PurgeOutcome purgeDetailed(ConnectionProfile profile, String queueName) {
        int maxMessages = properties.getPurge().getMaxMessages();
        long deadline = System.nanoTime() + properties.getPurge().getMaxDuration().toNanos();
        long firstTimeout = properties.getPurge().getFirstReceiveTimeout().toMillis();
        long timeout = properties.getPurge().getReceiveTimeout().toMillis();

        // AUTO_ACKNOWLEDGE is load-bearing. It acknowledges each message the instant receive() returns,
        // so an interrupted loop destroys exactly what it counted. CLIENT_ACKNOWLEDGE would let Artemis
        // batch acknowledgements up to 1 MB, making the count a lie on abort; SESSION_TRANSACTED would
        // break on IBM MQ, whose MaxUncommittedMsgs defaults to 10 000.
        return execute(profile, "purge the queue", Session.AUTO_ACKNOWLEDGE, (session, builder) -> {
            Queue queue = session.createQueue(queueName);
            builder.tuneDestination(queue);

            long purged = 0;
            PurgeOutcome.StopReason stopReason = PurgeOutcome.StopReason.QUEUE_EMPTY;

            try (MessageConsumer consumer = session.createConsumer(queue)) {
                // Never receiveNoWait(): on ActiveMQ it returns only what is already buffered locally
                // and would stop early, under-reporting the purge.
                Message message = consumer.receive(firstTimeout);
                while (message != null) {
                    purged++;
                    if (purged >= maxMessages) {
                        stopReason = PurgeOutcome.StopReason.MESSAGE_CAP;
                        break;
                    }
                    if (System.nanoTime() > deadline) {
                        stopReason = PurgeOutcome.StopReason.TIME_CAP;
                        break;
                    }
                    message = consumer.receive(timeout);
                }
            }

            log.info("Purged {} message(s) from '{}' ({})", purged, queueName, stopReason);
            return PurgeOutcome.of(purged, stopReason);
        });
    }

    @Override
    public DeleteOutcome deleteMessageDetailed(ConnectionProfile profile, String queueName,
                                               String jmsMessageId) {
        String messageId = requireValidMessageId(jmsMessageId);
        // JMS selectors are SQL-92: a literal quote is escaped by doubling it.
        String selector = "JMSMessageID='" + messageId.replace("'", "''") + "'";
        long receiveTimeout = properties.getDelete().getReceiveTimeout().toMillis();

        return execute(profile, "delete the message", Session.SESSION_TRANSACTED, (session, builder) -> {
            Queue queue = session.createQueue(queueName);
            builder.tuneDestination(queue);

            try (MessageConsumer consumer = session.createConsumer(queue, selector)) {
                Message received = consumer.receive(receiveTimeout);

                if (received != null) {
                    if (!messageId.equals(received.getJMSMessageID())) {
                        // Should be unreachable, but a consume is destructive and irreversible. IBM MQ's
                        // all-zero message id is a documented wildcard that matches ANY message, so a
                        // mismatch here is exactly the case where committing would destroy the wrong one.
                        session.rollback();
                        throw new MqOperationException("MESSAGE_ID_MISMATCH", HttpStatus.CONFLICT,
                                "The broker returned a different message than the one requested; "
                                        + "nothing was deleted.");
                    }
                    session.commit();
                    return DeleteOutcome.DELETED;
                }

                session.rollback();
            }

            // A null receive is ambiguous: the message may be gone, or it may be sitting on the queue
            // beyond the window a selector-based consumer can reach (ActiveMQ Classic stalls past
            // maxPageSize). Telling those apart is the difference between "already deleted" and a lie.
            return disambiguate(session, queueName, messageId, builder);
        });
    }

    /**
     * Works out why a selector consume found nothing, using a bounded non-destructive browse.
     *
     * @return {@link DeleteOutcome#NOT_FOUND} only when the message is provably absent
     */
    private DeleteOutcome disambiguate(Session session, String queueName, String messageId,
                                       ConnectionFactoryBuilder builder) throws JMSException {
        int scanLimit = properties.getDelete().getDisambiguationLimit();
        Queue queue = session.createQueue(queueName);
        builder.tuneDestination(queue);

        boolean scanTruncated = false;
        try (QueueBrowser browser = session.createBrowser(queue)) {
            Enumeration<?> enumeration = browser.getEnumeration();
            int scanned = 0;
            while (enumeration.hasMoreElements()) {
                if (scanned >= scanLimit) {
                    scanTruncated = true;
                    break;
                }
                Message candidate = (Message) enumeration.nextElement();
                scanned++;
                if (messageId.equals(candidate.getJMSMessageID())) {
                    throw new MqOperationException("MESSAGE_UNREACHABLE", HttpStatus.CONFLICT,
                            "The message is still on the queue but this broker will not deliver it to a "
                                    + "selector past its paging window, so it cannot be deleted "
                                    + "individually. Purging the queue, or consuming the messages ahead "
                                    + "of it, are the available options.");
                }
            }
        }

        if (scanTruncated) {
            throw new MqOperationException("MESSAGE_NOT_LOCATABLE", HttpStatus.CONFLICT,
                    "The message was not delivered by a selector and could not be located within the "
                            + "first " + scanLimit + " messages, so whether it is still on the queue "
                            + "cannot be confirmed. Nothing was deleted.");
        }
        return DeleteOutcome.NOT_FOUND;
    }

    /**
     * Rejects ids that must never reach a selector.
     *
     * <p>The all-zeros form is the important one: on IBM MQ
     * {@code JMSMessageID='ID:000...0'} is a documented <em>wildcard</em> that matches any message on the
     * queue and returns it. Any bug producing a default or padded id would otherwise delete an arbitrary
     * production message.
     */
    private static String requireValidMessageId(String jmsMessageId) {
        String messageId = jmsMessageId == null ? "" : jmsMessageId.trim();

        if (messageId.length() <= 3 || !messageId.startsWith("ID:")) {
            throw new MqOperationException("MESSAGE_ID_INVALID", HttpStatus.BAD_REQUEST,
                    "A JMS message id is required and must start with 'ID:'.");
        }
        if (messageId.chars().skip(3).allMatch(character -> character == '0')) {
            throw new MqOperationException("MESSAGE_ID_INVALID", HttpStatus.BAD_REQUEST,
                    "That message id is the IBM MQ wildcard, which matches an arbitrary message on the "
                            + "queue. Refusing to use it.");
        }
        return messageId;
    }

    private int clampLimit(int requested) {
        MqManagerProperties.Browse browse = properties.getBrowse();
        if (requested <= 0) {
            return browse.getDefaultLimit();
        }
        return Math.min(requested, browse.getMaxLimit());
    }

    private static String browseNote(Provider provider) {
        return switch (provider) {
            case ACTIVE_MQ -> "ActiveMQ Classic limits how many messages a browser may see "
                    + "(maxBrowsePageSize, 400 by default). The queue may hold more than is shown, and "
                    + "that cannot be detected from here.";
            case ARTEMIS -> "Artemis browsers scan the whole queue, including paged messages, so this is "
                    + "complete as of the moment of the scan.";
            case IBM_MQ -> "Uncommitted messages are never visible to a browse, and on a priority-ordered "
                    + "queue higher-priority messages arriving during the scan are not shown.";
            // Left as an explicit case rather than a default: this switch must keep failing to compile
            // when a provider is added, so nobody gets a silently wrong note.
            case KAFKA -> throw new IllegalStateException(
                    "Kafka is not routed through the JMS implementation");
        };
    }

    /**
     * Opens a connection, runs the operation, and closes everything innermost-first.
     *
     * <p>{@code connection.start()} is called before anything reads. It is required before
     * <em>browsing</em>, not just before receiving: ActiveMQ's browser short-circuits when the session is
     * not running and returns zero messages with no error at all, which reads as "the queue is empty".
     */
    private <T> T execute(ConnectionProfile profile, String action, int acknowledgeMode,
                          JmsOperation<T> operation) {
        return execute(profile, () -> passwordResolver.resolve(profile), action, acknowledgeMode,
                operation);
    }

    private <T> T execute(ConnectionProfile profile, Supplier<String> password, String action,
                          int acknowledgeMode, JmsOperation<T> operation) {
        ConnectionFactoryBuilder builder = registry.forProvider(profile.getProvider());

        try {
            ConnectionFactory factory = builder.build(profile, password.get());
            try (Connection connection = factory.createConnection()) {
                connection.start();
                try (Session session = connection.createSession(
                        acknowledgeMode == Session.SESSION_TRANSACTED, acknowledgeMode)) {
                    return operation.run(session, builder);
                }
            }
        } catch (MqOperationException e) {
            throw e;
        } catch (JMSException | RuntimeException e) {
            throw errorTranslator.translate(e, profile, action);
        }
    }

    /** INFO records that a send happened and how big it was; the body itself needs an explicit opt-in. */
    private void logSend(String queueName, String body) {
        log.info("Sent a message to '{}' ({} characters)", queueName, body == null ? 0 : body.length());
        if (properties.isLogPayloads() && log.isDebugEnabled()) {
            log.debug("Payload sent to '{}': {}", queueName, body);
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    @FunctionalInterface
    private interface JmsOperation<T> {
        T run(Session session, ConnectionFactoryBuilder builder) throws JMSException;
    }
}
