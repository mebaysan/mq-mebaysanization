package com.baysansoft.mqmanager.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baysansoft.mqmanager.service.SavedRequestService;
import com.baysansoft.mqmanager.web.dto.SavedRequestRequest;
import com.baysansoft.mqmanager.web.dto.SavedRequestResponses.SavedRequestResponse;

import jakarta.validation.Valid;

/**
 * The send requests this instance remembers for a destination, so one composed before can be sent again
 * without retyping it.
 *
 * <p>The destination name is always a query parameter, never a path segment — the same rule the rest of
 * the queue API follows, since {@code DEV.QUEUE.1} would otherwise look like a static-file request.
 */
@RestController
@RequestMapping("/api/connections/{id}/queue/saved-requests")
public class SavedRequestController {

    private final SavedRequestService requests;

    public SavedRequestController(SavedRequestService requests) {
        this.requests = requests;
    }

    /** Everything remembered for one destination, newest first — named and history together. */
    @GetMapping
    public List<SavedRequestResponse> list(@PathVariable Long id, @RequestParam String queueName) {
        return requests.list(id, queueName);
    }

    /**
     * Remembers a request. A blank {@code label} appends an automatic history entry (trimmed to a cap);
     * a non-blank one saves a named request that is never evicted. Safe to call on every send.
     */
    @PostMapping
    public SavedRequestResponse save(@PathVariable Long id, @RequestParam String queueName,
                                     @Valid @RequestBody SavedRequestRequest request) {
        return requests.save(id, queueName, request);
    }

    @DeleteMapping
    public ResponseEntity<Void> forget(@PathVariable Long id, @RequestParam String queueName,
                                       @RequestParam long requestId) {
        requests.forget(id, queueName, requestId);
        return ResponseEntity.noContent().build();
    }
}
