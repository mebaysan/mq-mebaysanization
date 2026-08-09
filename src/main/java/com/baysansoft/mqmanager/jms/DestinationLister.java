package com.baysansoft.mqmanager.jms;

import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.messaging.model.DestinationListing;

/**
 * How one provider is asked what destinations it has. The second provider-specific seam, sitting
 * beside {@link ConnectionFactoryBuilder} for the same reason: everything above it is written once.
 *
 * <p>Unlike that one, this seam is <strong>not all JMS</strong>. ActiveMQ Classic answers through
 * advisory topics on the JMS connection and Artemis through a JMS management request — but IBM MQ
 * answers over PCF, which is the base Java API and shares no type with {@code jakarta.jms}. That is
 * exactly why each implementation opens its own connection rather than being handed a {@code Session}:
 * a signature that fitted three of them would have to pass the fourth a null.
 */
public interface DestinationLister {

    Provider provider();

    /**
     * @throws Exception when the BROKER could not be reached at all — the caller translates it into the
     *                   standard error contract. A broker that answered but would not answer this must
     *                   be reported as {@link DestinationListing#unavailable}, never thrown
     */
    DestinationListing list(DestinationListRequest request) throws Exception;
}
