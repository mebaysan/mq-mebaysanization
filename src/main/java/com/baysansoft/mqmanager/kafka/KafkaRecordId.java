package com.baysansoft.mqmanager.kafka;

import org.springframework.http.HttpStatus;

import com.baysansoft.mqmanager.web.MqOperationException;

/**
 * The synthetic id this tool gives a Kafka record: {@code topic-partition-offset}.
 *
 * <p>Kafka records have no id. Identity is the triple {@code (topic, partition, offset)}, so the id is
 * built from it rather than invented — which is what lets a single-message fetch seek straight to the
 * record instead of scanning for it.
 *
 * <p>Parsing reads from the <em>right</em>, because topic names legitimately contain hyphens:
 * {@code orders-eu-0-42} is topic {@code orders-eu}, partition 0, offset 42. Reading from the left
 * would silently mis-parse every hyphenated topic in existence.
 */
public record KafkaRecordId(String topic, int partition, long offset) {

    public static KafkaRecordId of(String topic, int partition, long offset) {
        return new KafkaRecordId(topic, partition, offset);
    }

    @Override
    public String toString() {
        return topic + "-" + partition + "-" + offset;
    }

    /**
     * @throws MqOperationException {@code MESSAGE_ID_INVALID} 400 when the id is not one this tool
     *                              produced, or names a different topic than the one being browsed
     */
    public static KafkaRecordId parse(String messageId, String expectedTopic) {
        KafkaRecordId parsed = parse(messageId);
        if (!parsed.topic().equals(expectedTopic)) {
            throw invalid("it names topic '" + parsed.topic() + "', not '" + expectedTopic + "'");
        }
        return parsed;
    }

    private static KafkaRecordId parse(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw invalid("it is empty");
        }
        int lastHyphen = messageId.lastIndexOf('-');
        if (lastHyphen <= 0) {
            throw invalid("it has no partition or offset");
        }
        int secondLastHyphen = messageId.lastIndexOf('-', lastHyphen - 1);
        if (secondLastHyphen <= 0) {
            throw invalid("it has no partition");
        }

        long offset;
        int partition;
        try {
            partition = Integer.parseInt(messageId.substring(secondLastHyphen + 1, lastHyphen));
            offset = Long.parseLong(messageId.substring(lastHyphen + 1));
        } catch (NumberFormatException e) {
            throw invalid("the partition and offset must both be numbers");
        }
        if (partition < 0 || offset < 0) {
            throw invalid("the partition and offset cannot be negative");
        }
        return new KafkaRecordId(messageId.substring(0, secondLastHyphen), partition, offset);
    }

    private static MqOperationException invalid(String because) {
        return new MqOperationException("MESSAGE_ID_INVALID", HttpStatus.BAD_REQUEST,
                "A Kafka message id looks like topic-partition-offset; " + because + ".");
    }
}
