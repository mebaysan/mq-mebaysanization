package com.baysansoft.mqmanager.jms;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.messaging.model.TargetClient;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Queue;

/**
 * The only provider-specific seam in the application. Everything downstream — send, browse, purge,
 * delete, depth, test — is written once against {@code jakarta.jms} and never branches on provider.
 */
public interface ConnectionFactoryBuilder {

    Provider provider();

    /**
     * @param plainPassword already-decrypted password, or null when the profile has none. Builders
     *                      never touch the crypto layer; the service decrypts and passes it in.
     */
    ConnectionFactory build(ConnectionProfile profile, String plainPassword) throws JMSException;

    /**
     * Applies provider-specific settings to a destination before it is used.
     *
     * <p>Default is a no-op. IBM MQ overrides it to disable read-ahead, which otherwise <em>discards</em>
     * buffered messages when a consumer closes — that would make a purge count silently under-report.
     */
    default void tuneDestination(Queue queue) throws JMSException {
        // no-op for brokers that need no per-destination tuning
    }

    /**
     * Chooses whether the provider writes a header of its own ahead of the body, for one send.
     *
     * <p>Deliberately a second method rather than a parameter on {@link #tuneDestination(Queue)},
     * because the two have different scopes and conflating them would lose that. Read-ahead applies to
     * all seven destination paths; IBM MQ's {@code TARGCLIENT} is consulted only on a PUT, and a
     * message already on the queue is read back from its MQMD format regardless. Browse, browseOne,
     * depth, purge and delete must therefore never call this.
     *
     * <p>Named for what it does rather than for when it runs, so a future unrelated send-path tweak
     * cannot be smuggled inside it and quietly invalidate the paragraph above.
     *
     * <p>Called only when the caller actually asked for a target client, and only after
     * {@code JmsMessagingOperations} has confirmed the provider declares
     * {@link Provider.Capability#SUPPORTS_TARGET_CLIENT}. A builder without that capability is
     * therefore unreachable here and its no-op default never runs.
     */
    default void applyTargetClient(Queue queue, TargetClient targetClient) throws JMSException {
        // no-op for brokers with no header of their own to suppress
    }
}
