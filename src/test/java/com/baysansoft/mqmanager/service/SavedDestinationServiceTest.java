package com.baysansoft.mqmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.DestinationKind;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.domain.SavedDestination;
import com.baysansoft.mqmanager.domain.SavedDestinationRepository;
import com.baysansoft.mqmanager.web.NotFoundException;
import com.baysansoft.mqmanager.web.dto.ConnectionProfileRequest;
import com.baysansoft.mqmanager.web.dto.SavedDestinationResponses.SavedDestinationResponse;

/**
 * Remembered destinations against the real schema, because most of what matters here is enforced by the
 * database: the unique constraint, its case sensitivity, and the cascade that takes these rows with a
 * deleted connection.
 */
@SpringBootTest
class SavedDestinationServiceTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void useThrowawayDataDirectory(DynamicPropertyRegistry registry) {
        registry.add("mqmanager.data-dir", () -> dataDir.toString());
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:file:" + dataDir.resolve("mqmanager") + ";DB_CLOSE_ON_EXIT=FALSE");
    }

    @Autowired
    private SavedDestinationService saved;

    @Autowired
    private ConnectionProfileService profiles;

    @Autowired
    private SavedDestinationRepository repository;

    @Autowired
    private MqManagerProperties properties;

    private Long connectionId;

    @BeforeEach
    void freshConnection() {
        repository.deleteAll();
        profiles.list().forEach(profile -> profiles.delete(profile.id()));
        connectionId = profiles.create(new ConnectionProfileRequest(
                null, "amq-" + System.nanoTime(), Provider.ACTIVE_MQ, "localhost", 61616,
                null, null, null, null, null, null)).id();
    }

    private ConnectionProfile kafkaConnection() {
        Long id = profiles.create(new ConnectionProfileRequest(
                null, "kafka-" + System.nanoTime(), Provider.KAFKA, null, null, null, null, null,
                "localhost:9092", null, null)).id();
        return profiles.require(id);
    }

    @Test
    @DisplayName("opening the same name twice touches one row and counts both opens")
    void recordOpenIsAnUpsert() {
        saved.recordOpen(connectionId, "orders.new", null);
        SavedDestinationResponse second = saved.recordOpen(connectionId, "orders.new", null);

        assertThat(saved.list(connectionId)).hasSize(1);
        assertThat(second.openCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a name is trimmed, and a blank one is refused rather than stored")
    void namesAreTrimmedAndRequired() {
        assertThat(saved.recordOpen(connectionId, "  orders.new  ", null).name())
                .isEqualTo("orders.new");
        assertThatThrownBy(() -> saved.recordOpen(connectionId, "   ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("names differing only in case are two destinations, as they are on every broker")
    void uniquenessIsCaseSensitive() {
        saved.recordOpen(connectionId, "ORDERS", null);
        saved.recordOpen(connectionId, "orders", null);

        assertThat(saved.list(connectionId)).hasSize(2);
    }

    @Test
    @DisplayName("a kind the caller does not know is derived from the provider, never guessed wrongly")
    void kindIsDerivedFromTheProvider() {
        assertThat(saved.recordOpen(connectionId, "orders.new", null).kind()).isEqualTo("QUEUE");

        ConnectionProfile kafka = kafkaConnection();
        assertThat(saved.recordOpen(kafka.getId(), "orders", null).kind()).isEqualTo("TOPIC");
    }

    @Test
    @DisplayName("a kind supplied by the picker wins, and a later typed open does not undo it")
    void suppliedKindIsNotDowngraded() {
        saved.recordOpen(connectionId, "events", DestinationKind.TOPIC);
        assertThat(saved.recordOpen(connectionId, "events", null).kind()).isEqualTo("TOPIC");
    }

    @Test
    @DisplayName("pinning does not count as opening, so it never reorders the list")
    void pinningDoesNotTouchLastOpened() {
        saved.recordOpen(connectionId, "orders.new", null);
        SavedDestination before = repository
                .findByConnectionProfileIdAndName(connectionId, "orders.new").orElseThrow();

        SavedDestinationResponse pinned = saved.pin(connectionId, "orders.new", true);

        assertThat(pinned.pinned()).isTrue();
        assertThat(pinned.openCount()).isEqualTo(1);
        assertThat(pinned.lastOpenedAt()).isEqualTo(before.getLastOpenedAt());
    }

    @Test
    @DisplayName("pinned first, then most recently opened")
    void listIsOrderedPinnedThenRecent() {
        saved.recordOpen(connectionId, "first", null);
        saved.recordOpen(connectionId, "second", null);
        saved.recordOpen(connectionId, "third", null);
        saved.pin(connectionId, "first", true);

        assertThat(saved.list(connectionId))
                .extracting(SavedDestinationResponse::name)
                .containsExactly("first", "third", "second");
    }

    @Test
    @DisplayName("the retention cap evicts the oldest unpinned rows and never a pinned one")
    void retentionNeverEvictsAPin() {
        properties.getDestinations().setSavedPerConnection(3);
        try {
            saved.recordOpen(connectionId, "keep-me", null);
            saved.pin(connectionId, "keep-me", true);
            for (int i = 1; i <= 5; i++) {
                saved.recordOpen(connectionId, "queue-" + i, null);
            }

            List<String> names = saved.list(connectionId).stream()
                    .map(SavedDestinationResponse::name)
                    .toList();

            assertThat(names).contains("keep-me");
            assertThat(names).doesNotContain("queue-1", "queue-2");
            // The cap is soft: pins are kept even when they push the total over it.
            assertThat(names).hasSize(3);
        } finally {
            properties.getDestinations().setSavedPerConnection(50);
        }
    }

    @Test
    @DisplayName("deleting the connection takes its remembered destinations with it")
    void deletingTheConnectionCascades() {
        saved.recordOpen(connectionId, "orders.new", null);
        assertThat(repository.countByConnectionProfileId(connectionId)).isEqualTo(1);

        profiles.delete(connectionId);

        // Enforced by the foreign key in V3, not by application code — which is the point of declaring it.
        assertThat(repository.countByConnectionProfileId(connectionId)).isZero();
    }

    @Test
    @DisplayName("forgetting a name that was never remembered is not an error")
    void forgettingIsIdempotent() {
        saved.forget(connectionId, "never-opened");
        assertThat(saved.list(connectionId)).isEmpty();
    }

    @Test
    @DisplayName("an unknown connection is the same 404 as everywhere else, not an orphaned row")
    void unknownConnectionIsNotFound() {
        assertThatThrownBy(() -> saved.recordOpen(9_999L, "orders.new", null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> saved.list(9_999L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("pinning something that was never opened is refused rather than silently created")
    void pinningAnUnknownNameIsRejected() {
        assertThatThrownBy(() -> saved.pin(connectionId, "never-opened", true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
