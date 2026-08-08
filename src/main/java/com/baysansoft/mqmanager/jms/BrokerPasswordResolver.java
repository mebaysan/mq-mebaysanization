package com.baysansoft.mqmanager.jms;

import com.baysansoft.mqmanager.domain.ConnectionProfile;

/**
 * Supplies the decrypted password for a profile.
 *
 * <p>Exists so the messaging layer never depends on the persistence or crypto layers — it receives a
 * password and knows nothing about where it was stored or how.
 */
@FunctionalInterface
public interface BrokerPasswordResolver {

    String resolve(ConnectionProfile profile);
}
