package com.baysansoft.mqmanager.web.dto;

import java.util.List;

import com.baysansoft.mqmanager.messaging.model.CreateTopicOutcome;
import com.baysansoft.mqmanager.messaging.model.DestinationEntry;
import com.baysansoft.mqmanager.messaging.model.DestinationListing;

/** Response shapes for the destination-listing endpoint. */
public final class DestinationResponses {

    private DestinationResponses() {
    }

    /**
     * A listing, plus one plain sentence saying what happened.
     *
     * <p>The sentence is built here rather than in the UI so every client says the same thing, and it
     * is kept separate from {@code note} for the same reason {@code PurgeResponse} keeps them apart:
     * one is the headline, the other is the provider-specific small print.
     *
     * <p><strong>{@code availability} is the field to branch on, never {@code destinations.length}.</strong>
     * An empty list means "there is nothing here" only when this reads {@code COMPLETE}.
     *
     * @param reason a stable {@code DESTINATION_LIST_*} code. This is a BODY code on a 200 response,
     *               not an {@code ApiError} code — the request succeeded
     */
    public record DestinationListResponse(List<DestinationEntry> destinations, int returned, int limit,
            boolean truncated, String availability, String reason, String source, String message,
            String note) {

        public static DestinationListResponse from(DestinationListing listing) {
            return new DestinationListResponse(
                    listing.destinations(),
                    listing.returned(),
                    listing.limit(),
                    listing.truncated(),
                    listing.availability().name(),
                    listing.reason(),
                    listing.source(),
                    messageFor(listing),
                    listing.note());
        }

        private static String messageFor(DestinationListing listing) {
            return switch (listing.availability()) {
                case COMPLETE -> listing.returned() == 0
                        // Only sayable at COMPLETE. Any other availability and this would be a guess.
                        ? "This broker reported no destinations."
                        : "Found " + listing.returned() + " destination(s) via " + listing.source() + ".";
                case PARTIAL -> DestinationListing.CAPPED.equals(listing.reason())
                        ? "Showing the first " + listing.returned() + " destination(s) by name, out of "
                                + "more than the " + listing.limit() + " this request asked for. Narrow "
                                + "it with a name prefix to see the rest."
                        : "Found " + listing.returned() + " destination(s), but this is not everything "
                                + "the broker has — part of the listing could not be read.";
                case UNAVAILABLE -> "This broker would not list its destinations. That is broker "
                        + "policy rather than a problem with the connection, and typing a name still "
                        + "works.";
            };
        }
    }

    /**
     * The result of creating a topic, with one plain sentence stating what was made.
     *
     * <p>The values echoed back are the ones the broker accepted, so the sentence describes the created
     * topic rather than the request. {@code note} stays separate for the same reason it does above: the
     * headline and the provider-specific small print are two different things.
     */
    public record CreateTopicResponse(String name, int partitions, int replicationFactor, String message,
            String note) {

        public static CreateTopicResponse from(CreateTopicOutcome outcome) {
            return new CreateTopicResponse(outcome.name(), outcome.partitions(),
                    outcome.replicationFactor(),
                    "Created topic " + outcome.name() + " with " + outcome.partitions()
                            + " partition(s) and replication factor " + outcome.replicationFactor() + ".",
                    outcome.note());
        }
    }
}
