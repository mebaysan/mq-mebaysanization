package com.baysansoft.mqmanager.web.dto;

import java.time.Instant;
import java.util.Map;

/** Response shapes for the remembered-request endpoints. */
public final class SavedRequestResponses {

    private SavedRequestResponses() {
    }

    /**
     * One remembered send request.
     *
     * <p>Carries an {@code id}, unlike {@link SavedDestinationResponses.SavedDestinationResponse}: a
     * history entry has no natural key (the same body can be sent twice), so the id is how the UI
     * addresses a specific row to load or forget it.
     *
     * @param named true for a deliberately saved request, false for an automatic history entry
     */
    public record SavedRequestResponse(long id, String label, boolean named, String payload,
            Map<String, String> properties, String key, String messageType, String targetClient,
            Instant createdAt) {
    }
}
