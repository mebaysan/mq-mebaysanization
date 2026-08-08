package com.baysansoft.mqmanager.jms.provider;

import org.springframework.stereotype.Component;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.jms.BrokerUrls;
import com.baysansoft.mqmanager.jms.ConnectionFactoryBuilder;

import jakarta.jms.ConnectionFactory;

/**
 * Apache ActiveMQ Artemis, via the {@code artemis-jakarta-client} artifact.
 *
 * <p>The artifact choice matters more than anything in this class: {@code artemis-jms-client} is the
 * javax.jms build and ships the very same class names, so getting it wrong compiles cleanly and fails
 * only at runtime. {@code scripts/verify-portability.sh} guards it.
 *
 * <p>As in the Classic builder, the connection factory class is written out in full rather than imported,
 * because the two brokers' factories share a simple name.
 */
@Component
public class ArtemisConnectionFactoryBuilder implements ConnectionFactoryBuilder {

    @Override
    public Provider provider() {
        return Provider.ARTEMIS;
    }

    @Override
    public ConnectionFactory build(ConnectionProfile profile, String plainPassword) {
        // The one-argument constructor plus setters, never the (url, user, password) form: with a null
        // user that constructor silently falls back to DefaultConnectionProperties.DEFAULT_USER /
        // DEFAULT_PASSWORD, which are driven by system properties. A profile with no credentials would
        // then connect as somebody else entirely.
        org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory factory =
                new org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory(
                        BrokerUrls.artemis(profile));

        if (profile.getUsername() != null) {
            factory.setUser(profile.getUsername());
            factory.setPassword(plainPassword);
        }

        return factory;
    }
}
