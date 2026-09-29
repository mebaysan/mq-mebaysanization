package com.baysansoft.mqmanager.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.service.ConnectionProfileService;
import com.baysansoft.mqmanager.web.dto.ConnectionProfileResponse;

/**
 * The seeding mechanism, driven by injected properties rather than the real (untracked) seed file, so it
 * proves the behaviour on any machine — a fresh clone and CI have no {@code seed-connections.yml}.
 *
 * <p>The context (and its temp data directory, marker file included) is shared across these methods, so
 * they are ordered: the read-only checks run before the one that deletes a seed.
 */
@SpringBootTest
@TestMethodOrder(OrderAnnotation.class)
class ConnectionSeederTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void seeds(DynamicPropertyRegistry registry) {
        registry.add("mqmanager.data-dir", () -> dataDir.toString());
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:file:" + dataDir.resolve("mqmanager") + ";DB_CLOSE_ON_EXIT=FALSE");
        registry.add("mqmanager.seed-connections[0].name", () -> "SEED KAFKA");
        registry.add("mqmanager.seed-connections[0].provider", () -> "KAFKA");
        registry.add("mqmanager.seed-connections[0].bootstrap-servers", () -> "h1:9092,h2:9092");
        registry.add("mqmanager.seed-connections[1].name", () -> "SEED AMQ");
        registry.add("mqmanager.seed-connections[1].provider", () -> "ACTIVE_MQ");
        registry.add("mqmanager.seed-connections[1].host", () -> "localhost");
        registry.add("mqmanager.seed-connections[1].port", () -> "61616");
    }

    @Autowired
    private ConnectionSeeder seeder;

    @Autowired
    private ConnectionProfileService connections;

    private Map<String, ConnectionProfileResponse> byName() {
        return connections.list().stream()
                .collect(Collectors.toMap(ConnectionProfileResponse::name, profile -> profile));
    }

    @Test
    @Order(1)
    @DisplayName("configured seeds are created with their fields, each mapped to the right provider")
    void createsConfiguredSeeds() {
        Map<String, ConnectionProfileResponse> byName = byName();

        assertThat(byName).containsKeys("SEED KAFKA", "SEED AMQ");
        assertThat(byName.get("SEED KAFKA").provider()).isEqualTo(Provider.KAFKA);
        assertThat(byName.get("SEED KAFKA").bootstrapServers()).isEqualTo("h1:9092,h2:9092");
        assertThat(byName.get("SEED AMQ").provider()).isEqualTo(Provider.ACTIVE_MQ);
        assertThat(byName.get("SEED AMQ").host()).isEqualTo("localhost");
        assertThat(byName.get("SEED AMQ").port()).isEqualTo(61616);
    }

    @Test
    @Order(2)
    @DisplayName("running again creates nothing new — each seed name is applied once")
    void seedingIsIdempotent() {
        int before = connections.list().size();

        seeder.run(null);

        assertThat(connections.list()).hasSize(before);
    }

    @Test
    @Order(3)
    @DisplayName("a deleted seed is not resurrected on the next run — deletion is permanent")
    void deletedSeedStaysDeleted() {
        Long id = byName().get("SEED KAFKA").id();
        connections.delete(id);
        assertThat(byName()).doesNotContainKey("SEED KAFKA");

        seeder.run(null);

        // The marker already lists "SEED KAFKA", so the seeder leaves it gone rather than recreating it.
        assertThat(byName()).doesNotContainKey("SEED KAFKA");
    }
}
