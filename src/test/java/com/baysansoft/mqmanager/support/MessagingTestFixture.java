package com.baysansoft.mqmanager.support;

import java.util.List;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.jms.ConnectionFactoryBuilder;
import com.baysansoft.mqmanager.jms.ConnectionFactoryRegistry;
import com.baysansoft.mqmanager.jms.DestinationListerRegistry;
import com.baysansoft.mqmanager.jms.JmsErrorTranslator;
import com.baysansoft.mqmanager.jms.JmsMessagingOperations;
import com.baysansoft.mqmanager.jms.MessageMapper;
import com.baysansoft.mqmanager.jms.provider.ActiveMqClassicConnectionFactoryBuilder;
import com.baysansoft.mqmanager.jms.provider.ActiveMqClassicDestinationLister;
import com.baysansoft.mqmanager.jms.provider.ArtemisConnectionFactoryBuilder;
import com.baysansoft.mqmanager.jms.provider.ArtemisDestinationLister;
import com.baysansoft.mqmanager.jms.provider.IbmMqConnectionFactoryBuilder;
import com.baysansoft.mqmanager.jms.provider.IbmMqDestinationLister;
import com.baysansoft.mqmanager.jms.provider.IbmMqDiagnostics;

/**
 * Builds the real production messaging stack without a Spring context, so integration tests exercise the
 * same code paths the application uses and start in milliseconds.
 */
public final class MessagingTestFixture {

    private MessagingTestFixture() {
    }

    public static JmsMessagingOperations operations(MqManagerProperties properties) {
        return new JmsMessagingOperations(
                registry(),
                listerRegistry(properties),
                profile -> null, // embedded brokers run without authentication
                new JmsErrorTranslator(new IbmMqDiagnostics()),
                new MessageMapper(),
                properties);
    }

    /** The three real builders, wired exactly as Spring wires them. */
    public static ConnectionFactoryRegistry registry() {
        List<ConnectionFactoryBuilder> builders = List.of(
                new ActiveMqClassicConnectionFactoryBuilder(),
                new ArtemisConnectionFactoryBuilder(),
                new IbmMqConnectionFactoryBuilder());
        return new ConnectionFactoryRegistry(builders);
    }

    /** The three real destination listers, so a listing test drives production code end to end. */
    public static DestinationListerRegistry listerRegistry(MqManagerProperties properties) {
        ConnectionFactoryRegistry factories = registry();
        return new DestinationListerRegistry(List.of(
                new ActiveMqClassicDestinationLister(factories, properties),
                new ArtemisDestinationLister(factories, properties),
                new IbmMqDestinationLister(properties)));
    }

    public static JmsMessagingOperations operations() {
        return operations(new MqManagerProperties());
    }

    /** A profile pointing at an already-running embedded broker, via its real listener URL. */
    public static ConnectionProfile profileFor(Provider provider, String brokerUrl) {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setId(1L);
        profile.setName("embedded-" + provider);
        profile.setProvider(provider);
        profile.setBrokerUrlOverride(brokerUrl);
        return profile;
    }
}
