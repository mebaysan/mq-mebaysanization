package com.baysansoft.mqmanager.web;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.messaging.MessagingOperations;
import com.baysansoft.mqmanager.messaging.model.ConnectionTestResult;
import com.baysansoft.mqmanager.service.ConnectionProfileService;
import com.baysansoft.mqmanager.web.dto.ConnectionProfileRequest;
import com.baysansoft.mqmanager.web.dto.ConnectionProfileResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/connections")
public class ConnectionController {

    private final ConnectionProfileService service;
    private final MessagingOperations messaging;

    public ConnectionController(ConnectionProfileService service, MessagingOperations messaging) {
        this.service = service;
        this.messaging = messaging;
    }

    /** Tests a saved profile, using its stored password. */
    @PostMapping("/{id}/test")
    public ConnectionTestResult test(@PathVariable Long id) {
        return messaging.testConnection(service.require(id));
    }

    /**
     * Tests a draft that has not been saved, so the user gets pass/fail before committing to it.
     *
     * <p>When editing an existing profile the password field is normally left untouched (the client is
     * never given the current value), so a null password with an {@code id} falls back to the stored one.
     * Without that, "Test connection" would be unusable on every edit form.
     */
    @PostMapping("/test")
    public ConnectionTestResult testDraft(@Valid @RequestBody ConnectionProfileRequest request) {
        ConnectionProfile draft = service.toTransientProfile(request);
        String password = service.resolveDraftPassword(request);
        return messaging.testConnection(draft, password);
    }

    @GetMapping
    public List<ConnectionProfileResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ConnectionProfileResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<ConnectionProfileResponse> create(
            @Valid @RequestBody ConnectionProfileRequest request) {
        ConnectionProfileResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/connections/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ConnectionProfileResponse update(@PathVariable Long id,
                                            @Valid @RequestBody ConnectionProfileRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
