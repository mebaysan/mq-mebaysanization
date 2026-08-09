package com.baysansoft.mqmanager.jms;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.baysansoft.mqmanager.domain.Provider;

/**
 * Resolves the one destination lister for a profile. Fails loudly if a JMS provider is unwired.
 *
 * <p>Deliberately the same shape as {@link ConnectionFactoryRegistry}, including the completeness
 * check: a provider the UI offers a Browse button for must not discover at click time that nobody
 * implemented it. Kafka is excluded for the same reason it is there — it has no {@code jakarta.jms}
 * API, so {@code KafkaMessagingOperations} lists topics itself with its own admin client.
 */
@Component
public class DestinationListerRegistry {

    private final Map<Provider, DestinationLister> listers = new EnumMap<>(Provider.class);

    public DestinationListerRegistry(List<DestinationLister> discovered) {
        for (DestinationLister lister : discovered) {
            DestinationLister previous = listers.put(lister.provider(), lister);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two DestinationLister beans claim provider " + lister.provider());
            }
        }
        for (Provider provider : Provider.values()) {
            if (provider.isJms() && !listers.containsKey(provider)) {
                throw new IllegalStateException(
                        "No DestinationLister registered for provider " + provider);
            }
        }
    }

    public DestinationLister forProvider(Provider provider) {
        return listers.get(provider);
    }
}
