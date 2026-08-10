package com.baysansoft.mqmanager.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.messaging.model.MessageType;
import com.baysansoft.mqmanager.messaging.model.OutboundMessage;
import com.baysansoft.mqmanager.messaging.model.TargetClient;
import com.baysansoft.mqmanager.support.KafkaTestFixture;
import com.baysansoft.mqmanager.web.MqOperationException;

/** Sending, over Kafka's own {@code MockProducer} — no broker, no network. */
class KafkaSendTest {

    private MockProducer<String, byte[]> producer;
    private KafkaMessagingOperations messaging;
    private ConnectionProfile profile;

    @BeforeEach
    void setUp() {
        // Per test, not per class: the operation closes the producer and a closed one cannot be reused.
        producer = KafkaTestFixture.producer();
        messaging = KafkaTestFixture.operations(producer, KafkaTestFixture.consumer(),
                mock(Admin.class));
        profile = KafkaTestFixture.profile();
    }

    /** A plain send: no key, no properties, and — crucially — no message type to object to. */
    private static OutboundMessage body(String body) {
        return new OutboundMessage(body, Map.of(), null, null, null);
    }

    @Test
    @DisplayName("a sent record carries the body as UTF-8, the key, and every property as a record header")
    void sendsBodyKeyAndHeaders() {
        messaging.send(profile, KafkaTestFixture.TOPIC,
                new OutboundMessage("hello wörld", Map.of("tenant", "acme"), "customer-7", null, null));

        assertThat(producer.history()).hasSize(1);
        ProducerRecord<String, byte[]> sent = producer.history().get(0);

        assertThat(sent.topic()).isEqualTo(KafkaTestFixture.TOPIC);
        assertThat(sent.key()).isEqualTo("customer-7");
        assertThat(sent.value()).isEqualTo("hello wörld".getBytes(StandardCharsets.UTF_8));
        assertThat(sent.partition()).as("the partitioner decides, not us").isNull();

        Header tenant = sent.headers().lastHeader("tenant");
        assertThat(tenant).isNotNull();
        assertThat(new String(tenant.value(), StandardCharsets.UTF_8)).isEqualTo("acme");
    }

    @Test
    @DisplayName("send returns the topic-partition-offset id the browse view will show for that record")
    void returnsAnIdThatRoundTrips() {
        String messageId = messaging.send(profile, KafkaTestFixture.TOPIC, body("body"));

        assertThat(messageId).isEqualTo(KafkaTestFixture.TOPIC + "-0-0");
        // The id must survive a trip back through the single-message endpoint, which is the whole
        // reason it is the full topic-partition-offset rather than just partition-offset.
        assertThat(KafkaRecordId.parse(messageId, KafkaTestFixture.TOPIC))
                .isEqualTo(KafkaRecordId.of(KafkaTestFixture.TOPIC, 0, 0));
    }

    @Test
    @DisplayName("no key means a record with a null key, not an empty one — they partition differently")
    void absentKeyIsNullNotEmpty() {
        messaging.send(profile, KafkaTestFixture.TOPIC, body("body"));

        assertThat(producer.history().get(0).key()).isNull();
    }

    @Test
    @DisplayName("a null body sends an empty record rather than a tombstone")
    void nullBodyIsEmptyNotATombstone() {
        messaging.send(profile, KafkaTestFixture.TOPIC, body(null));

        // A null value on a compacted topic is a delete marker. Sending one because the textarea was
        // left empty would quietly remove a key.
        assertThat(producer.history().get(0).value()).isEmpty();
    }

    @Test
    @DisplayName("the producer is closed once the send completes, since one is opened per operation")
    void closesAfterSuccess() {
        messaging.send(profile, KafkaTestFixture.TOPIC, body("body"));

        assertThat(producer.closed()).isTrue();
    }

    @Test
    @DisplayName("a Kafka send with no message type still works, since omitting it is what every "
            + "caller written before the choice existed does")
    void absentMessageTypeIsAccepted() {
        messaging.send(profile, KafkaTestFixture.TOPIC, body("body"));

        assertThat(producer.history()).hasSize(1);
    }

    @Test
    @DisplayName("any explicit message type is refused on Kafka, TEXT included — its record values are "
            + "bytes already, so agreeing to TEXT would imply a choice Kafka never had")
    void explicitMessageTypeIsRefused() {
        for (MessageType type : MessageType.values()) {
            assertThatThrownBy(() -> messaging.send(profile, KafkaTestFixture.TOPIC,
                    new OutboundMessage("body", Map.of(), null, type, null)))
                    .isInstanceOfSatisfying(MqOperationException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("OPERATION_NOT_SUPPORTED");
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });
        }
        // Refused before the producer is ever opened, so nothing reached the topic on the way to the
        // error. A rejection that had already sent the record would be the worst of both answers.
        assertThat(producer.history()).isEmpty();
    }

    @Test
    @DisplayName("a Kafka send with no target client still works, since omitting it is what every "
            + "caller written before the choice existed does")
    void absentTargetClientIsAccepted() {
        messaging.send(profile, KafkaTestFixture.TOPIC, body("body"));

        assertThat(producer.history()).hasSize(1);
    }

    @Test
    @DisplayName("any explicit target client is refused on Kafka, JMS included — a target client "
            + "chooses whether IBM MQ writes an MQRFH2 header, and Kafka has no such header for either "
            + "answer to be about")
    void explicitTargetClientIsRefused() {
        for (TargetClient targetClient : TargetClient.values()) {
            assertThatThrownBy(() -> messaging.send(profile, KafkaTestFixture.TOPIC,
                    new OutboundMessage("body", Map.of(), null, null, targetClient)))
                    .isInstanceOfSatisfying(MqOperationException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("OPERATION_NOT_SUPPORTED");
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });
        }
        assertThat(producer.history()).isEmpty();
    }

    @Test
    @DisplayName("sending to a topic that does not exist is a 404, the same answer reading it gives")
    void unknownTopicIsNotFound() {
        // This is what max.block.ms produces when the topic is missing. Reported as a timeout it would
        // mean sending said 504 while reading the very same topic said 404.
        producer.sendException = new TimeoutException("Topic orders not present in metadata after 5000 ms");

        assertThatThrownBy(() ->
                messaging.send(profile, KafkaTestFixture.TOPIC, body("body")))
                .isInstanceOfSatisfying(MqOperationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("QUEUE_NOT_FOUND");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).contains("auto.create.topics.enable");
                });
        assertThat(producer.closed()).isTrue();
    }

    @Test
    @DisplayName("an unreachable cluster is a clean 504 with no stack trace, and the producer still closes")
    void translatesFailuresAndStillCloses() {
        producer.sendException = new TimeoutException("Timed out waiting for a node assignment");

        assertThatThrownBy(() ->
                messaging.send(profile, KafkaTestFixture.TOPIC, body("body")))
                .isInstanceOfSatisfying(MqOperationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("BROKER_UNREACHABLE");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                    assertThat(e.getMessage()).doesNotContain("Exception").doesNotContain("\tat ");
                });
        assertThat(producer.closed()).isTrue();
    }
}
