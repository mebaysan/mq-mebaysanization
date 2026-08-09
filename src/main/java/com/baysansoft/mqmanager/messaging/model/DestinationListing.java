package com.baysansoft.mqmanager.messaging.model;

import java.util.List;

/**
 * What destinations a broker will admit to having, and how much of that answer to trust.
 *
 * <p>Every one of the four mechanisms can be switched off or refused on a hardened broker: ActiveMQ
 * Classic can run without advisories, Artemis can deny its management address, IBM MQ's command server
 * can be stopped, and Kafka can withhold Describe on the cluster. None of those is a fault in this
 * application or in the saved connection, so none is an error response — but neither may any of them
 * come back as an empty list, which would read as "this broker has no queues".
 *
 * <p><strong>The rule for an implementor.</strong> A failure to reach the broker at all is thrown, and
 * the caller translates it into the standard error contract. A broker that answered, but would not
 * answer <em>this</em>, is {@link Availability#UNAVAILABLE}. Never the other way round.
 *
 * <p>The corollary the UI depends on: an empty {@code destinations} list means "there is genuinely
 * nothing here" <em>only</em> when {@code availability == COMPLETE}. Branch on availability first.
 *
 * @param truncated true when OUR OWN cap cut the list, never when the broker stopped short
 * @param reason    stable machine-readable code, null only when {@code availability == COMPLETE}
 * @param source    plain-language name of the mechanism used, e.g. "advisory topics"
 * @param note      provider-specific explanation, for the quiet place in the UI
 */
public record DestinationListing(
        List<DestinationEntry> destinations,
        int returned,
        int limit,
        boolean truncated,
        Availability availability,
        String reason,
        String source,
        String note) {

    public enum Availability {
        /** Everything the broker has — or everything up to our cap, with {@code truncated} set. */
        COMPLETE,
        /** Some of it. A follow-up call failed, or our cap cut it. What is here is real. */
        PARTIAL,
        /** Nothing. The list is empty because we could not ask, not because there is nothing. */
        UNAVAILABLE
    }

    /** The broker refused: no permission, or the mechanism is disabled. */
    public static final String NOT_PERMITTED = "DESTINATION_LIST_NOT_PERMITTED";
    /** The mechanism is not there to be used at all on this broker. */
    public static final String NOT_AVAILABLE = "DESTINATION_LIST_NOT_AVAILABLE";
    /** No answer inside the budget. On IBM MQ this usually means the command server is stopped. */
    public static final String TIMED_OUT = "DESTINATION_LIST_TIMED_OUT";
    /** Our own limit cut the list. The only reason the user can do something about from the UI. */
    public static final String CAPPED = "DESTINATION_LIST_CAPPED";
    /** ActiveMQ Classic only: an empty advisory answer and a broker with no queues look identical. */
    public static final String INDISTINGUISHABLE = "DESTINATION_LIST_INDISTINGUISHABLE";

    public static DestinationListing complete(List<DestinationEntry> found, int limit, String source,
            String note) {
        return new DestinationListing(List.copyOf(found), found.size(), limit, false,
                Availability.COMPLETE, null, source, note);
    }

    /** Our cap cut it, which is a partial answer the user can widen with a prefix. */
    public static DestinationListing capped(List<DestinationEntry> found, int limit, String source,
            String note) {
        return new DestinationListing(List.copyOf(found), found.size(), limit, true,
                Availability.PARTIAL, CAPPED, source, note);
    }

    /** Some of it, for a provider-specific reason — one of two calls succeeded, typically. */
    public static DestinationListing partial(List<DestinationEntry> found, int limit, String source,
            String reason, String note) {
        return new DestinationListing(List.copyOf(found), found.size(), limit, false,
                Availability.PARTIAL, reason, source, note);
    }

    /** Always an empty list: this is the shape that says "do not read anything into the emptiness". */
    public static DestinationListing unavailable(int limit, String source, String reason, String note) {
        return new DestinationListing(List.of(), 0, limit, false, Availability.UNAVAILABLE, reason,
                source, note);
    }

    /**
     * Downgrades a finished listing to {@link Availability#PARTIAL} because a follow-up call failed.
     * What was already collected is real and is kept.
     *
     * <p>When our own cap had already cut the list, {@link #CAPPED} stays as the reason: it is the one
     * the user can act on from the UI by narrowing with a prefix. The note carries the rest either way.
     */
    public DestinationListing toPartial(String reason, String note) {
        return new DestinationListing(destinations, returned, limit, truncated, Availability.PARTIAL,
                truncated ? CAPPED : reason, source, note);
    }
}
