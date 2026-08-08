package com.baysansoft.mqmanager.web.dto;

import com.baysansoft.mqmanager.domain.Provider;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Inbound shape for creating, updating and test-driving a connection profile.
 *
 * <p>This type is only ever deserialized, never returned — {@link ConnectionProfileResponse} is a
 * separate record with no password member at all. That is a stronger guarantee than annotating the
 * entity write-only, which would depend on nobody ever returning the entity from a future endpoint.
 *
 * @param password on create, the password to store. On update, {@code null} means "leave the stored
 *                 password alone" and {@code ""} means "clear it" — without that distinction an edit
 *                 form could never be submitted, since the client is never given the current value.
 * @param id       used only by the test endpoints, so "Test connection" on an edit form can fall back
 *                 to the saved password when the user has not retyped it. Ignored by create and update.
 */
@ValidConnectionProfile
public record ConnectionProfileRequest(

        Long id,

        @NotBlank(message = "is required")
        @Size(max = 120, message = "must be at most 120 characters")
        String name,

        @NotNull(message = "is required")
        Provider provider,

        @Size(max = 255, message = "must be at most 255 characters")
        String host,

        @Min(value = 1, message = "must be between 1 and 65535")
        @Max(value = 65535, message = "must be between 1 and 65535")
        Integer port,

        @Size(max = 255, message = "must be at most 255 characters")
        String username,

        String password,

        @Size(max = 1024, message = "must be at most 1024 characters")
        String brokerUrlOverride,

        @Size(max = 1024, message = "must be at most 1024 characters")
        String bootstrapServers,

        @Size(max = 48, message = "must be at most 48 characters")
        String queueManagerName,

        @Size(max = 64, message = "must be at most 64 characters")
        String channel) {
}
