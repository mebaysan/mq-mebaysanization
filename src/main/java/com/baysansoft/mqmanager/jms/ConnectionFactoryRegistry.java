package com.baysansoft.mqmanager.jms;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.baysansoft.mqmanager.domain.Provider;

/** Resolves the one provider-specific builder for a profile. Fails loudly if a provider is unwired. */
@Component
public class ConnectionFactoryRegistry {

    private final Map<Provider, ConnectionFactoryBuilder> builders = new EnumMap<>(Provider.class);

    public ConnectionFactoryRegistry(List<ConnectionFactoryBuilder> discovered) {
        for (ConnectionFactoryBuilder builder : discovered) {
            ConnectionFactoryBuilder previous = builders.put(builder.provider(), builder);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two ConnectionFactoryBuilder beans claim provider " + builder.provider());
            }
        }
        // Better to refuse to start than to let a provider the UI offers fail at click time.
        // Scoped to the JMS providers: Kafka has no jakarta.jms API and therefore no connection factory,
        // and is wired into MessagingOperationsRouter instead — which applies the same rule over there.
        for (Provider provider : Provider.values()) {
            if (provider.isJms() && !builders.containsKey(provider)) {
                throw new IllegalStateException(
                        "No ConnectionFactoryBuilder registered for provider " + provider);
            }
        }
    }

    public ConnectionFactoryBuilder forProvider(Provider provider) {
        return builders.get(provider);
    }
}
