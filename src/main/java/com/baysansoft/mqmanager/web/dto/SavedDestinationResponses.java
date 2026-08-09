package com.baysansoft.mqmanager.web.dto;

import java.time.Instant;

import com.baysansoft.mqmanager.domain.SavedDestination;

/** Response shapes for the remembered-destination endpoints. */
public final class SavedDestinationResponses {

    private SavedDestinationResponses() {
    }

    /**
     * One remembered destination.
     *
     * <p>Deliberately carries no {@code id}: within a connection the name <em>is</em> the key, and a
     * second identifier in the UI would only invite the two to disagree.
     */
    public record SavedDestinationResponse(String name, String kind, boolean pinned, long openCount,
            Instant lastOpenedAt, Instant createdAt) {

        public static SavedDestinationResponse from(SavedDestination saved) {
            return new SavedDestinationResponse(
                    saved.getName(),
                    saved.getKind().name(),
                    saved.isPinned(),
                    saved.getOpenCount(),
                    saved.getLastOpenedAt(),
                    saved.getCreatedAt());
        }
    }
}
