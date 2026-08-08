package com.baysansoft.mqmanager.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DeleteRecordsOptions;
import org.apache.kafka.clients.admin.DeleteRecordsResult;
import org.apache.kafka.clients.admin.DeletedRecords;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.messaging.model.PurgeOutcome;
import com.baysansoft.mqmanager.support.KafkaTestFixture;
import com.baysansoft.mqmanager.web.MqOperationException;

/**
 * Purge is the one Kafka operation that mutates anything, and the one whose reported count is easiest
 * to get wrong: the number to report is what the broker's low watermark actually moved by, not the end
 * offset that was requested.
 *
 * <p>{@code Admin} is mocked rather than faked because {@code DeleteRecordsResult} is one of the few
 * admin result types with a public constructor — {@code DescribeTopicsResult} and friends are not, and
 * that is why every read in this tool goes through the consumer instead.
 */
class KafkaPurgeTest {

    private KafkaTestFixture.RecordingConsumer consumer;
    private Admin admin;
    private KafkaMessagingOperations messaging;
    private ConnectionProfile profile;

    @BeforeEach
    void setUp() {
        consumer = KafkaTestFixture.consumer();
        admin = mock(Admin.class);
        messaging = KafkaTestFixture.operations(KafkaTestFixture.producer(), consumer, admin);
        profile = KafkaTestFixture.profile();
    }

    @Test
    @DisplayName("purge truncates every partition to its own end offset")
    void truncatesEachPartitionToItsEnd() {
        List<TopicPartition> partitions =
                KafkaTestFixture.withPartitions(consumer, KafkaTestFixture.TOPIC, 2);
        consumer.updateBeginningOffsets(Map.of(partitions.get(0), 0L, partitions.get(1), 0L));
        consumer.updateEndOffsets(Map.of(partitions.get(0), 12L, partitions.get(1), 4L));
        stubDeleteRecords(Map.of(partitions.get(0), 12L, partitions.get(1), 4L));

        messaging.purgeDetailed(profile, KafkaTestFixture.TOPIC);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<TopicPartition, RecordsToDelete>> captor =
                ArgumentCaptor.forClass(Map.class);
        verify(admin).deleteRecords(captor.capture(), any(DeleteRecordsOptions.class));

        // A single shared offset would under-truncate the busier partition and over-ask on the other.
        assertThat(captor.getValue().get(partitions.get(0)).beforeOffset()).isEqualTo(12L);
        assertThat(captor.getValue().get(partitions.get(1)).beforeOffset()).isEqualTo(4L);
    }

    @Test
    @DisplayName("the reported count is the low-watermark delta, not the end offset that was requested")
    void reportsWhatWasActuallyRemoved() {
        List<TopicPartition> partitions =
                KafkaTestFixture.withPartitions(consumer, KafkaTestFixture.TOPIC, 1);
        // The partition starts at 5 — earlier records were already gone. Reporting 12 here would
        // claim five records that this purge did not remove.
        consumer.updateBeginningOffsets(Map.of(partitions.get(0), 5L));
        consumer.updateEndOffsets(Map.of(partitions.get(0), 12L));
        stubDeleteRecords(Map.of(partitions.get(0), 12L));

        PurgeOutcome outcome = messaging.purgeDetailed(profile, KafkaTestFixture.TOPIC);

        assertThat(outcome.purged()).isEqualTo(7);
        assertThat(outcome.stopReason()).isEqualTo(PurgeOutcome.StopReason.QUEUE_EMPTY);
        assertThat(outcome.complete()).isTrue();
        assertThat(outcome.note())
                .contains("log start offset")
                .contains("Records produced after that point are unaffected");
    }

