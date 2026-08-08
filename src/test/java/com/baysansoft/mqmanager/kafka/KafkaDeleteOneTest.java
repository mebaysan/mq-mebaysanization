package com.baysansoft.mqmanager.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.messaging.model.DeleteOutcome;
import com.baysansoft.mqmanager.support.KafkaTestFixture;
import com.baysansoft.mqmanager.web.MqOperationException;

/**
 * Deleting a single record is impossible on Kafka — a partition is an append-only log — and this is the
 * one place where "impossible" must never degrade into "reported as done".
 *
 * <p>The UI hides the per-row Delete button for Kafka, so nothing should reach here in normal use. A
 * direct API call still has to get a clear answer rather than a broker error or, worse, a success.
 */
class KafkaDeleteOneTest {

    @SuppressWarnings("unchecked")
    private final Producer<String, byte[]> producer = mock(Producer.class);

    @SuppressWarnings("unchecked")
    private final Consumer<String, byte[]> consumer = mock(Consumer.class);

    private final Admin admin = mock(Admin.class);

    private KafkaMessagingOperations messaging;
    private ConnectionProfile profile;

    @BeforeEach
    void setUp() {
        messaging = KafkaTestFixture.operations(producer, consumer, admin);
        profile = KafkaTestFixture.profile();
    }

    @Test
    @DisplayName("deleting one record is refused with 501 and an explanation, not attempted")
    void isRefusedWithAnExplanation() {
        assertThatThrownBy(() ->
                messaging.deleteMessageDetailed(profile, KafkaTestFixture.TOPIC, "orders-0-3"))
                .isInstanceOfSatisfying(MqOperationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("OPERATION_NOT_SUPPORTED");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
                    assertThat(e.getMessage())
                            .isEqualTo("Kafka records cannot be deleted individually; the log is "
                                    + "immutable. Purge truncates the topic instead.");
                });
    }

    @Test
    @DisplayName("it never reports a deletion — not DELETED, and not the NOT_FOUND that reads like one")
    void neverReportsAnOutcome() {
        // The convenience form on the interface answers a boolean. Neither answer is honest here:
        // true is a lie, and false reads as "already gone" — so it must throw instead.
        assertThatThrownBy(() ->
                messaging.deleteMessage(profile, KafkaTestFixture.TOPIC, "orders-0-3"))
                .isInstanceOf(MqOperationException.class);

        assertThatThrownBy(() ->
                messaging.deleteMessageDetailed(profile, KafkaTestFixture.TOPIC, "orders-0-3"))
                .isNotInstanceOf(AssertionError.class)
                .isInstanceOf(MqOperationException.class);

        // And to be explicit about the value that must never come back:
        assertThat(DeleteOutcome.values()).containsExactly(DeleteOutcome.DELETED,
                DeleteOutcome.NOT_FOUND);
    }

    @Test
    @DisplayName("the refusal never touches the cluster, so it costs nothing and cannot time out")
    void touchesNoClient() {
        assertThatThrownBy(() ->
                messaging.deleteMessageDetailed(profile, KafkaTestFixture.TOPIC, "orders-0-3"))
                .isInstanceOf(MqOperationException.class);

        verifyNoInteractions(producer, consumer, admin);
    }

    @Test
    @DisplayName("even a malformed id is refused for the same reason, not for being malformed")
    void theIdIsIrrelevant() {
        // Validating the id first would imply that a well-formed one might work.
        assertThatThrownBy(() -> messaging.deleteMessageDetailed(profile, KafkaTestFixture.TOPIC,
                "not-an-id"))
                .isInstanceOfSatisfying(MqOperationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OPERATION_NOT_SUPPORTED"));
    }
}
