package com.baysansoft.mqmanager.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.DestinationKind;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.domain.SavedDestination;
import com.baysansoft.mqmanager.domain.SavedDestinationRepository;
import com.baysansoft.mqmanager.web.dto.SavedDestinationResponses.SavedDestinationResponse;

/**
 * The destinations this instance has opened, per connection.
 *
 * <p>Every method starts by requiring the connection to exist, so an unknown id is the same 404 the
 * rest of the API gives rather than a silently orphaned row.
 */
@Service
@Transactional
public class SavedDestinationService {

    private final SavedDestinationRepository repository;
    private final ConnectionProfileService profiles;
    private final MqManagerProperties properties;

    public SavedDestinationService(SavedDestinationRepository repository,
            ConnectionProfileService profiles, MqManagerProperties properties) {
        this.repository = repository;
        this.profiles = profiles;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<SavedDestinationResponse> list(Long connectionId) {
        profiles.require(connectionId);
        return repository.findByConnectionProfileIdOrderByPinnedDescLastOpenedAtDesc(connectionId)
                .stream()
                .map(SavedDestinationResponse::from)
                .toList();
    }

    /**
     * Records that a destination was opened: creates the row or touches the existing one, then trims
     * the connection back to its retention cap.
     *
     * <p>Idempotent by name. Typing a name, following a deep link and picking from the browse list all
     * land here, so there is exactly one path and no way for the three to disagree.
     *
     * @param kind null when the caller does not know — a typed name — in which case it is derived from
     *             the provider
     */
    public SavedDestinationResponse recordOpen(Long connectionId, String name, DestinationKind kind) {
        ConnectionProfile profile = profiles.require(connectionId);
        String trimmed = requireName(name);
        DestinationKind resolved = kind != null ? kind : kindFor(profile.getProvider());

        SavedDestination saved = repository
                .findByConnectionProfileIdAndName(connectionId, trimmed)
                .orElseGet(() -> {
                    SavedDestination created = new SavedDestination();
                    created.setConnectionProfileId(connectionId);
                    created.setName(trimmed);
                    created.setOpenCount(0);
                    created.setPinned(false);
                    return created;
                });

        // A later open with a known kind upgrades a row that was saved as a guess, but a known kind is
        // never downgraded back to the derived default.
        if (kind != null || saved.getKind() == null) {
            saved.setKind(resolved);
        }
        saved.setOpenCount(saved.getOpenCount() + 1);
        saved.setLastOpenedAt(Instant.now());

        SavedDestination stored = repository.save(saved);
        evictBeyondCap(connectionId);
        return SavedDestinationResponse.from(stored);
    }

    /** Pinning is not opening, so {@code lastOpenedAt} is deliberately left alone. */
    public SavedDestinationResponse pin(Long connectionId, String name, boolean pinned) {
        profiles.require(connectionId);
        String trimmed = requireName(name);
        SavedDestination saved = repository.findByConnectionProfileIdAndName(connectionId, trimmed)
                .orElseThrow(() -> new IllegalArgumentException(
                        "'" + trimmed + "' is not a remembered destination on this connection."));
        saved.setPinned(pinned);
        return SavedDestinationResponse.from(repository.save(saved));
    }

    /** Forgetting an unremembered name is not an error — the caller wanted it gone, and it is. */
    public void forget(Long connectionId, String name) {
        profiles.require(connectionId);
        repository.findByConnectionProfileIdAndName(connectionId, requireName(name))
                .ifPresent(repository::delete);
    }

    /**
     * Trims to {@code mqmanager.destinations.saved-per-connection}, oldest first.
     *
     * <p>Pinned rows are never evicted, so the cap is <strong>soft</strong>: a connection whose pins
     * alone exceed it simply keeps them. Unpinning something a user deliberately pinned in order to
     * honour a number would be the wrong trade.
     */
    private void evictBeyondCap(Long connectionId) {
        int cap = Math.max(1, properties.getDestinations().getSavedPerConnection());
        long held = repository.countByConnectionProfileId(connectionId);
        if (held <= cap) {
            return;
        }

        List<SavedDestination> candidates =
                repository.findByConnectionProfileIdAndPinnedFalseOrderByLastOpenedAtAsc(connectionId);
        int excess = (int) Math.min(held - cap, candidates.size());
        if (excess > 0) {
            repository.deleteAll(candidates.subList(0, excess));
        }
    }

    /**
     * What a destination is on a provider whose caller did not say.
     *
     * <p>An exhaustive switch with no {@code default}, so adding a provider is a compile error here
     * rather than a silently wrong guess. The three JMS providers get QUEUE because this tool only ever
     * calls {@code session.createQueue}.
     */
    private static DestinationKind kindFor(Provider provider) {
        return switch (provider) {
            case ACTIVE_MQ, ARTEMIS, IBM_MQ -> DestinationKind.QUEUE;
            case KAFKA -> DestinationKind.TOPIC;
        };
    }

    private static String requireName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("A destination name is required.");
        }
        return name.trim();
    }
}
