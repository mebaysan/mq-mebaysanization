package com.baysansoft.mqmanager.messaging.model;

import java.util.List;

/**
 * A page of browsed messages.
 *
 * @param truncated    true when the result may not be everything on the queue — either our own limit was
 *                     reached, or the broker stopped the enumeration early
 * @param providerNote plain-language explanation of what this provider's browse can and cannot see
 */
public record BrowseResult(
        List<QueueMessageView> messages,
        int returned,
        int limit,
        boolean truncated,
        String providerNote) {
}
