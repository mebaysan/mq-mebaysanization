package com.baysansoft.mqmanager.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.messaging.model.BrowseResult;
import com.baysansoft.mqmanager.messaging.model.QueueMessageView;
import com.baysansoft.mqmanager.support.KafkaTestFixture;
import com.baysansoft.mqmanager.web.MqOperationException;

/**
 * Browsing, over Kafka's own {@code MockConsumer}.
 *
 * <p>The claim under test is not just "records come back" but "records come back without the browse
 * being visible to anyone else" — which is what makes a read-only tool safe to point at production.
 */
class KafkaBrowseTest {

    private KafkaTestFixture.RecordingConsumer consumer;
    private ConnectionProfile profile;

    @BeforeEach
    void setUp() {
        consumer = KafkaTestFixture.consumer();
        profile = KafkaTestFixture.profile();
    }

    private KafkaMessagingOperations messaging() {
        return messaging(new MqManagerProperties());
    }

    private KafkaMessagingOperations messaging(MqManagerProperties properties) {
        return KafkaTestFixture.operations(KafkaTestFixture.producer(), consumer, mock(Admin.class),
                properties);
    }

    @Test
    @DisplayName("browsing reads every partition from its earliest retained offset and commits nothing")
    void readsEveryPartitionAndNeverCommits() {
        List<TopicPartition> partitions = KafkaTestFixture.withPartitions(consumer,
                KafkaTestFixture.TOPIC, 3);
        consumer.updateBeginningOffsets(Map.of(partitions.get(0), 0L, partitions.get(1), 0L,
                partitions.get(2), 0L));
        consumer.updateEndOffsets(Map.of(partitions.get(0), 1L, partitions.get(1), 1L,
                partitions.get(2), 1L));
        for (int partition = 0; partition < 3; partition++) {
            consumer.addRecord(KafkaTestFixture.record(KafkaTestFixture.TOPIC, partition, 0,
                    "k" + partition, "body-" + partition, new RecordHeaders()));
        }

        BrowseResult result = messaging().browse(profile, KafkaTestFixture.TOPIC, 100);

        assertThat(result.returned()).isEqualTo(3);
        assertThat(result.messages()).extracting(QueueMessageView::messageId)
                .containsExactlyInAnyOrder("orders-0-0", "orders-1-0", "orders-2-0");
        assertThat(partitions).hasSize(3);
        // The guarantee. A single commit call here would move a real consumer group's position.
        assertThat(consumer.commitCalls()).isZero();
    }

    @Test
    @DisplayName("the record key and its coordinates land in headers; record headers land in properties")
    void mapsCoordinatesAndHeadersToTheRightPlaces() {
        KafkaTestFixture.withPartitions(consumer, KafkaTestFixture.TOPIC, 1);
        TopicPartition partition = KafkaTestFixture.partition(KafkaTestFixture.TOPIC, 0);
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        consumer.updateEndOffsets(Map.of(partition, 1L));
        consumer.addRecord(KafkaTestFixture.record(KafkaTestFixture.TOPIC, 0, 0, "customer-7",
                "payload", new RecordHeaders()
                        .add(new RecordHeader("tenant", "acme".getBytes(StandardCharsets.UTF_8)))));

        QueueMessageView view = messaging().browse(profile, KafkaTestFixture.TOPIC, 100)
                .messages().get(0);

        assertThat(view.headers())
                .containsEntry("KafkaPartition", "0")
                .containsEntry("KafkaOffset", "0")
                .containsEntry("KafkaKey", "customer-7")
                .containsEntry("KafkaTimestampType", "CreateTime");
        // What the send form calls a property comes back as a property, on both sides of the trip.
        assertThat(view.properties()).containsExactly(Map.entry("tenant", "acme"));
        assertThat(view.enqueueTime()).isNotNull();
        // Kafka has no equivalent of these, and a plausible-looking default would be a lie.
        assertThat(view.correlationId()).isNull();
        assertThat(view.priority()).isNull();
        assertThat(view.redelivered()).isNull();
    }

