package com.baysansoft.mqmanager.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.messaging.model.DepthOutcome;
import com.baysansoft.mqmanager.support.KafkaTestFixture;

/**
 * Depth on Kafka is arithmetic on watermarks, not a browse-and-count — which makes it exact, but also
 * makes it mean something different from a JMS queue depth. Both halves of that are asserted here.
 */
class KafkaDepthTest {

    private KafkaTestFixture.RecordingConsumer consumer;
    private ConnectionProfile profile;

    @BeforeEach
    void setUp() {
        consumer = KafkaTestFixture.consumer();
        profile = KafkaTestFixture.profile();
    }

    private KafkaMessagingOperations messaging(MqManagerProperties properties) {
        return KafkaTestFixture.operations(KafkaTestFixture.producer(), consumer, mock(Admin.class),
                properties);
    }

    @Test
    @DisplayName("depth is end minus beginning summed over every partition, and is exact")
    void sumsWatermarksAcrossPartitions() {
        List<TopicPartition> partitions =
                KafkaTestFixture.withPartitions(consumer, KafkaTestFixture.TOPIC, 3);
        consumer.updateBeginningOffsets(Map.of(partitions.get(0), 5L, partitions.get(1), 0L,
                partitions.get(2), 100L));
        consumer.updateEndOffsets(Map.of(partitions.get(0), 12L, partitions.get(1), 3L,
                partitions.get(2), 100L));

        DepthOutcome depth = messaging(new MqManagerProperties())
                .depthDetailed(profile, KafkaTestFixture.TOPIC);

        // (12-5) + (3-0) + (100-100). A partition whose start has advanced past 0 has had records
        // deleted or aged out; counting from 0 would over-report every purged topic.
        assertThat(depth.count()).isEqualTo(10);
        assertThat(depth.exact()).isTrue();
    }

    @Test
    @DisplayName("the note says plainly that this counts retained records, not a consumer backlog")
    void theNoteRefusesToReuseTheJmsMeaning() {
        KafkaTestFixture.seed(consumer, KafkaTestFixture.TOPIC, 4);

        DepthOutcome depth = messaging(new MqManagerProperties())
                .depthDetailed(profile, KafkaTestFixture.TOPIC);

        assertThat(depth.count()).isEqualTo(4);
        assertThat(depth.note())
                .contains("retained")
                .contains("not records waiting to be consumed");
    }

    @Test
    @DisplayName("the browse-and-count ceiling does not apply, because this depth is not a count of a browse")
    void theDepthCeilingIsIrrelevant() {
        MqManagerProperties properties = new MqManagerProperties();
        properties.getDepth().setCeiling(1);
        KafkaTestFixture.withPartitions(consumer, KafkaTestFixture.TOPIC, 1);
        TopicPartition partition = KafkaTestFixture.partition(KafkaTestFixture.TOPIC, 0);
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        consumer.updateEndOffsets(Map.of(partition, 5_000_000L));

        DepthOutcome depth = messaging(properties).depthDetailed(profile, KafkaTestFixture.TOPIC);

        // The JMS providers would report "10000+" here. Kafka knows the real number for free, so the
        // UI must show it without a "+".
        assertThat(depth.count()).isEqualTo(5_000_000L);
        assertThat(depth.exact()).isTrue();
    }

    @Test
    @DisplayName("an empty topic reports an exact zero")
    void emptyTopicIsExactlyZero() {
        KafkaTestFixture.withPartitions(consumer, KafkaTestFixture.TOPIC, 2);
        TopicPartition first = KafkaTestFixture.partition(KafkaTestFixture.TOPIC, 0);
        TopicPartition second = KafkaTestFixture.partition(KafkaTestFixture.TOPIC, 1);
        consumer.updateBeginningOffsets(Map.of(first, 0L, second, 0L));
        consumer.updateEndOffsets(Map.of(first, 0L, second, 0L));

        DepthOutcome depth = messaging(new MqManagerProperties())
                .depthDetailed(profile, KafkaTestFixture.TOPIC);

        assertThat(depth.count()).isZero();
        assertThat(depth.exact()).isTrue();
    }

    @Test
    @DisplayName("reading the depth commits nothing, so it is as harmless as a browse")
    void depthCommitsNothing() {
        KafkaTestFixture.seed(consumer, KafkaTestFixture.TOPIC, 3);

        messaging(new MqManagerProperties()).depthDetailed(profile, KafkaTestFixture.TOPIC);

        assertThat(consumer.commitCalls()).isZero();
    }
}
