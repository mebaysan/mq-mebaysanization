package com.baysansoft.mqmanager.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DeleteRecordsOptions;
import org.apache.kafka.clients.admin.DeletedRecords;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.admin.TopicListing;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.errors.AuthorizationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.DestinationKind;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.jms.BrokerPasswordResolver;
import com.baysansoft.mqmanager.jms.BrokerUrls;
import com.baysansoft.mqmanager.messaging.ProviderMessagingOperations;
import com.baysansoft.mqmanager.messaging.model.BrowseResult;
import com.baysansoft.mqmanager.messaging.model.ConnectionTestResult;
import com.baysansoft.mqmanager.messaging.model.DeleteOutcome;
import com.baysansoft.mqmanager.messaging.model.DepthOutcome;
import com.baysansoft.mqmanager.messaging.model.DestinationEntry;
import com.baysansoft.mqmanager.messaging.model.DestinationListing;
import com.baysansoft.mqmanager.messaging.model.DestinationListings;
import com.baysansoft.mqmanager.messaging.model.DestinationQuery;
import com.baysansoft.mqmanager.messaging.model.PurgeOutcome;
import com.baysansoft.mqmanager.messaging.model.QueueMessageView;
import com.baysansoft.mqmanager.web.MqOperationException;

/**
 * Every queue operation, for Kafka, over {@code kafka-clients} directly.
 *
 * <p>Kafka is not JMS and this class does not pretend otherwise. Three things work differently enough
 * that the API reports them differently rather than mapping them onto queue semantics:
 *
 * <ul>
 *   <li><strong>Browse is naturally non-destructive.</strong> It assigns every partition, seeks to the
 *       beginning and reads. It never commits an offset and the consumer has no group id at all, so
 *       there is no way for it to disturb a real consumer group.
 *   <li><strong>Depth counts records retained, not records unconsumed.</strong> It is
 *       {@code end - start} summed across partitions, which is exact — but it is not a backlog, and
 *       the note on the result says so.
 *   <li><strong>A single record cannot be deleted.</strong> The log is immutable. That is reported as
 *       501 {@code OPERATION_NOT_SUPPORTED} rather than attempted and failed.
 * </ul>
 *
 * <p>There is no long-lived connection to bound, so every call carries its own explicit timeout. That
 * matters more than it sounds: Kafka's own defaults run to minutes, and {@code max.block.ms} alone
 * would freeze a request for a minute on a topic that does not exist.
 */
