package com.baysansoft.mqmanager.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.SavedRequest;
import com.baysansoft.mqmanager.domain.SavedRequestRepository;
import com.baysansoft.mqmanager.web.dto.SavedRequestRequest;
import com.baysansoft.mqmanager.web.dto.SavedRequestResponses.SavedRequestResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The send requests this instance remembers for a destination: an automatic history of what was sent,
 * plus any the user saved deliberately under a name.
 *
 * <p>Every method requires the connection to exist first, so an unknown id is the same 404 the rest of
 * the API gives rather than a silently orphaned row.
 */
@Service
@Transactional
public class SavedRequestService {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final SavedRequestRepository repository;
    private final ConnectionProfileService profiles;
    private final MqManagerProperties properties;
    private final ObjectMapper objectMapper;

    public SavedRequestService(SavedRequestRepository repository, ConnectionProfileService profiles,
            MqManagerProperties properties, ObjectMapper objectMapper) {
        this.repository = repository;
        this.profiles = profiles;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<SavedRequestResponse> list(Long connectionId, String destinationName) {
        profiles.require(connectionId);
        String destination = requireDestination(destinationName);
        return repository
                .findByConnectionProfileIdAndDestinationNameOrderByCreatedAtDesc(connectionId, destination)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Remembers a request. A blank label appends a history entry (then trims the destination back to its
     * cap); a non-blank label upserts the named request of that name, leaving history untouched.
     *
     * <p>Idempotent only for named saves: sending the same body twice is two real events, and the
     * history reflects that.
     */
    public SavedRequestResponse save(Long connectionId, String destinationName,
            SavedRequestRequest request) {
        profiles.require(connectionId);
        String destination = requireDestination(destinationName);
        String label = StringUtils.hasText(request.label()) ? request.label().trim() : null;

        SavedRequest saved = label == null
                ? newRow(connectionId, destination)
                : repository
                        .findByConnectionProfileIdAndDestinationNameAndLabel(connectionId, destination, label)
                        .orElseGet(() -> newRow(connectionId, destination));

        saved.setLabel(label);
        saved.setPayload(request.payload());
        saved.setPropertiesJson(writeProperties(request.properties()));
        saved.setMessageKey(blankToNull(request.key()));
        saved.setMessageType(blankToNull(request.messageType()));
        saved.setTargetClient(blankToNull(request.targetClient()));
        // A named upsert refreshes createdAt so the saved list, sorted newest-first, surfaces the one
        // just edited. A history entry gets its timestamp from @PrePersist.
        if (label != null) {
            saved.setCreatedAt(Instant.now());
        }

        SavedRequest stored = repository.save(saved);
        if (label == null) {
            evictHistoryBeyondCap(connectionId, destination);
        }
        return toResponse(stored);
    }

    /** Forgetting a row that is not there is not an error — the caller wanted it gone, and it is. */
    public void forget(Long connectionId, String destinationName, long id) {
        profiles.require(connectionId);
        String destination = requireDestination(destinationName);
        repository.findById(id)
                // Scope the delete to the connection and destination in the path, so an id from another
                // destination cannot be deleted through this one.
                .filter(row -> row.getConnectionProfileId().equals(connectionId)
                        && row.getDestinationName().equals(destination))
                .ifPresent(repository::delete);
    }

    private SavedRequest newRow(Long connectionId, String destination) {
        SavedRequest row = new SavedRequest();
        row.setConnectionProfileId(connectionId);
        row.setDestinationName(destination);
        return row;
    }

    /**
     * Trims history entries to {@code mqmanager.requests.history-per-destination}, oldest first. Named
     * requests are excluded from both the count and the candidates, so they are never dropped.
     */
    private void evictHistoryBeyondCap(Long connectionId, String destination) {
        int cap = Math.max(1, properties.getRequests().getHistoryPerDestination());
        long held = repository
                .countByConnectionProfileIdAndDestinationNameAndLabelIsNull(connectionId, destination);
        if (held <= cap) {
            return;
        }
        List<SavedRequest> candidates = repository
                .findByConnectionProfileIdAndDestinationNameAndLabelIsNullOrderByCreatedAtAsc(
                        connectionId, destination);
        int excess = (int) Math.min(held - cap, candidates.size());
        if (excess > 0) {
            repository.deleteAll(candidates.subList(0, excess));
        }
    }

    private SavedRequestResponse toResponse(SavedRequest row) {
        return new SavedRequestResponse(row.getId(), row.getLabel(), row.isNamed(), row.getPayload(),
                readProperties(row.getPropertiesJson()), row.getMessageKey(), row.getMessageType(),
                row.getTargetClient(), row.getCreatedAt());
    }

    private String writeProperties(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(properties);
        } catch (JsonProcessingException e) {
            // The map is String→String, so this cannot happen in practice; treat it as "no properties"
            // rather than failing a save over bookkeeping.
            return "{}";
        }
    }

    private Map<String, String> readProperties(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(json, STRING_MAP);
            return parsed != null ? parsed : Map.of();
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private static String requireDestination(String destinationName) {
        if (!StringUtils.hasText(destinationName)) {
            throw new IllegalArgumentException("A destination name is required.");
        }
        return destinationName.trim();
    }
}
