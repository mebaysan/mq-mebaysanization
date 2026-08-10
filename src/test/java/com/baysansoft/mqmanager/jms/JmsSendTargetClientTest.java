package com.baysansoft.mqmanager.jms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.messaging.model.OutboundMessage;
import com.baysansoft.mqmanager.messaging.model.TargetClient;
import com.baysansoft.mqmanager.support.MessagingTestFixture;
import com.baysansoft.mqmanager.web.MqOperationException;

/**
 * The two rules that decide whether a send is even attempted once a target client is named.
 *
 * <p>Both are pre-flight: they throw before {@code execute} opens anything, which is why these profiles
 * name no broker at all and the class needs neither an embedded broker nor a network. A profile with no
 * host is also the proof — if a guard stopped firing, the failure would arrive as a connection error
 * rather than as the code asserted here.
 */
class JmsSendTargetClientTest {

    private final JmsMessagingOperations messaging = MessagingTestFixture.operations();

    private static ConnectionProfile profile(Provider provider) {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setId(1L);
        profile.setName("unreachable-" + provider);
        profile.setProvider(provider);
        return profile;
    }

    @Test
    @DisplayName("only IBM MQ declares the capability the guard consults, so the option is gated on a "
            + "behavioural fact rather than on the shared code branching on a provider constant")
    void onlyIbmMqDeclaresTheCapability() {
        assertThat(Provider.IBM_MQ.supportsTargetClient()).isTrue();
        assertThat(Provider.ACTIVE_MQ.supportsTargetClient()).isFalse();
        assertThat(Provider.ARTEMIS.supportsTargetClient()).isFalse();
        assertThat(Provider.KAFKA.supportsTargetClient()).isFalse();
    }

    @Test
    @DisplayName("a target client is refused on both Apache brokers and for both values, since a send "
            + "that reported success would look like the header choice had been honoured")
    void targetClientIsRefusedOnAProviderThatHasNoRfh2ToSuppress() {
        for (Provider provider : new Provider[] {Provider.ACTIVE_MQ, Provider.ARTEMIS}) {
            for (TargetClient targetClient : TargetClient.values()) {
                OutboundMessage outbound = new OutboundMessage("body", Map.of(), null, null, targetClient);

                assertThatThrownBy(() -> messaging.send(profile(provider), "Q", outbound))
                        .isInstanceOfSatisfying(MqOperationException.class, e -> {
                            assertThat(e.getCode()).isEqualTo("OPERATION_NOT_SUPPORTED");
                            assertThat(e.getMessage()).contains(provider.displayName());
                        });
            }
        }
    }

    @Test
    @DisplayName("target client MQ together with custom properties is refused outright — with no MQRFH2 "
            + "there is no usr folder to carry them, and a returned message id would say they had travelled")
    void targetClientMqWithCustomPropertiesIsRefusedBeforeAnyConnectionIsOpened() {
        OutboundMessage outbound =
                new OutboundMessage("body", Map.of("tenant", "acme"), null, null, TargetClient.MQ);

        assertThatThrownBy(() -> messaging.send(profile(Provider.IBM_MQ), "Q", outbound))
                .isInstanceOfSatisfying(MqOperationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("OPERATION_NOT_SUPPORTED");
                    assertThat(e.getMessage()).contains("MQRFH2").contains("JMS");
                });
    }

    @Test
    @DisplayName("target client JMS and custom properties are a legitimate pair, so the pre-flight lets "
            + "them past and whatever fails afterwards is the broker rather than the request")
    void targetClientJmsWithCustomPropertiesIsNotRefusedByThePreflight() {
        OutboundMessage outbound =
                new OutboundMessage("body", Map.of("tenant", "acme"), null, null, TargetClient.JMS);

        // It still fails — the profile names no queue manager to reach — but on the way out rather than
        // on the way in. Asserting the code is *not* the refusal is the whole point: it proves the
        // properties rule is scoped to MQ and does not quietly ban properties on IBM MQ altogether.
        assertThatThrownBy(() -> messaging.send(profile(Provider.IBM_MQ), "Q", outbound))
                .isInstanceOf(MqOperationException.class)
                .extracting(thrown -> ((MqOperationException) thrown).getCode())
                .isNotEqualTo("OPERATION_NOT_SUPPORTED");
    }

    @Test
    @DisplayName("the capability rule is checked before the properties rule, so a caller who aimed the "
            + "option at the wrong broker is told that instead of being told about a header it has not got")
    void theCapabilityRuleIsCheckedFirst() {
        OutboundMessage outbound =
                new OutboundMessage("body", Map.of("tenant", "acme"), null, null, TargetClient.MQ);

        assertThatThrownBy(() -> messaging.send(profile(Provider.ACTIVE_MQ), "Q", outbound))
                .hasMessageContaining(Provider.ACTIVE_MQ.displayName())
                .hasMessageNotContaining("usr folder");
    }
}
