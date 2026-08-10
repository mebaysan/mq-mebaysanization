package com.baysansoft.mqmanager.messaging.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The normalisation done once here rather than twice in the two implementations. Each rule below
 * preserves behaviour the send path already had before this record existed.
 */
class OutboundMessageTest {

    @Test
    @DisplayName("a null body becomes an empty one, so an empty textarea can never send a Kafka tombstone")
    void nullBodyBecomesEmpty() {
        assertThat(new OutboundMessage(null, Map.of(), null, null, null).body()).isEmpty();
    }

    @Test
    @DisplayName("null properties become an empty map, so neither implementation has to null-check them")
    void nullPropertiesBecomeEmpty() {
        assertThat(new OutboundMessage("body", null, null, null, null).properties()).isEmpty();
    }

    @Test
    @DisplayName("a property with a null value survives construction — Map.copyOf would have thrown, and "
            + "{\"properties\":{\"a\":null}} is a request a user can send")
    void nullPropertyValueSurvives() {
        Map<String, String> withNull = new HashMap<>();
        withNull.put("a", null);

        assertThatCode(() -> new OutboundMessage("body", withNull, null, null, null))
                .doesNotThrowAnyException();
        assertThat(new OutboundMessage("body", withNull, null, null, null).properties())
                .containsEntry("a", null);
    }

    @Test
    @DisplayName("properties keep the order they were given, so a browse shows them as they were typed")
    void propertiesKeepTheirOrder() {
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("z", "1");
        ordered.put("a", "2");
        ordered.put("m", "3");

        assertThat(new OutboundMessage("body", ordered, null, null, null).properties().keySet())
                .containsExactly("z", "a", "m");
    }

    @Test
    @DisplayName("the properties map is defensively copied, so mutating the caller's map afterwards "
            + "cannot change what gets sent")
    void propertiesAreCopied() {
        Map<String, String> caller = new HashMap<>();
        caller.put("a", "1");
        OutboundMessage message = new OutboundMessage("body", caller, null, null, null);

        caller.put("b", "2");

        assertThat(message.properties()).containsOnlyKeys("a");
    }

    @Test
    @DisplayName("no message type at all reads as TEXT, which is what every send did before the choice "
            + "existed — while messageType() itself stays null so Kafka can tell nobody asked")
    void absentTypeReadsAsTextButStaysNull() {
        OutboundMessage message = new OutboundMessage("body", Map.of(), null, null, null);

        assertThat(message.messageType()).isNull();
        assertThat(message.messageTypeOrDefault()).isEqualTo(MessageType.TEXT);
    }

    @Test
    @DisplayName("no target client at all stays null, so IBM MQ keeps writing the MQRFH2 header exactly "
            + "as it did before the choice existed and the other three can tell that nobody asked")
    void absentTargetClientStaysNull() {
        OutboundMessage message = new OutboundMessage("body", Map.of(), null, null, null);

        // No targetClientOrDefault() to assert against, deliberately: unlike a message type, nothing
        // downstream needs a value in every case, and an "or default" would report JMS as a choice
        // somebody made.
        assertThat(message.targetClient()).isNull();
    }

    @Test
    @DisplayName("the text factory asks for TEXT explicitly rather than leaving it to the default, and "
            + "asks for nothing at all about the IBM MQ header")
    void textFactoryIsExplicit() {
        OutboundMessage message = OutboundMessage.text("body", Map.of("a", "1"));

        assertThat(message.messageType()).isEqualTo(MessageType.TEXT);
        assertThat(message.targetClient()).isNull();
        assertThat(message.key()).isNull();
        assertThat(message.properties()).containsEntry("a", "1");
    }
}