    @Test
    @DisplayName("a repeated record-header key is joined rather than silently overwritten")
    void repeatedHeaderKeysSurvive() {
        KafkaTestFixture.withPartitions(consumer, KafkaTestFixture.TOPIC, 1);
        TopicPartition partition = KafkaTestFixture.partition(KafkaTestFixture.TOPIC, 0);
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        consumer.updateEndOffsets(Map.of(partition, 1L));
        consumer.addRecord(KafkaTestFixture.record(KafkaTestFixture.TOPIC, 0, 0, null, "payload",
                new RecordHeaders()
                        .add(new RecordHeader("trace", "a".getBytes(StandardCharsets.UTF_8)))
                        .add(new RecordHeader("trace", "b".getBytes(StandardCharsets.UTF_8)))));

        // Kafka header keys are not unique. A map cannot hold both, so the value must show both.
        assertThat(messaging().browse(profile, KafkaTestFixture.TOPIC, 100)
                .messages().get(0).properties())
                .containsEntry("trace", "a, b");
    }

    @Test
    @DisplayName("a null value renders as a tombstone rather than as an empty body")
    void tombstonesAreNamed() {
        KafkaTestFixture.withPartitions(consumer, KafkaTestFixture.TOPIC, 1);
        TopicPartition partition = KafkaTestFixture.partition(KafkaTestFixture.TOPIC, 0);
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        consumer.updateEndOffsets(Map.of(partition, 1L));
        consumer.addRecord(KafkaTestFixture.record(KafkaTestFixture.TOPIC, 0, 0, "k", null,
                new RecordHeaders()));

        QueueMessageView view = messaging().browse(profile, KafkaTestFixture.TOPIC, 100)
                .messages().get(0);

        assertThat(view.body()).isNull();
        assertThat(view.note()).contains("Tombstone");
    }

    @Test
    @DisplayName("a body longer than the preview budget is cut down and flagged")
    void longBodiesAreTruncated() {
        MqManagerProperties properties = new MqManagerProperties();
        properties.getBrowse().setPreviewBytes(4);
        KafkaTestFixture.seed(consumer, KafkaTestFixture.TOPIC, 1);

        QueueMessageView view = messaging(properties).browse(profile, KafkaTestFixture.TOPIC, 100)
                .messages().get(0);

        assertThat(view.body()).isEqualTo("body");
        assertThat(view.bodyTruncated()).isTrue();
    }

    @Test
    @DisplayName("hitting the requested limit is reported as truncated, so the UI never claims a total")
    void limitIsReportedAsTruncated() {
        KafkaTestFixture.seed(consumer, KafkaTestFixture.TOPIC, 10);

        BrowseResult result = messaging().browse(profile, KafkaTestFixture.TOPIC, 4);

        assertThat(result.returned()).isEqualTo(4);
        assertThat(result.limit()).isEqualTo(4);
        assertThat(result.truncated()).isTrue();
        assertThat(result.providerNote())
                .contains("without committing")
                .contains("retention");
    }

    @Test
    @DisplayName("a plain browse returns the NEWEST records, newest first — not the oldest from the start")
    void plainBrowseReadsTheNewestFromTheEnd() {
        // Offsets 0..9, timestamps rising with offset, so offset 9 is the newest record on the topic.
        KafkaTestFixture.seed(consumer, KafkaTestFixture.TOPIC, 10);

        BrowseResult result = messaging().browse(profile, KafkaTestFixture.TOPIC, 4);

        assertThat(result.returned()).isEqualTo(4);
        // The four newest, newest first — not body-0..body-3 from the head of the topic.
        assertThat(result.messages()).extracting(QueueMessageView::body)
                .containsExactly("body-9", "body-8", "body-7", "body-6");
        // Older records remain below the window, so the page is honestly marked truncated.
        assertThat(result.truncated()).isTrue();
    }

    @Test
    @DisplayName("reading fewer records than asked for is not truncated")
    void underTheLimitIsNotTruncated() {
        KafkaTestFixture.seed(consumer, KafkaTestFixture.TOPIC, 2);

        BrowseResult result = messaging().browse(profile, KafkaTestFixture.TOPIC, 100);

        assertThat(result.returned()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("an empty topic browses to zero messages rather than erroring")
    void emptyTopicIsNotAnError() {
        KafkaTestFixture.withPartitions(consumer, KafkaTestFixture.TOPIC, 1);
        TopicPartition partition = KafkaTestFixture.partition(KafkaTestFixture.TOPIC, 0);
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        consumer.updateEndOffsets(Map.of(partition, 0L));

        assertThat(messaging().browse(profile, KafkaTestFixture.TOPIC, 100).returned()).isZero();
    }

    @Test
    @DisplayName("a topic with no visible partitions is a 404, not an empty list that looks like an empty topic")
    void unknownTopicIsNotFound() {
        assertThatThrownBy(() -> messaging().browse(profile, "does-not-exist", 100))
                .isInstanceOfSatisfying(MqOperationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("QUEUE_NOT_FOUND"));
    }
}
