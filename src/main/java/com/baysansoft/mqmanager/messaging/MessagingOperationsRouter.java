package com.baysansoft.mqmanager.messaging;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.messaging.model.BrowseResult;
import com.baysansoft.mqmanager.messaging.model.ConnectionTestResult;
import com.baysansoft.mqmanager.messaging.model.DeleteOutcome;
import com.baysansoft.mqmanager.messaging.model.DepthOutcome;
import com.baysansoft.mqmanager.messaging.model.DestinationListing;
import com.baysansoft.mqmanager.messaging.model.DestinationQuery;
import com.baysansoft.mqmanager.messaging.model.PurgeOutcome;
import com.baysansoft.mqmanager.messaging.model.QueueMessageView;

/**
 * Picks the implementation that can talk to a profile's broker, so nothing above this layer has to
 * know that three providers speak JMS and one does not.
 *
 * <p>Marked {@code @Primary} on purpose: the controllers inject {@link MessagingOperations} by
 * interface, so this is what they get, and they never branch on {@link Provider} themselves.
 *
 * <p>Like {@code ConnectionFactoryRegistry}, this refuses to start rather than letting a provider the
 * UI offers fail at click time.
 */
@Service
@Primary
public class MessagingOperationsRouter implements MessagingOperations {

    private final Map<Provider, MessagingOperations> implementations = new EnumMap<>(Provider.class);

    public MessagingOperationsRouter(List<ProviderMessagingOperations> discovered) {
        for (ProviderMessagingOperations implementation : discovered) {
            for (Provider provider : implementation.providers()) {
                MessagingOperations previous = implementations.put(provider, implementation);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Two MessagingOperations beans claim provider " + provider);
                }
            }
        }
        for (Provider provider : Provider.values()) {
            if (!implementations.containsKey(provider)) {
                throw new IllegalStateException(
                        "No MessagingOperations implementation registered for provider " + provider);
            }
        }
    }

    private MessagingOperations forProfile(ConnectionProfile profile) {
        return implementations.get(profile.getProvider());
    }

    @Override
    public ConnectionTestResult testConnection(ConnectionProfile profile) {
        return forProfile(profile).testConnection(profile);
    }

    @Override
    public ConnectionTestResult testConnection(ConnectionProfile profile, String plainPassword) {
        return forProfile(profile).testConnection(profile, plainPassword);
    }

    @Override
    public String send(ConnectionProfile profile, String queueName, String body,
            Map<String, String> properties, String key) {
        return forProfile(profile).send(profile, queueName, body, properties, key);
    }

    @Override
    public DestinationListing listDestinations(ConnectionProfile profile, DestinationQuery query) {
        return forProfile(profile).listDestinations(profile, query);
    }

    @Override
    public BrowseResult browse(ConnectionProfile profile, String queueName, int limit) {
        return forProfile(profile).browse(profile, queueName, limit);
    }

    @Override
    public QueueMessageView browseOne(ConnectionProfile profile, String queueName, String messageId) {
        return forProfile(profile).browseOne(profile, queueName, messageId);
    }

    @Override
    public PurgeOutcome purgeDetailed(ConnectionProfile profile, String queueName) {
        return forProfile(profile).purgeDetailed(profile, queueName);
    }

    @Override
    public DeleteOutcome deleteMessageDetailed(ConnectionProfile profile, String queueName,
            String messageId) {
        return forProfile(profile).deleteMessageDetailed(profile, queueName, messageId);
    }

    @Override
    public DepthOutcome depthDetailed(ConnectionProfile profile, String queueName) {
        return forProfile(profile).depthDetailed(profile, queueName);
    }
}
