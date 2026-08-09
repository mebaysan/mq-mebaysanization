package com.baysansoft.mqmanager.jms;

import java.time.Duration;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.DestinationKind;

/**
 * One listing call, with everything a lister needs and nothing it does not.
 *
 * @param plainPassword already decrypted, or null when the profile has none. Listers never touch the
 *                      crypto layer, exactly like the connection-factory builders
 * @param kind          null for both kinds
 * @param prefix        a name prefix, or null. See {@code DestinationQuery#prefix()}
 * @param limit         already clamped against the configured maximum
 * @param timeout       overall wall-clock budget for this call. A human is waiting on an HTTP request,
 *                      so no lister may block past it
 */
public record DestinationListRequest(
        ConnectionProfile profile,
        String plainPassword,
        DestinationKind kind,
        String prefix,
        int limit,
        Duration timeout) {
}
