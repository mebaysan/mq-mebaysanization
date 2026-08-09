package com.baysansoft.mqmanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.jms.ConnectionFactoryRegistry;
import com.baysansoft.mqmanager.jms.DestinationListerRegistry;
import com.baysansoft.mqmanager.messaging.MessagingOperationsRouter;
import com.baysansoft.mqmanager.web.MqOperationException;

import java.nio.file.Path;

/**
 * Proves the whole application context wires up: Flyway migrates both migrations, Hibernate's
 * {@code validate} accepts the resulting schema, the encryption key is provisioned, the SPA resource
 * handlers register, and every provider has something that can talk to it.
 *
 * <p>Both the registry and the router fail fast on a provider they cannot serve, so this test catches a
 * provider that was added to the enum but never wired up — which is the whole reason they are written
 * that way.
 */
@SpringBootTest
class MqManagerApplicationTests {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void useThrowawayDataDirectory(DynamicPropertyRegistry registry) {
        registry.add("mqmanager.data-dir", () -> dataDir.toString());
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:file:" + dataDir.resolve("mqmanager") + ";DB_CLOSE_ON_EXIT=FALSE");
    }

    @Autowired
    private ConnectionFactoryRegistry registry;

    @Autowired
    private DestinationListerRegistry listers;

    @Autowired
    private MessagingOperationsRouter router;

    @Test
    @DisplayName("the context loads and every JMS provider has a connection-factory builder")
    void contextLoadsWithAllJmsProvidersWired() {
        for (Provider provider : Provider.values()) {
            if (!provider.isJms()) {
                continue;
            }
            assertThat(registry.forProvider(provider))
                    .as("no ConnectionFactoryBuilder registered for %s", provider)
                    .isNotNull();
            assertThat(registry.forProvider(provider).provider()).isEqualTo(provider);
        }
    }

    @Test
    @DisplayName("every JMS provider also has a destination lister, so no Browse button fails at click time")
    void everyJmsProviderCanBeListed() {
        for (Provider provider : Provider.values()) {
            if (!provider.isJms()) {
                continue;
            }
            assertThat(listers.forProvider(provider))
                    .as("no DestinationLister registered for %s", provider)
                    .isNotNull();
            assertThat(listers.forProvider(provider).provider()).isEqualTo(provider);
        }
        // Kafka lists topics through its own admin client, so it deliberately has no lister here.
        assertThat(listers.forProvider(Provider.KAFKA)).isNull();
    }

    @Test
    @DisplayName("every provider — including the non-JMS one — has a messaging implementation")
    void everyProviderIsRoutable() {
        // The router's constructor refuses to build without full Provider coverage, so being injectable
        // at all is the assertion. A provider added to the enum and never wired fails context startup.
        assertThat(router).isNotNull();
    }

    @Test
    @DisplayName("Kafka routes to its own implementation, not to a JMS one")
    void kafkaRoutesToTheKafkaImplementation() {
        assertThat(Provider.KAFKA.isJms()).isFalse();
        assertThat(registry.forProvider(Provider.KAFKA)).isNull();

        ConnectionProfile kafka = new ConnectionProfile();
        kafka.setProvider(Provider.KAFKA);
        kafka.setBootstrapServers("localhost:9092");

        // Only the Kafka implementation answers delete-one this way, and it does so without touching
        // the cluster — so this proves the routing without needing a broker.
        assertThatThrownBy(() -> router.deleteMessageDetailed(kafka, "orders", "orders-0-1"))
                .isInstanceOfSatisfying(MqOperationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OPERATION_NOT_SUPPORTED"));
    }
}
