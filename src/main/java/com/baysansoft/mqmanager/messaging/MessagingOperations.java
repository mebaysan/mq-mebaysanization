package com.baysansoft.mqmanager.messaging;

import java.util.Map;

import org.springframework.http.HttpStatus;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.messaging.model.BrowseResult;
import com.baysansoft.mqmanager.messaging.model.ConnectionTestResult;
import com.baysansoft.mqmanager.messaging.model.CreateTopicCommand;
import com.baysansoft.mqmanager.messaging.model.CreateTopicOutcome;
import com.baysansoft.mqmanager.messaging.model.DeleteOutcome;
import com.baysansoft.mqmanager.messaging.model.DepthOutcome;
import com.baysansoft.mqmanager.messaging.model.DestinationListing;
import com.baysansoft.mqmanager.messaging.model.DestinationQuery;
import com.baysansoft.mqmanager.messaging.model.OutboundMessage;
import com.baysansoft.mqmanager.messaging.model.PurgeOutcome;
import com.baysansoft.mqmanager.messaging.model.QueueMessageView;
import com.baysansoft.mqmanager.web.MqOperationException;

/**
 * Every queue operation the tool performs, written once and shared by every provider.
 *
 * <p>Deliberately protocol-neutral: it names a destination and a message id, not a
 * {@code jakarta.jms.Queue} or a {@code JMSMessageID}. Three providers reach it through
 * {@code JmsMessagingOperations}; Kafka, which has no JMS API at all, implements it directly. That is
 * the whole reason this type does not live in the {@code jms} package.
 *
 * <p>The plain {@code long}/{@code boolean} forms are kept as defaults for callers that genuinely only
 * want the number. The detailed forms exist because the simple ones cannot express the truth: a purge
 * that stopped at its time cap returns the same {@code long} as one that emptied the queue, and a depth
 * derived from browsing may be a floor rather than a count. The REST layer always uses the detailed forms.
 *
 * <p>Not every provider can do everything. An operation a broker genuinely cannot perform throws
 * {@code MqOperationException} with code {@code OPERATION_NOT_SUPPORTED} — it never quietly succeeds
 * and never returns a value that reads like success.
 */
public interface MessagingOperations {

    /** Tests a saved profile, decrypting its stored password. */
    ConnectionTestResult testConnection(ConnectionProfile profile);

    /**
     * Tests an unsaved draft with an explicitly supplied password, since there is nothing stored to
     * look up.
     */
    ConnectionTestResult testConnection(ConnectionProfile profile, String plainPassword);

    /**
     * @return the id assigned by the broker: a {@code JMSMessageID} for the JMS providers, or
     *         {@code topic-partition-offset} for Kafka
     */
    String send(ConnectionProfile profile, String queueName, OutboundMessage message);

    /**
     * Sends a text message with no key, which is the only form every provider supports unconditionally.
     *
     * <p>There is deliberately no overload that takes a key but not a message type. It would have to
     * pick a message type on the caller's behalf, and picking one silently is the exact failure this
     * type exists to make impossible.
     */
    default String send(ConnectionProfile profile, String queueName, String body,
            Map<String, String> properties) {
        return send(profile, queueName, OutboundMessage.text(body, properties));
    }

    /**
     * What destinations the broker has.
     *
     * <p>No default: every provider can answer this in some form, and a provider that quietly inherited
     * "not supported" would be a Browse button that fails at click time. A broker that <em>will not</em>
     * answer is reported inside {@link DestinationListing}, not thrown — see that type for the rule.
     */
    DestinationListing listDestinations(ConnectionProfile profile, DestinationQuery query);

    BrowseResult browse(ConnectionProfile profile, String queueName, int limit);

    /** Non-destructive fetch of one message, used to show a full body the list view had to truncate. */
    QueueMessageView browseOne(ConnectionProfile profile, String queueName, String messageId);

    PurgeOutcome purgeDetailed(ConnectionProfile profile, String queueName);

    DeleteOutcome deleteMessageDetailed(ConnectionProfile profile, String queueName, String messageId);

    DepthOutcome depthDetailed(ConnectionProfile profile, String queueName);

    /**
     * Creates a destination as an explicit administrative operation.
     *
     * <p>A default that refuses, not an abstract method, on purpose: only Kafka has an admin API that
     * creates a topic on request, and the three JMS providers should stay exactly as they are rather
     * than grow a method they cannot implement. The JMS brokers create a queue silently on first send
     * (ActiveMQ, Artemis) or not at all (IBM MQ) — neither is this operation, and a caller that reaches
     * one here gets an honest refusal instead of a create that quietly did nothing. Kafka overrides it.
     */
    default CreateTopicOutcome createTopic(ConnectionProfile profile, CreateTopicCommand command) {
        throw new MqOperationException("OPERATION_NOT_SUPPORTED", HttpStatus.NOT_IMPLEMENTED,
                "Creating a destination on request is a Kafka operation. The JMS providers either create "
                        + "a queue on first use or not at all, so there is nothing here to create "
                        + "explicitly.");
    }

    default long purge(ConnectionProfile profile, String queueName) {
        return purgeDetailed(profile, queueName).purged();
    }

    default boolean deleteMessage(ConnectionProfile profile, String queueName, String messageId) {
        return deleteMessageDetailed(profile, queueName, messageId) == DeleteOutcome.DELETED;
    }

    default long depth(ConnectionProfile profile, String queueName) {
        return depthDetailed(profile, queueName).count();
    }
}
