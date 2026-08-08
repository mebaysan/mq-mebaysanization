package com.baysansoft.mqmanager.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.baysansoft.mqmanager.web.MqOperationException;

/**
 * The synthetic id is the only handle a Kafka record has, so getting it wrong would mean the
 * single-message view silently showed the wrong record.
 */
class KafkaRecordIdTest {

    @Test
    @DisplayName("an id round-trips through format and parse")
    void roundTrips() {
        KafkaRecordId id = KafkaRecordId.of("orders", 3, 4711);

        assertThat(id).hasToString("orders-3-4711");
        assertThat(KafkaRecordId.parse("orders-3-4711", "orders")).isEqualTo(id);
    }

    @Test
    @DisplayName("a topic name containing hyphens still parses, because the id is read from the right")
    void hyphenatedTopicNamesParse() {
        KafkaRecordId id = KafkaRecordId.parse("my-orders-topic-3-4711", "my-orders-topic");

        assertThat(id.topic()).isEqualTo("my-orders-topic");
        assertThat(id.partition()).isEqualTo(3);
        assertThat(id.offset()).isEqualTo(4711);
    }

    @Test
    @DisplayName("offset 0 of partition 0 is a real coordinate, not an absent one")
    void zeroIsValid() {
        assertThat(KafkaRecordId.parse("orders-0-0", "orders"))
                .isEqualTo(KafkaRecordId.of("orders", 0, 0));
    }

    @Test
    @DisplayName("an id naming a different topic is refused before any cluster call")
    void wrongTopicIsRejected() {
        assertInvalid(() -> KafkaRecordId.parse("payments-0-1", "orders"));
    }

    @Test
    @DisplayName("ids that are not topic-partition-offset are refused with a 400, not a parse crash")
    void malformedIdsAreRejected() {
        assertInvalid(() -> KafkaRecordId.parse("", "orders"));
        assertInvalid(() -> KafkaRecordId.parse(null, "orders"));
        assertInvalid(() -> KafkaRecordId.parse("orders", "orders"));
        assertInvalid(() -> KafkaRecordId.parse("orders-0", "orders"));
        assertInvalid(() -> KafkaRecordId.parse("orders-x-1", "orders"));
        assertInvalid(() -> KafkaRecordId.parse("orders-0-y", "orders"));
        assertInvalid(() -> KafkaRecordId.parse("orders-0--1", "orders"));
        // A JMS id offered to Kafka: the shape is wrong, and it must not be coerced into something.
        assertInvalid(() -> KafkaRecordId.parse("ID:414d5120514d31", "orders"));
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(MqOperationException.class, e -> {
            assertThat(e.getCode()).isEqualTo("MESSAGE_ID_INVALID");
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
    }
}
