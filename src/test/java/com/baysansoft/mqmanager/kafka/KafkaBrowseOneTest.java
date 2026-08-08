package com.baysansoft.mqmanager.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.messaging.model.QueueMessageView;
import com.baysansoft.mqmanager.support.KafkaTestFixture;
import com.baysansoft.mqmanager.web.MqOperationException;

/**
 * Fetching one record by its synthetic id.
 *
 * <p>The dangerous failure here is not "not found" — it is returning the <em>wrong</em> record.
 * Seeking to an offset that retention or compaction has removed silently lands on the next surviving
 * record, so the offset has to be checked rather than trusted.
 */
class KafkaBrowseOneTest {

    private KafkaTestFixture.RecordingConsumer consumer;
    private ConnectionProfile profile;

    @BeforeEach
    void setUp() {
        consumer = KafkaTestFixture.consumer();
        profile = KafkaTestFixture.profile();
    }

    private KafkaMessagingOperations messaging() {
        return KafkaTestFixture.operations(KafkaTestFixture.producer(), consumer, mock(Admin.class),
                shortDeadlines());
    }

    /** Keeps the "nothing there" paths from spending the full 15-second browse budget. */
    private static MqManagerProperties shortDeadlines() {
        MqManagerProperties properties = new MqManagerProperties();
        properties.getKafka().setPollTimeout(java.time.Duration.ofMillis(10));
        properties.getKafka().setBrowseTimeout(java.time.Duration.ofMillis(200));
        return properties;
    }

    @Test
    @DisplayName("browseOne seeks straight to the offset in the id and returns that record")
    void returnsTheRecordAtTheOffset() {
        KafkaTestFixture.seed(consumer, KafkaTestFixture.TOPIC, 5);

        QueueMessageView view = messaging().browseOne(profile, KafkaTestFixture.TOPIC, "orders-0-3");

        assertThat(view.messageId()).isEqualTo("orders-0-3");
        assertThat(view.body()).isEqualTo("body-3");
        assertThat(view.headers()).containsEntry("KafkaOffset", "3");
        assertThat(consumer.commitCalls()).isZero();
    }

    @Test
    @DisplayName("an offset that has been compacted away is a 404, never the next surviving record")
    void neverReturnsTheWrongRecord() {
        KafkaTestFixture.withPartitions(consumer, KafkaTestFixture.TOPIC, 1);
        TopicPartition partition = KafkaTestFixture.partition(KafkaTestFixture.TOPIC, 0);
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        consumer.updateEndOffsets(Map.of(partition, 10L));
        // Only offset 9 survives; 7 was compacted away. A plain seek(7) lands on 9.
        consumer.addRecord(KafkaTestFixture.record(KafkaTestFixture.TOPIC, 0, 9, "k9", "body-9",
                new RecordHeaders()));

        assertThatThrownBy(() -> messaging().browseOne(profile, KafkaTestFixture.TOPIC, "orders-0-7"))
                .isInstanceOfSatisfying(MqOperationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("MESSAGE_NOT_FOUND");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).contains("compacted");
                });
    }

    @Test
    @DisplayName("an offset past the end is a 404 answered from the watermarks, without polling for it")
    void offsetPastTheEndIsNotFound() {
        KafkaTestFixture.seed(consumer, KafkaTestFixture.TOPIC, 2);

        // Settled by arithmetic rather than by waiting out the browse deadline for a record that
        // cannot arrive, and the message names the end offset so the answer is checkable.
        assertThatThrownBy(() -> messaging().browseOne(profile, KafkaTestFixture.TOPIC, "orders-0-99"))
                .isInstanceOfSatisfying(MqOperationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("MESSAGE_NOT_FOUND");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).contains("ends at offset 2");
                });
    }

    @Test
    @DisplayName("an offset below the log start offset says retention or a purge took it, not 'never existed'")
    void offsetBelowTheStartNamesRetention() {
        KafkaTestFixture.withPartitions(consumer, KafkaTestFixture.TOPIC, 1);
        TopicPartition partition = KafkaTestFixture.partition(KafkaTestFixture.TOPIC, 0);
        // A purged or aged-out partition: the log now starts at 50.
        consumer.updateBeginningOffsets(Map.of(partition, 50L));
        consumer.updateEndOffsets(Map.of(partition, 60L));

        assertThatThrownBy(() -> messaging().browseOne(profile, KafkaTestFixture.TOPIC, "orders-0-7"))
                .isInstanceOfSatisfying(MqOperationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("MESSAGE_NOT_FOUND");
                    assertThat(e.getMessage())
                            .contains("starts at offset 50")
                            .contains("retention or by a purge");
                });
    }

    @Test
    @DisplayName("a partition the topic does not have is a 404 rather than a hang")
    void unknownPartitionIsNotFound() {
        KafkaTestFixture.seed(consumer, KafkaTestFixture.TOPIC, 2);

        assertThatThrownBy(() -> messaging().browseOne(profile, KafkaTestFixture.TOPIC, "orders-9-0"))
                .isInstanceOfSatisfying(MqOperationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("MESSAGE_NOT_FOUND");
                    assertThat(e.getMessage()).contains("partition 9");
                });
    }

    @Test
    @DisplayName("an id from another topic is refused before the cluster is touched at all")
    void mismatchedTopicIsRejectedUpFront() {
        // No partitions registered: reaching the consumer at all would fail differently.
        assertThatThrownBy(() -> messaging().browseOne(profile, KafkaTestFixture.TOPIC, "payments-0-1"))
                .isInstanceOfSatisfying(MqOperationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("MESSAGE_ID_INVALID");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    @DisplayName("a JMS-style message id is rejected rather than coerced into Kafka coordinates")
    void jmsMessageIdIsRejected() {
        assertThatThrownBy(() -> messaging().browseOne(profile, KafkaTestFixture.TOPIC,
                "ID:414d5120514d312020202020202020"))
                .isInstanceOfSatisfying(MqOperationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("MESSAGE_ID_INVALID"));
    }
}
