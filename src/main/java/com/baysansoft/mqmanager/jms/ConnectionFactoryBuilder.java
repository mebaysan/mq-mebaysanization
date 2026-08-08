package com.baysansoft.mqmanager.jms;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;

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
}
