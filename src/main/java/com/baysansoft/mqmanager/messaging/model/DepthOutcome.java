package com.baysansoft.mqmanager.messaging.model;

/**
 * A queue depth derived from browsing and counting, which is the only portable option: {@code jakarta.jms}
 * has no depth API and none of the three providers adds one to its JMS destination.
 *
 * @param exact false when the number is a floor rather than the truth — either the provider caps how
 *              many messages a browser may see, or our own ceiling was reached. The UI renders an
 *              inexact depth as "N+", never as "N".
 */
public record DepthOutcome(long count, boolean exact, String note) {
}
