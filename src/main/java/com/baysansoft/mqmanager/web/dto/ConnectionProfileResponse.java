package com.baysansoft.mqmanager.web.dto;

import java.time.Instant;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;

/**
 * Outbound shape for a connection profile.
 *
 * <p>There is deliberately no {@code password} or {@code passwordCipher} member. Not a write-only
 * annotation, not a filtered field — the data simply is not part of this type, so no future endpoint
 * can leak it by accident.
 *
 * @param hasPassword          whether a password is stored at all, so the edit form can show
 *                             "leave blank to keep the existing password"
 * @param credentialsReadable  false when the stored password cannot be decrypted with the current key
 *                             (lost or rotated key file). The profile is still listed and editable; only
 *                             operations that actually need the password will fail.
 */
public record ConnectionProfileResponse(
        Long id,
        String name,
        Provider provider,
        String providerLabel,
        String host,
        Integer port,
        String username,
        boolean hasPassword,
        boolean credentialsReadable,
        String brokerUrlOverride,
        String bootstrapServers,
        String queueManagerName,
        String channel,
        Instant createdAt,
        Instant updatedAt) {

    public static ConnectionProfileResponse from(ConnectionProfile profile, boolean credentialsReadable) {
        return new ConnectionProfileResponse(
                profile.getId(),
                profile.getName(),
                profile.getProvider(),
                profile.getProvider().displayName(),
                profile.getHost(),
                profile.getPort(),
                profile.getUsername(),
                profile.getPasswordCipher() != null,
                credentialsReadable,
                profile.getBrokerUrlOverride(),
                profile.getBootstrapServers(),
                profile.getQueueManagerName(),
                profile.getChannel(),
                profile.getCreatedAt(),
                profile.getUpdatedAt());
    }
}
