package com.baysansoft.mqmanager.support;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.kafka.KafkaClientFactory;
import com.baysansoft.mqmanager.kafka.KafkaErrorTranslator;
import com.baysansoft.mqmanager.kafka.KafkaMessagingOperations;
import com.baysansoft.mqmanager.kafka.KafkaRecordMapper;

/**
 * Builds the real {@link KafkaMessagingOperations} over Kafka's own {@code MockProducer} /
 * {@code MockConsumer} and a mocked {@code Admin}, so the production code paths are exercised with no
 * broker, no Docker and no network — the same doctrine as {@link MessagingTestFixture}.
 *
 * <p><strong>Build these per test method, never in {@code @BeforeAll}.</strong> Every operation closes
 * the client it used, and a closed {@code MockConsumer} throws on any subsequent call. That is the
 * opposite of the embedded-broker tests, where one broker is deliberately shared across the class.
 *
 * <p>Reads deliberately go through the {@code Consumer}, not the {@code Admin}: {@code Admin}'s
 * describe/list result types have non-public constructors and cannot be built in a test at all, while
 * {@code MockConsumer} supports every read this tool performs.
 */
public final class KafkaTestFixture {

    public static final String TOPIC = "orders";

    private static final Node NODE = new Node(0, "localhost", 9092);

    private KafkaTestFixture() {
    }

    public static KafkaMessagingOperations operations(Producer<String, byte[]> producer,
            Consumer<String, byte[]> consumer, Admin admin, MqManagerProperties properties) {
        KafkaClientFactory factory = new KafkaClientFactory() {
            @Override
            public Producer<String, byte[]> producer(ConnectionProfile profile, String plainPassword) {
                return producer;
            }

            @Override
            public Consumer<String, byte[]> consumer(ConnectionProfile profile, String plainPassword) {
                return consumer;
            }

            @Override
            public Admin admin(ConnectionProfile profile, String plainPassword) {
                return admin;
            }
        };
        return new KafkaMessagingOperations(factory,
                profile -> null, // no credentials: the mock clients never authenticate
                new KafkaErrorTranslator(),
                new KafkaRecordMapper(),
                properties);
    }

    public static KafkaMessagingOperations operations(Producer<String, byte[]> producer,
            Consumer<String, byte[]> consumer, Admin admin) {
        return operations(producer, consumer, admin, new MqManagerProperties());
    }

    public static MockProducer<String, byte[]> producer() {
        return new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
    }

    /** Earliest, because every read in this tool starts from the beginning of the partition. */
    public static RecordingConsumer consumer() {
        return new RecordingConsumer();
    }

    /**
     * A {@code MockConsumer} that counts commit calls.
     *
     * <p>Asserting on {@code committed()} after an operation is not possible — the operation closes
     * the consumer, as it should — and it would only prove no offset was <em>stored</em>. Counting the
     * calls proves the stronger thing: the browse never asks to commit at all.
     */
    public static final class RecordingConsumer extends MockConsumer<String, byte[]> {

        private int commitCalls;

        private RecordingConsumer() {
            super(OffsetResetStrategy.EARLIEST);
        }

        /** How many times any commit overload was invoked. Must be zero for every read operation. */
        public int commitCalls() {
            return commitCalls;
        }

        @Override
        public synchronized void commitSync() {
            commitCalls++;
            super.commitSync();
        }

        @Override
        public synchronized void commitSync(java.time.Duration timeout) {
            commitCalls++;
            super.commitSync(timeout);
        }

        @Override
        public synchronized void commitSync(Map<TopicPartition,
                org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets) {
            commitCalls++;
            super.commitSync(offsets);
        }

        @Override
        public void commitSync(Map<TopicPartition,
                org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets,
                java.time.Duration timeout) {
            commitCalls++;
            super.commitSync(offsets, timeout);
        }

        @Override
        public synchronized void commitAsync() {
            commitCalls++;
            super.commitAsync();
        }

        @Override
        public synchronized void commitAsync(
                org.apache.kafka.clients.consumer.OffsetCommitCallback callback) {
            commitCalls++;
            super.commitAsync(callback);
        }

        @Override
        public synchronized void commitAsync(Map<TopicPartition,
                org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets,
                org.apache.kafka.clients.consumer.OffsetCommitCallback callback) {
            commitCalls++;
            super.commitAsync(offsets, callback);
        }
    }

    public static TopicPartition partition(String topic, int index) {
        return new TopicPartition(topic, index);
    }

    /**
     * Registers partitions for the topic and assigns them, which {@code MockConsumer.addRecord}
     * requires before any record can be seeded. The production code assigns them again itself.
     */
    public static List<TopicPartition> withPartitions(RecordingConsumer consumer,
            String topic, int partitionCount) {
        consumer.updatePartitions(topic, IntStream.range(0, partitionCount)
                .mapToObj(index -> new PartitionInfo(topic, index, NODE, new Node[] {NODE},
                        new Node[] {NODE}))
                .toList());
        List<TopicPartition> partitions = IntStream.range(0, partitionCount)
                .mapToObj(index -> partition(topic, index))
                .toList();
        consumer.assign(partitions);
        return partitions;
    }

    /** A single-partition topic holding {@code count} records, keyed {@code k0..kN} with body-N values. */
    public static void seed(RecordingConsumer consumer, String topic, int count) {
        withPartitions(consumer, topic, 1);
        TopicPartition partition = partition(topic, 0);
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        consumer.updateEndOffsets(Map.of(partition, (long) count));
        for (int index = 0; index < count; index++) {
            consumer.addRecord(record(topic, 0, index, "k" + index, "body-" + index,
                    new RecordHeaders()));
        }
    }

    public static ConsumerRecord<String, byte[]> record(String topic, int partition, long offset,
            String key, String body, Headers headers) {
        return new ConsumerRecord<>(topic, partition, offset, 1_700_000_000_000L + offset,
                TimestampType.CREATE_TIME, -1, -1, key,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), headers,
                Optional.empty());
    }

    public static ConnectionProfile profile() {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setId(1L);
        profile.setName("kafka-test");
        profile.setProvider(Provider.KAFKA);
        profile.setBootstrapServers("localhost:9092");
        return profile;
    }
}
