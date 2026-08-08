package com.baysansoft.mqmanager.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.record.TimestampType;
import org.springframework.stereotype.Component;

import com.baysansoft.mqmanager.messaging.model.QueueMessageView;

/**
 * Converts a Kafka record into the shared view model.
 *
 * <p>The split between the two maps is deliberate and is the mirror image of what {@code send} does:
 *
 * <ul>
 *   <li><strong>headers</strong> carries the record's <em>coordinates</em> — partition, offset, key,
 *       timestamp — which is the closest thing Kafka has to the JMS header fields.
 *   <li><strong>properties</strong> carries the record's own Kafka headers, which is where
 *       application-set metadata lives on both sides. What the send form calls properties comes back
 *       as properties.
 * </ul>
 *
 * <p>Fields Kafka has no equivalent for — correlation id, priority, redelivered — are left null rather
 * than filled with a plausible-looking default.
 */
@Component
public class KafkaRecordMapper {

    public QueueMessageView toView(ConsumerRecord<String, byte[]> record, int maxBodyBytes) {
        Body body = renderBody(record.value(), maxBodyBytes);

        return new QueueMessageView(
                KafkaRecordId.of(record.topic(), record.partition(), record.offset()).toString(),
                null,
                record.timestamp() > 0 ? Instant.ofEpochMilli(record.timestamp()) : null,
                null,
                null,
                null,
                body.text(),
                body.truncated(),
                readCoordinates(record),
                readHeaders(record),
                body.note());
    }

    private static Map<String, String> readCoordinates(ConsumerRecord<String, byte[]> record) {
        Map<String, String> coordinates = new LinkedHashMap<>();
        coordinates.put("KafkaTopic", record.topic());
        coordinates.put("KafkaPartition", String.valueOf(record.partition()));
        coordinates.put("KafkaOffset", String.valueOf(record.offset()));
        if (record.key() != null) {
            coordinates.put("KafkaKey", record.key());
        }
        coordinates.put("KafkaTimestamp", String.valueOf(record.timestamp()));
        if (record.timestampType() != null && record.timestampType() != TimestampType.NO_TIMESTAMP_TYPE) {
            // CreateTime means the producer set it; LogAppendTime means the broker overwrote it. The
            // difference matters when someone is reading the timestamp as "when this was produced".
            coordinates.put("KafkaTimestampType", record.timestampType().name);
        }
        coordinates.put("KafkaSerializedValueSize", String.valueOf(record.serializedValueSize()));
        return coordinates;
    }

    /**
     * Kafka header keys are not unique — the same key may legitimately appear more than once. A map
     * cannot hold that, so repeats are joined rather than silently overwritten.
     */
    private static Map<String, String> readHeaders(ConsumerRecord<String, byte[]> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            String value = header.value() == null
                    ? null
                    : new String(header.value(), StandardCharsets.UTF_8);
            headers.merge(header.key(), value, (existing, added) -> existing + ", " + added);
        }
        return headers;
    }

    private static Body renderBody(byte[] value, int maxBodyBytes) {
        if (value == null) {
            // Not the same as an empty record. A null value is a tombstone, and on a compacted topic it
            // is an instruction to delete the key — worth naming rather than rendering as "".
            return new Body(null, false, "Tombstone — this record has a null value. On a compacted "
                    + "topic that marks the key for removal.");
        }
        int readable = Math.min(value.length, maxBodyBytes);
        // Decoded with replacement rather than strictly: a binary payload must still render as something
        // rather than failing the whole browse.
        String text = new String(value, 0, readable, StandardCharsets.UTF_8);
        boolean truncated = value.length > readable;
        return new Body(text, truncated, null);
    }

    private record Body(String text, boolean truncated, String note) {
    }
}