@Service
public class KafkaMessagingOperations implements ProviderMessagingOperations {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessagingOperations.class);

    private static final Set<Provider> PROVIDERS = EnumSet.of(Provider.KAFKA);

    /** Named the way the error translator names every other action, for consistent error text. */
    private static final String LIST_ACTION = "list topics";

    /** Carried into the listing so the UI can say how the answer was obtained. */
    private static final String SOURCE = "the Kafka admin API";

    private final KafkaClientFactory clientFactory;
    private final BrokerPasswordResolver passwordResolver;
    private final KafkaErrorTranslator errorTranslator;
    private final KafkaRecordMapper recordMapper;
    private final MqManagerProperties properties;

    public KafkaMessagingOperations(KafkaClientFactory clientFactory,
                                    BrokerPasswordResolver passwordResolver,
                                    KafkaErrorTranslator errorTranslator,
                                    KafkaRecordMapper recordMapper,
                                    MqManagerProperties properties) {
        this.clientFactory = clientFactory;
        this.passwordResolver = passwordResolver;
        this.errorTranslator = errorTranslator;
        this.recordMapper = recordMapper;
        this.properties = properties;
    }

    @Override
    public Set<Provider> providers() {
        return PROVIDERS;
    }

    // ------------------------------------------------------------------ connect

    @Override
    public ConnectionTestResult testConnection(ConnectionProfile profile) {
        return runConnectionTest(profile, () -> passwordResolver.resolve(profile));
    }

    @Override
    public ConnectionTestResult testConnection(ConnectionProfile profile, String plainPassword) {
        return runConnectionTest(profile, () -> plainPassword);
    }

    private ConnectionTestResult runConnectionTest(ConnectionProfile profile,
                                                   Supplier<String> password) {
        long startedAt = System.nanoTime();
        try {
            int topics = withConsumer(profile, password.get(), KafkaErrorTranslator.CONNECT_ACTION,
                    consumer -> consumer.listTopics(apiTimeout()).size());
            return ConnectionTestResult.ok(elapsedMs(startedAt),
                    "Connected to " + BrokerUrls.describe(profile) + "; " + topics
                            + " topic(s) visible.");
        } catch (MqOperationException e) {
            // A failed test answers the question the caller asked, so it is a 200 with success=false.
            return ConnectionTestResult.failed(elapsedMs(startedAt), e.getCode(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ send

    @Override
    public String send(ConnectionProfile profile, String topic, String body,
                       Map<String, String> headers, String key) {
        return withProducer(profile, "send a message", producer -> {
            List<Header> recordHeaders = new ArrayList<>();
            if (headers != null) {
                headers.forEach((name, value) -> recordHeaders.add(new RecordHeader(name,
                        value == null ? null : value.getBytes(StandardCharsets.UTF_8))));
            }
            byte[] value = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
            // Null partition, so Kafka's partitioner decides: by key when there is one, round-robin
            // otherwise. Passing an empty key instead of null would change that, and not for the better.
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, null, null,
                    emptyToNull(key), value, recordHeaders);

            RecordMetadata metadata = producer.send(record).get(apiTimeoutMs(), TimeUnit.MILLISECONDS);
            logSend(topic, body, metadata);
            return KafkaRecordId.of(metadata.topic(), metadata.partition(), metadata.offset()).toString();
        });
    }

    // ------------------------------------------------------------------ browse

    @Override
    public BrowseResult browse(ConnectionProfile profile, String topic, int limit) {
        int effectiveLimit = clampLimit(limit);
        int previewBytes = properties.getBrowse().getPreviewBytes();

        List<QueueMessageView> messages = withConsumer(profile, "browse the topic", consumer -> {
            List<TopicPartition> partitions = assignAllPartitions(consumer, topic);
            consumer.seekToBeginning(partitions);

            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, apiTimeout());
            List<QueueMessageView> collected = new ArrayList<>();
            long deadline = System.nanoTime() + properties.getKafka().getBrowseTimeout().toNanos();

            // The loop stops on the watermarks, never on an empty poll. A real consumer routinely
            // returns nothing from its first poll or two while fetches are still in flight, and
            // treating that as "the topic is empty" would report zero messages for a topic that has
            // plenty. An empty topic costs nothing anyway: its positions already equal its end
            // offsets, so the loop body never runs.
            while (collected.size() < effectiveLimit
                    && !allPartitionsDrained(consumer, partitions, ends)
                    && System.nanoTime() < deadline) {
                ConsumerRecords<String, byte[]> polled = consumer.poll(pollTimeout());
                for (ConsumerRecord<String, byte[]> record : polled) {
                    if (collected.size() >= effectiveLimit) {
                        break;
                    }
                    collected.add(recordMapper.toView(record, previewBytes));
                }
            }
            // Never commitSync/commitAsync. Together with having no group id, this is what makes a
            // browse provably invisible to real consumers.
            return collected;
        });

        return new BrowseResult(messages, messages.size(), effectiveLimit,
                messages.size() >= effectiveLimit, browseNote());
    }

    @Override
    public QueueMessageView browseOne(ConnectionProfile profile, String topic, String messageId) {
        KafkaRecordId id = KafkaRecordId.parse(messageId, topic);
        int maxBodyBytes = properties.getBrowse().getMaxBodyBytes();

        return withConsumer(profile, "read the message", consumer -> {
            TopicPartition partition = new TopicPartition(id.topic(), id.partition());
            requirePartitionExists(consumer, id, partition);

            List<TopicPartition> assignment = List.of(partition);
            consumer.assign(assignment);

            // Settle the "definitely not there" cases against the watermarks first. They are exact and
            // cost one round trip, where polling for a record that cannot arrive would burn the whole
            // browse deadline before admitting it.
            long begin = offset(consumer.beginningOffsets(assignment, apiTimeout()), partition);
            long end = offset(consumer.endOffsets(assignment, apiTimeout()), partition);
            if (id.offset() >= end) {
                throw notFound(id, "the partition ends at offset " + end);
            }
            if (id.offset() < begin) {
                throw notFound(id, "the partition now starts at offset " + begin
                        + " — the record was removed by retention or by a purge");
            }

            consumer.seek(partition, id.offset());

            long deadline = System.nanoTime() + properties.getKafka().getBrowseTimeout().toNanos();
            while (System.nanoTime() < deadline) {
                // Not stopped by an empty poll: a real consumer often returns nothing on its first
                // poll while the fetch is in flight, and the offset is known to be in range by now.
                for (ConsumerRecord<String, byte[]> record : consumer.poll(pollTimeout())
                        .records(partition)) {
                    if (record.offset() == id.offset()) {
                        return recordMapper.toView(record, maxBodyBytes);
                    }
                    if (record.offset() > id.offset()) {
                        // A seek to a removed offset silently lands on the next surviving record, so
                        // returning what we got would answer with a different message entirely.
                        throw notFound(id, "it has been compacted away");
                    }
                }
            }
            throw notFound(id, "it could not be read within the browse timeout");
        });
    }

    // ------------------------------------------------------------------ depth

    @Override
    public DepthOutcome depthDetailed(ConnectionProfile profile, String topic) {
        return withConsumer(profile, "read the topic depth", consumer -> {
            List<TopicPartition> partitions = partitionsOf(consumer, topic);
            Map<TopicPartition, Long> begins = consumer.beginningOffsets(partitions, apiTimeout());
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, apiTimeout());

            long retained = partitions.stream()
                    .mapToLong(partition -> offset(ends, partition) - offset(begins, partition))
                    .sum();

            // Exact, unlike the JMS providers' browse-and-count — this is arithmetic on watermarks the
            // broker reports, so the depth ceiling does not apply and there is never an "N+".
            return new DepthOutcome(retained, true, depthNote(partitions.size()));
        });
    }

    // ------------------------------------------------------------------ purge

    @Override
    public PurgeOutcome purgeDetailed(ConnectionProfile profile, String topic) {
        String password = passwordResolver.resolve(profile);
        String action = "purge the topic";

        // Deliberately not try-with-resources. Admin.close() defaults to waiting Long.MAX_VALUE
        // milliseconds and KafkaConsumer.close() to 30 seconds, either of which would outlast every
        // other bound in this class and hold the request thread after the work was already done.
        Consumer<String, byte[]> consumer = clientFactory.consumer(profile, password);
        Admin admin = clientFactory.admin(profile, password);
        try {
            List<TopicPartition> partitions = partitionsOf(consumer, topic);
            Map<TopicPartition, Long> begins = consumer.beginningOffsets(partitions, apiTimeout());
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, apiTimeout());

            Map<TopicPartition, RecordsToDelete> toDelete = new HashMap<>();
            for (TopicPartition partition : partitions) {
                toDelete.put(partition, RecordsToDelete.beforeOffset(offset(ends, partition)));
            }

            Map<TopicPartition, KafkaFuture<DeletedRecords>> results = admin
                    .deleteRecords(toDelete, new DeleteRecordsOptions().timeoutMs(apiTimeoutMs()))
                    .lowWatermarks();

            long removed = 0;
            List<TopicPartition> failed = new ArrayList<>();
            Throwable firstFailure = null;
            for (Map.Entry<TopicPartition, KafkaFuture<DeletedRecords>> result : results.entrySet()) {
                try {
                    DeletedRecords deleted = result.getValue().get(apiTimeoutMs(), TimeUnit.MILLISECONDS);
                    // The real count, from the low watermark the broker actually moved to — not the
                    // end offset we asked for, which it is free not to reach.
                    removed += Math.max(0, deleted.lowWatermark() - offset(begins, result.getKey()));
                } catch (Exception e) {
                    failed.add(result.getKey());
                    firstFailure = firstFailure == null ? e : firstFailure;
                }
            }

            if (!failed.isEmpty()) {
                throw partialPurge(profile, action, removed, failed.size(), partitions.size(),
                        firstFailure);
            }

            log.info("Purged {} record(s) from '{}' across {} partition(s)", removed, topic,
                    partitions.size());
            return new PurgeOutcome(removed, PurgeOutcome.StopReason.QUEUE_EMPTY,
                    purgeNote(partitions.size()));
        } catch (MqOperationException e) {
            throw e;
        } catch (Exception e) {
            throw errorTranslator.translate(e, profile, action);
        } finally {
            closeQuietly(() -> admin.close(closeTimeout()));
            closeQuietly(() -> consumer.close(closeTimeout()));
        }
    }

    // ------------------------------------------------------------------ list destinations

    /**
     * Every topic this user may describe.
     *
     * <p>The only provider with no {@code DestinationLister}: Kafka has no {@code jakarta.jms} API and
     * so no connection-factory registry to hang one off, and the admin client is already here.
     *
     * <p>{@code listInternal(true)} plus the per-entry {@code internal} flag beats hiding internal
     * topics server-side: {@code TopicListing.isInternal()} is the broker's own answer, so
     * {@code __consumer_offsets} is marked rather than guessed at from its name, and the UI toggle that
     * reveals it is discoverable.
     */
    @Override
    public DestinationListing listDestinations(ConnectionProfile profile, DestinationQuery query) {
        MqManagerProperties.Destinations settings = properties.getDestinations();
        int limit = query.limit() <= 0
                ? settings.getDefaultLimit()
                : Math.min(query.limit(), settings.getMaxLimit());

        Admin admin = clientFactory.admin(profile, passwordResolver.resolve(profile));
        try {
            Map<String, TopicListing> listings = admin
                    .listTopics(new ListTopicsOptions().timeoutMs(apiTimeoutMs()).listInternal(true))
                    .namesToListings()
                    .get(apiTimeoutMs(), TimeUnit.MILLISECONDS);

            List<DestinationEntry> found = new ArrayList<>(listings.size());
            for (TopicListing listing : listings.values()) {
                // Every Kafka destination is a topic. There is no queue to distinguish it from.
                found.add(new DestinationEntry(listing.name(), DestinationKind.TOPIC,
                        listing.isInternal()));
            }
            return DestinationListings.finish(found, query.kind(), query.prefix(), limit, SOURCE,
                    "Kafka has topics, never queues. Internal topics such as __consumer_offsets are "
                            + "marked as the broker reports them, not guessed at from their names.");

        } catch (ExecutionException e) {
            // The cluster answered and said no. That is broker policy, not a broken connection, so it
            // comes back as a listing that admits it rather than as an error response.
            Throwable cause = e.getCause();
            if (cause instanceof AuthorizationException) {
                return DestinationListing.unavailable(limit, SOURCE,
                        DestinationListing.NOT_PERMITTED,
                        "This user is connected but is not allowed to describe the cluster, so its "
                                + "topics cannot be listed. Type a topic name instead — reading one "
                                + "topic needs only Describe and Read on that topic.");
            }
            if (cause instanceof org.apache.kafka.common.errors.TimeoutException) {
                return DestinationListing.unavailable(limit, SOURCE, DestinationListing.TIMED_OUT,
                        "The cluster did not return its topic list within " + apiTimeoutMs() + " ms.");
            }
            throw errorTranslator.translate(e, profile, LIST_ACTION);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw errorTranslator.translate(e, profile, LIST_ACTION);
        } catch (MqOperationException e) {
            throw e;
        } catch (Exception e) {
            throw errorTranslator.translate(e, profile, LIST_ACTION);
        } finally {
            closeQuietly(() -> admin.close(closeTimeout()));
        }
    }

    // ------------------------------------------------------------------ delete one

    /**
     * Always refuses. A Kafka partition is an append-only log: there is no operation that removes one
     * record and leaves its neighbours, at any offset, with any permission.
     *
     * <p>Reported rather than attempted, and 501 rather than 409, because this is not a state the
     * caller can wait out or work around — the button that would call this is hidden in the UI.
     */
    @Override
    public DeleteOutcome deleteMessageDetailed(ConnectionProfile profile, String topic,
            String messageId) {
        throw new MqOperationException("OPERATION_NOT_SUPPORTED", HttpStatus.NOT_IMPLEMENTED,
                "Kafka records cannot be deleted individually; the log is immutable. "
                        + "Purge truncates the topic instead.");
    }

    // ------------------------------------------------------------------ plumbing

    private <T> T withConsumer(ConnectionProfile profile, String action,
            Function<Consumer<String, byte[]>, T> operation) {
        return withConsumer(profile, passwordResolver.resolve(profile), action, operation);
    }

    private <T> T withConsumer(ConnectionProfile profile, String password, String action,
            Function<Consumer<String, byte[]>, T> operation) {
        Consumer<String, byte[]> consumer = clientFactory.consumer(profile, password);
        try {
            return operation.apply(consumer);
        } catch (MqOperationException e) {
            throw e;
        } catch (Exception e) {
            throw errorTranslator.translate(e, profile, action);
        } finally {
            closeQuietly(() -> consumer.close(closeTimeout()));
        }
    }

    private <T> T withProducer(ConnectionProfile profile, String action,
            ThrowingFunction<Producer<String, byte[]>, T> operation) {
        Producer<String, byte[]> producer = clientFactory.producer(profile,
                passwordResolver.resolve(profile));
        try {
            return operation.apply(producer);
        } catch (MqOperationException e) {
            throw e;
        } catch (Exception e) {
            throw errorTranslator.translate(e, profile, action);
        } finally {
            // close(Duration), never the no-arg form: that one waits out the full delivery timeout.
            closeQuietly(() -> producer.close(closeTimeout()));
        }
    }

    /**
     * Assignment, never subscription. {@code subscribe} would need a group id, join a consumer group
     * and rebalance it — all of which a read-only browse has no business doing.
     */
    private List<TopicPartition> assignAllPartitions(Consumer<String, byte[]> consumer, String topic) {
        List<TopicPartition> partitions = partitionsOf(consumer, topic);
        consumer.assign(partitions);
        return partitions;
    }

    private List<TopicPartition> partitionsOf(Consumer<String, byte[]> consumer, String topic) {
        List<PartitionInfo> info = consumer.partitionsFor(topic, apiTimeout());
        if (info == null || info.isEmpty()) {
            // An unknown topic answers with no partitions rather than an error when the broker is
            // willing to auto-create; either way there is nothing here to read.
            throw new MqOperationException("QUEUE_NOT_FOUND", HttpStatus.NOT_FOUND,
                    "Topic '" + topic + "' has no partitions visible to this user — it may not exist, "
                            + "or the user may lack Describe permission on it.");
        }
        return info.stream()
                .map(partition -> new TopicPartition(partition.topic(), partition.partition()))
                .toList();
    }

    private void requirePartitionExists(Consumer<String, byte[]> consumer, KafkaRecordId id,
            TopicPartition partition) {
        if (!partitionsOf(consumer, id.topic()).contains(partition)) {
            throw new MqOperationException("MESSAGE_NOT_FOUND", HttpStatus.NOT_FOUND,
                    "Topic '" + id.topic() + "' has no partition " + id.partition() + ".");
        }
    }

    /** True once every assigned partition's read position has caught up with its end offset. */
    private static boolean allPartitionsDrained(Consumer<String, byte[]> consumer,
            List<TopicPartition> partitions, Map<TopicPartition, Long> ends) {
        for (TopicPartition partition : partitions) {
            if (consumer.position(partition) < offset(ends, partition)) {
                return false;
            }
        }
        return true;
    }

    private MqOperationException partialPurge(ConnectionProfile profile, String action, long removed,
            int failedCount, int total, Throwable firstFailure) {
        MqOperationException translated = errorTranslator.translate(firstFailure, profile, action);
        return new MqOperationException("PURGE_PARTIAL", HttpStatus.CONFLICT,
                "Removed " + removed + " record(s), but " + failedCount + " of " + total
                        + " partition(s) could not be truncated, so the topic is not empty. "
                        + translated.getMessage(),
                firstFailure);
    }

    private static MqOperationException notFound(KafkaRecordId id, String because) {
        return new MqOperationException("MESSAGE_NOT_FOUND", HttpStatus.NOT_FOUND,
                "No record at offset " + id.offset() + " of partition " + id.partition() + " — "
                        + because + ".");
    }

    private static String browseNote() {
        return "Read from the start of every partition without committing an offset and without a "
                + "consumer group, so browsing cannot disturb real consumers. Only records still "
                + "inside the topic's retention window exist to be read.";
    }

    private static String depthNote(int partitions) {
        return "This counts records retained on the topic, not records waiting to be consumed: end "
                + "offset minus start offset, summed over " + partitions + " partition(s). Records "
                + "consumers have already read still count until retention removes them, and a "
                + "compacted topic counts only the surviving version of each key.";
    }

    private static String purgeNote(int partitions) {
        return "Truncated " + partitions + " partition(s) by moving the log start offset to the end. "
                + "Records produced after that point are unaffected, and Kafka reclaims the disk space "
                + "asynchronously.";
    }

    private static long offset(Map<TopicPartition, Long> offsets, TopicPartition partition) {
        Long value = offsets.get(partition);
        return value == null ? 0L : value;
    }

    private static String emptyToNull(String key) {
        return key == null || key.isEmpty() ? null : key;
    }

    private int clampLimit(int requested) {
        MqManagerProperties.Browse browse = properties.getBrowse();
        if (requested <= 0) {
            return browse.getDefaultLimit();
        }
        return Math.min(requested, browse.getMaxLimit());
    }

    private Duration apiTimeout() {
        return properties.getKafka().getApiTimeout();
    }

    private int apiTimeoutMs() {
        return Math.toIntExact(apiTimeout().toMillis());
    }

    private Duration pollTimeout() {
        return properties.getKafka().getPollTimeout();
    }

    private Duration closeTimeout() {
        return properties.getKafka().getCloseTimeout();
    }

    /** A failure to close must never replace the real result, or the error that was already thrown. */
    private static void closeQuietly(Runnable close) {
        try {
            close.run();
        } catch (RuntimeException e) {
            log.debug("Closing a Kafka client failed", e);
        }
    }

    private void logSend(String topic, String body, RecordMetadata metadata) {
        log.info("Sent {} character(s) to '{}' partition {} offset {}",
                body == null ? 0 : body.length(), topic, metadata.partition(), metadata.offset());
        if (properties.isLogPayloads() && log.isDebugEnabled()) {
            log.debug("Payload sent to '{}': {}", topic, body);
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T input) throws Exception;
    }
}
