package com.baysansoft.mqmanager.messaging.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parse rules, which are what stand between a typo in a request body and a 500.
 */
class MessageTypeTest {

    @Test
    @DisplayName("a message type is parsed leniently, so 'bytes' and ' BYTES ' are the same value")
    void parsesLeniently() {
        assertThat(MessageType.parse("bytes")).isEqualTo(MessageType.BYTES);
        assertThat(MessageType.parse("  BYTES  ")).isEqualTo(MessageType.BYTES);
        assertThat(MessageType.parse("Text")).isEqualTo(MessageType.TEXT);
    }

    @Test
    @DisplayName("an unknown message type names both valid values, so the 400 tells the caller what to send")
    void unknownValueNamesTheValidOnes() {
        assertThatThrownBy(() -> MessageType.parse("BINARY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BINARY")
                .hasMessageContaining("TEXT")
                .hasMessageContaining("BYTES");
    }

    @Test
    @DisplayName("a null message type is rejected by parse rather than throwing a NullPointerException, "
            + "which would surface as a 500 instead of a 400")
    void nullIsAnIllegalArgumentNotANullPointer() {
        assertThatThrownBy(() -> MessageType.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an absent message type parses to null, which is what keeps every send written before "
            + "the choice existed a text message")
    void absentValueIsNull() {
        assertThat(MessageType.parseOptional(null)).isNull();
        assertThat(MessageType.parseOptional("")).isNull();
        assertThat(MessageType.parseOptional("   ")).isNull();
    }

    @Test
    @DisplayName("a present message type still parses through parseOptional, typo and all")
    void presentValueStillParses() {
        assertThat(MessageType.parseOptional(" bytes ")).isEqualTo(MessageType.BYTES);
        assertThatThrownBy(() -> MessageType.parseOptional("BINARY"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
