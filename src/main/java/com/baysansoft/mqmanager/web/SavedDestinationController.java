package com.baysansoft.mqmanager.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baysansoft.mqmanager.domain.DestinationKind;
import com.baysansoft.mqmanager.service.SavedDestinationService;
import com.baysansoft.mqmanager.web.dto.SavedDestinationRequest;
import com.baysansoft.mqmanager.web.dto.SavedDestinationResponses.SavedDestinationResponse;

import jakarta.validation.Valid;

/**
 * The destinations this instance remembers for a connection, so one that has been opened can be opened
 * again without retyping it.
 *
 * <p>The name travels in a JSON body for the two writes and as a query parameter for the delete —
 * never as a path segment, for the same reason queue names never are.
 */
@RestController
@RequestMapping("/api/connections/{id}/saved-destinations")
public class SavedDestinationController {

    private final SavedDestinationService saved;

    public SavedDestinationController(SavedDestinationService saved) {
        this.saved = saved;
    }

    /** Pinned first, then most recently opened. */
    @GetMapping
    public List<SavedDestinationResponse> list(@PathVariable Long id) {
        return saved.list(id);
    }

    /**
     * Records that a destination was opened. An upsert: a name already remembered is touched rather
     * than duplicated, so this is safe to call on every open.
     */
    @PostMapping
    public SavedDestinationResponse recordOpen(@PathVariable Long id,
                                               @Valid @RequestBody SavedDestinationRequest request) {
        DestinationKind kind = StringUtils.hasText(request.kind())
                ? DestinationKind.parse(request.kind())
                : null;
        return saved.recordOpen(id, request.name(), kind);
    }

    /** PUT rather than PATCH: the API client already has {@code put}, and one call is not worth a verb. */
    @PutMapping("/pin")
    public SavedDestinationResponse pin(@PathVariable Long id,
                                        @Valid @RequestBody SavedDestinationRequest request) {
        return saved.pin(id, request.name(), request.pinned());
    }

    @DeleteMapping
    public ResponseEntity<Void> forget(@PathVariable Long id, @RequestParam String name) {
        saved.forget(id, name);
        return ResponseEntity.noContent().build();
    }
}
