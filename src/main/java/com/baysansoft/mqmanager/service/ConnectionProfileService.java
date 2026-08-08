package com.baysansoft.mqmanager.service;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.ConnectionProfileRepository;
import com.baysansoft.mqmanager.jms.BrokerPasswordResolver;
import com.baysansoft.mqmanager.security.CryptoService;
import com.baysansoft.mqmanager.security.UndecryptableSecretException;
import com.baysansoft.mqmanager.web.MqOperationException;
import com.baysansoft.mqmanager.web.NotFoundException;
import com.baysansoft.mqmanager.web.dto.ConnectionProfileRequest;
import com.baysansoft.mqmanager.web.dto.ConnectionProfileResponse;

/** CRUD for connection profiles. The only place a broker password is encrypted or decrypted. */
@Service
@Transactional
public class ConnectionProfileService implements BrokerPasswordResolver {

    private final ConnectionProfileRepository repository;
    private final CryptoService crypto;

    public ConnectionProfileService(ConnectionProfileRepository repository, CryptoService crypto) {
        this.repository = repository;
        this.crypto = crypto;
    }

    @Transactional(readOnly = true)
    public List<ConnectionProfileResponse> list() {
        return repository.findAllByOrderByNameAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ConnectionProfileResponse get(Long id) {
        return toResponse(require(id));
    }

    @Transactional(readOnly = true)
    public ConnectionProfile require(Long id) {
        return repository.findById(id).orElseThrow(() -> NotFoundException.connectionProfile(id));
    }

    public ConnectionProfileResponse create(ConnectionProfileRequest request) {
        requireNameAvailable(request.name(), null);

        ConnectionProfile profile = new ConnectionProfile();
        applyEditableFields(profile, request);
        profile.setPasswordCipher(crypto.encrypt(emptyToNull(request.password())));

        return toResponse(repository.save(profile));
    }

    public ConnectionProfileResponse update(Long id, ConnectionProfileRequest request) {
        ConnectionProfile profile = require(id);
        requireNameAvailable(request.name(), id);

        applyEditableFields(profile, request);

        // null  -> keep whatever is stored (the client is never given the current password, so an
        //          unchanged edit form legitimately submits null)
        // ""    -> the user cleared the field: remove the stored password
        // value -> replace it
        if (request.password() != null) {
            profile.setPasswordCipher(crypto.encrypt(emptyToNull(request.password())));
        }

        return toResponse(repository.save(profile));
    }

    public void delete(Long id) {
        repository.delete(require(id));
    }

    /**
     * Decrypts the stored password for a broker operation.
     *
     * @throws MqOperationException 409 when the key can no longer open it, rather than a 500. The user
     *                              can fix this by re-entering the password in the UI.
     */
    @Override
    public String resolve(ConnectionProfile profile) {
        return resolvePassword(profile);
    }

    public String resolvePassword(ConnectionProfile profile) {
        try {
            return crypto.decrypt(profile.getPasswordCipher());
        } catch (UndecryptableSecretException e) {
            throw new MqOperationException("MQ_CREDENTIALS_UNREADABLE", HttpStatus.CONFLICT,
                    "The stored password for '" + profile.getName() + "' cannot be decrypted with the "
                            + "current encryption key. Edit the connection and re-enter the password.",
                    e);
        }
    }

    /**
     * Password to use for a draft (unsaved) test: what the user typed, or — when they left the field
     * untouched on an edit form — whatever is already stored against that profile.
     */
    public String resolveDraftPassword(ConnectionProfileRequest request) {
        if (request.password() != null) {
            return emptyToNull(request.password());
        }
        return Optional.ofNullable(request.id())
                .flatMap(repository::findById)
                .map(this::resolvePassword)
                .orElse(null);
    }

    /** Builds a transient profile from a draft request, for testing a connection before saving it. */
    public ConnectionProfile toTransientProfile(ConnectionProfileRequest request) {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setId(request.id());
        applyEditableFields(profile, request);
        return profile;
    }

    private void applyEditableFields(ConnectionProfile profile, ConnectionProfileRequest request) {
        profile.setName(request.name().trim());
        profile.setProvider(request.provider());
        profile.setHost(trimToNull(request.host()));
        profile.setPort(request.port());
        profile.setUsername(trimToNull(request.username()));
        profile.setBrokerUrlOverride(trimToNull(request.brokerUrlOverride()));
        profile.setBootstrapServers(trimToNull(request.bootstrapServers()));
        profile.setQueueManagerName(trimToNull(request.queueManagerName()));
        profile.setChannel(trimToNull(request.channel()));
    }

    /** Case-insensitive, so "Prod" and "prod" cannot both exist and confuse an operator. */
    private void requireNameAvailable(String name, Long allowedId) {
        repository.findByNameIgnoreCase(name.trim())
                .filter(existing -> !existing.getId().equals(allowedId))
                .ifPresent(existing -> {
                    throw new MqOperationException("DUPLICATE_NAME", HttpStatus.CONFLICT,
                            "A connection named '" + existing.getName() + "' already exists.");
                });
    }

    private ConnectionProfileResponse toResponse(ConnectionProfile profile) {
        return ConnectionProfileResponse.from(profile, crypto.isReadable(profile.getPasswordCipher()));
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
