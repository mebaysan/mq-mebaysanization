package com.baysansoft.mqmanager.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Recording that a destination was opened, or changing whether it is pinned.
 *
 * <p>The name travels in a body rather than a query parameter for these two, which sidesteps escaping
 * entirely — {@code DEV.QUEUE.1} needs no thought at all in JSON.
 *
 * @param kind   optional. When absent the server derives it from the connection's provider, which is
 *               the right answer for a name that was typed rather than picked from a listing
 * @param pinned only meaningful on the pin endpoint; ignored when recording an open
 */
public record SavedDestinationRequest(
        @NotBlank(message = "A destination name is required.")
        @Size(max = 512, message = "A destination name may be at most 512 characters.")
        String name,
        String kind,
        boolean pinned) {
}
