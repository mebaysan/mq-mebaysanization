package com.baysansoft.mqmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.domain.SavedRequestRepository;
import com.baysansoft.mqmanager.web.NotFoundException;
import com.baysansoft.mqmanager.web.dto.ConnectionProfileRequest;
import com.baysansoft.mqmanager.web.dto.SavedRequestRequest;
import com.baysansoft.mqmanager.web.dto.SavedRequestResponses.SavedRequestResponse;

/**
 * Remembered send requests against the real schema, because what matters here — the history cap that
 * spares named saves, the properties round-trip through a JSON column, and the cascade that takes these
 * rows with a deleted connection — is enforced by the database and the service together.
 */
@SpringBootTest
class SavedRequestServiceTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void useThrowawayDataDirectory(DynamicPropertyRegistry registry) {
        registry.add("mqmanager.data-dir", () -> dataDir.toString());
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:file:" + dataDir.resolve("mqmanager") + ";DB_CLOSE_ON_EXIT=FALSE");
    }

    @Autowired
    private SavedRequestService requests;

    @Autowired
    private ConnectionProfileService profiles;

    @Autowired
    private SavedRequestRepository repository;

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

    private SavedRequestRequest history(String payload) {
        return new SavedRequestRequest(payload, Map.of(), null, null, null, null);
    }

    private SavedRequestRequest named(String payload, String label) {
        return new SavedRequestRequest(payload, Map.of(), null, null, null, label);
    }

    @Test
    @DisplayName("a blank label appends a new history entry every time — two sends are two rows")
    void historyAppendsRatherThanUpserting() {
        requests.save(connectionId, "orders", history("one"));
        requests.save(connectionId, "orders", history("one"));

        List<SavedRequestResponse> all = requests.list(connectionId, "orders");
        assertThat(all).hasSize(2);
        assertThat(all).allMatch(entry -> !entry.named());
    }

    @Test
    @DisplayName("saving a name twice updates the one named row rather than duplicating it")
    void namedSaveIsAnUpsert() {
        requests.save(connectionId, "orders", named("first body", "smoke test"));
        SavedRequestResponse second = requests.save(connectionId, "orders", named("second body", "smoke test"));

        List<SavedRequestResponse> named = requests.list(connectionId, "orders").stream()
                .filter(SavedRequestResponse::named)
                .toList();
        assertThat(named).hasSize(1);
        assertThat(second.payload()).isEqualTo("second body");
        assertThat(second.label()).isEqualTo("smoke test");
    }

    @Test
    @DisplayName("the properties map round-trips through the JSON column unchanged")
    void propertiesRoundTrip() {
        SavedRequestResponse saved = requests.save(connectionId, "orders",
                new SavedRequestRequest("body", Map.of("a", "1", "b", "two"), "k", "TEXT", null, null));

        assertThat(saved.properties()).containsExactlyInAnyOrderEntriesOf(Map.of("a", "1", "b", "two"));
        assertThat(saved.key()).isEqualTo("k");
        assertThat(saved.messageType()).isEqualTo("TEXT");
        assertThat(saved.targetClient()).isNull();
    }

    @Test
    @DisplayName("the history cap drops the oldest history entries but never a named save")
    void historyCapSparesNamedSaves() {
        properties.getRequests().setHistoryPerDestination(3);
        try {
            requests.save(connectionId, "orders", named("keep", "important"));
            for (int i = 1; i <= 5; i++) {
                requests.save(connectionId, "orders", history("h" + i));
            }

            List<SavedRequestResponse> all = requests.list(connectionId, "orders");
            long historyCount = all.stream().filter(entry -> !entry.named()).count();
            assertThat(historyCount).isEqualTo(3);
            assertThat(all).anyMatch(entry -> "important".equals(entry.label()));
            // The two oldest history bodies were evicted; the newest three remain.
            assertThat(all).extracting(SavedRequestResponse::payload)
                    .contains("h5", "h4", "h3", "keep")
                    .doesNotContain("h1", "h2");
        } finally {
            properties.getRequests().setHistoryPerDestination(10);
        }
    }

    @Test
    @DisplayName("requests for different destinations on one connection do not mix")
    void requestsAreScopedToTheirDestination() {
        requests.save(connectionId, "orders", history("for orders"));
        requests.save(connectionId, "events", history("for events"));

        assertThat(requests.list(connectionId, "orders")).singleElement()
                .extracting(SavedRequestResponse::payload).isEqualTo("for orders");
        assertThat(requests.list(connectionId, "events")).singleElement()
                .extracting(SavedRequestResponse::payload).isEqualTo("for events");
    }

    @Test
    @DisplayName("forgetting a row scoped to another destination through this one does nothing")
    void forgetIsScopedToConnectionAndDestination() {
        SavedRequestResponse onEvents = requests.save(connectionId, "events", history("keep me"));

        // The id is real, but the destination in the path is not the one it belongs to.
        requests.forget(connectionId, "orders", onEvents.id());

        assertThat(requests.list(connectionId, "events")).hasSize(1);
    }

    @Test
    @DisplayName("forgetting a row that is not there is not an error")
    void forgettingIsIdempotent() {
        requests.forget(connectionId, "orders", 9_999L);
        assertThat(requests.list(connectionId, "orders")).isEmpty();
    }

    @Test
    @DisplayName("deleting the connection takes its remembered requests with it")
    void deletingTheConnectionCascades() {
        requests.save(connectionId, "orders", history("one"));
        assertThat(repository.count()).isEqualTo(1);

        profiles.delete(connectionId);

        // Enforced by the foreign key in V4, not by application code — which is the point of declaring it.
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("an unknown connection is the same 404 as everywhere else, not an orphaned row")
    void unknownConnectionIsNotFound() {
        assertThatThrownBy(() -> requests.save(9_999L, "orders", history("one")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> requests.list(9_999L, "orders")).isInstanceOf(NotFoundException.class);
    }
}
