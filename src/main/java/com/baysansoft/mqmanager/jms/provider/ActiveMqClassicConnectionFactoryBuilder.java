package com.baysansoft.mqmanager.jms.provider;

import org.springframework.stereotype.Component;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.jms.BrokerUrls;
import com.baysansoft.mqmanager.jms.ConnectionFactoryBuilder;

import jakarta.jms.ConnectionFactory;

/**
 * Apache ActiveMQ Classic 6.x, whose main {@code activemq-client} artifact is natively Jakarta.
 *
 * <p>Note that {@code org.apache.activemq.ActiveMQConnectionFactory} is written out in full below and
 * never imported. Artemis ships a class with the identical simple name
 * ({@code org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory}) and both are on the compile
 * classpath, so an import here would be a coin flip that compiles either way. {@code ImportGuardTest}
 * fails the build if anyone adds one.
 */
@Component
public class ActiveMqClassicConnectionFactoryBuilder implements ConnectionFactoryBuilder {

    @Override
    public Provider provider() {
        return Provider.ACTIVE_MQ;
    }

    @Override
    public ConnectionFactory build(ConnectionProfile profile, String plainPassword) {
        org.apache.activemq.ActiveMQConnectionFactory factory =
                new org.apache.activemq.ActiveMQConnectionFactory();

        factory.setBrokerURL(BrokerUrls.activeMqClassic(profile));

        if (profile.getUsername() != null) {
            factory.setUserName(profile.getUsername());
            factory.setPassword(plainPassword);
        }

        // An admin tool must never deserialize a payload it was merely asked to display. Classic refuses
        // untrusted packages by default; this makes the intent explicit and permanent, and MessageMapper
        // renders ObjectMessage as type plus headers rather than calling getObject().
        factory.setTrustAllPackages(false);

        return factory;
    }
}