    @Test
    @DisplayName("a broker that truncated less than asked is reported at its real figure, not the ask")
    void trustsTheBrokerOverTheRequest() {
        List<TopicPartition> partitions =
                KafkaTestFixture.withPartitions(consumer, KafkaTestFixture.TOPIC, 1);
        consumer.updateBeginningOffsets(Map.of(partitions.get(0), 0L));
        consumer.updateEndOffsets(Map.of(partitions.get(0), 100L));
        stubDeleteRecords(Map.of(partitions.get(0), 60L));

        assertThat(messaging.purgeDetailed(profile, KafkaTestFixture.TOPIC).purged()).isEqualTo(60);
    }

    @Test
    @DisplayName("purging an empty topic removes nothing and says so, rather than failing")
    void emptyTopicPurgesToZero() {
        List<TopicPartition> partitions =
                KafkaTestFixture.withPartitions(consumer, KafkaTestFixture.TOPIC, 1);
        consumer.updateBeginningOffsets(Map.of(partitions.get(0), 8L));
        consumer.updateEndOffsets(Map.of(partitions.get(0), 8L));
        stubDeleteRecords(Map.of(partitions.get(0), 8L));

        assertThat(messaging.purgeDetailed(profile, KafkaTestFixture.TOPIC).purged()).isZero();
    }

    @Test
    @DisplayName("a partition that could not be truncated is a 409 that still reports what was removed")
    void partialFailureIsNeverReportedAsSuccess() {
        List<TopicPartition> partitions =
                KafkaTestFixture.withPartitions(consumer, KafkaTestFixture.TOPIC, 2);
        consumer.updateBeginningOffsets(Map.of(partitions.get(0), 0L, partitions.get(1), 0L));
        consumer.updateEndOffsets(Map.of(partitions.get(0), 10L, partitions.get(1), 10L));

        Map<TopicPartition, KafkaFuture<DeletedRecords>> results = new HashMap<>();
        results.put(partitions.get(0), KafkaFuture.completedFuture(new DeletedRecords(10L)));
        results.put(partitions.get(1), failedFuture(
                new TopicAuthorizationException("Not authorized to access topics: [orders]")));
        when(admin.deleteRecords(anyMap(), any(DeleteRecordsOptions.class)))
                .thenReturn(new DeleteRecordsResult(results));

        assertThatThrownBy(() -> messaging.purgeDetailed(profile, KafkaTestFixture.TOPIC))
                .isInstanceOfSatisfying(MqOperationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("PURGE_PARTIAL");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    // The half that did work must still be reported, or the operator cannot tell
                    // whether re-running is safe.
                    assertThat(e.getMessage()).contains("Removed 10 record(s)")
                            .contains("1 of 2 partition(s)")
                            .doesNotContain("\tat ");
                });
    }

    @Test
    @DisplayName("purging a topic with no visible partitions is a 404 rather than a silent no-op")
    void unknownTopicIsNotFound() {
        assertThatThrownBy(() -> messaging.purgeDetailed(profile, "does-not-exist"))
                .isInstanceOfSatisfying(MqOperationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("QUEUE_NOT_FOUND"));
    }

    private void stubDeleteRecords(Map<TopicPartition, Long> lowWatermarks) {
        Map<TopicPartition, KafkaFuture<DeletedRecords>> results = new HashMap<>();
        lowWatermarks.forEach((partition, watermark) ->
                results.put(partition, KafkaFuture.completedFuture(new DeletedRecords(watermark))));
        when(admin.deleteRecords(anyMap(), any(DeleteRecordsOptions.class)))
                .thenReturn(new DeleteRecordsResult(results));
    }

    /**
     * {@code KafkaFuture} exposes no public failed-future factory and {@code KafkaFutureImpl} is
     * internal, so the failure is raised inside a {@code thenApply} instead. The result is what a real
     * broker produces: {@code get()} throws {@code ExecutionException} wrapping the Kafka error.
     */
    private static KafkaFuture<DeletedRecords> failedFuture(RuntimeException cause) {
        return KafkaFuture.completedFuture((DeletedRecords) null).thenApply(ignored -> {
            throw cause;
        });
    }
}
