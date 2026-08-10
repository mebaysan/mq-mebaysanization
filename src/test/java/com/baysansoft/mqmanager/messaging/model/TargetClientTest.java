package com.baysansoft.mqmanager.messaging.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parse rules, which are what stand between a typo in a request body and a 500.
 */
class TargetClientTest {

    @Test
    @DisplayName("a target client is parsed leniently, so 'mq' and ' MQ ' are the same value")
    void parsesLeniently() {
        assertThat(TargetClient.parse("mq")).isEqualTo(TargetClient.MQ);
        assertThat(TargetClient.parse("  MQ  ")).isEqualTo(TargetClient.MQ);
        assertThat(TargetClient.parse("Jms")).isEqualTo(TargetClient.JMS);
    }

    @Test
    @DisplayName("an unknown target client names both valid values, so the 400 tells the caller what to send")
    void unknownValueNamesTheValidOnes() {
        assertThatThrownBy(() -> TargetClient.parse("RFH2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RFH2")
                .hasMessageContaining("JMS")
                .hasMessageContaining("MQ");
    }

    @Test
    @DisplayName("a null target client is rejected by parse rather than throwing a NullPointerException, "
            + "which would surface as a 500 instead of a 400")
    void nullIsAnIllegalArgumentNotANullPointer() {
        assertThatThrownBy(() -> TargetClient.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an absent target client parses to null, which is what leaves IBM MQ at its own default "
            + "for every send written before the choice existed")
    void absentValueIsNull() {
        assertThat(TargetClient.parseOptional(null)).isNull();
        assertThat(TargetClient.parseOptional("")).isNull();
        assertThat(TargetClient.parseOptional("   ")).isNull();
    }

    @Test
    @DisplayName("a present target client still parses through parseOptional, typo and all")
    void presentValueStillParses() {
        assertThat(TargetClient.parseOptional(" mq ")).isEqualTo(TargetClient.MQ);
        assertThatThrownBy(() -> TargetClient.parseOptional("RFH2"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
