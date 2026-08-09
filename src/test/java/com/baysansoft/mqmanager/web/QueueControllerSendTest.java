package com.baysansoft.mqmanager.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.messaging.MessagingOperations;
import com.baysansoft.mqmanager.messaging.model.MessageType;
import com.baysansoft.mqmanager.messaging.model.OutboundMessage;
import com.baysansoft.mqmanager.service.ConnectionProfileService;

/**
 * What the send endpoint does with a message type, at the HTTP layer.
 *
 * <p>The messaging layer is mocked: the question here is what reaches it, and what an unusable value
 * looks like coming back out — not what a broker then does with it.
 */
@WebMvcTest(controllers = QueueController.class)
class QueueControllerSendTest {

    private static final String URL = "/api/connections/1/queue/messages?queueName=orders";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConnectionProfileService profiles;

    @MockitoBean
    private MessagingOperations messaging;

    @BeforeEach
    void setUp() {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setId(1L);
        profile.setProvider(Provider.ACTIVE_MQ);
        when(profiles.require(1L)).thenReturn(profile);
        when(messaging.send(any(), any(), any(OutboundMessage.class))).thenReturn("ID:1");
    }

    private OutboundMessage captureSend() {
        ArgumentCaptor<OutboundMessage> captor = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(messaging).send(any(), eq("orders"), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a send body with no message type reaches the messaging layer asking for nothing, "
            + "which is what keeps it a text message on JMS and acceptable on Kafka")
    void absentMessageTypeStaysNull() throws Exception {
        mockMvc.perform(post(URL).contentType("application/json")
                        .content("""
                                {"payload":"hello"}"""))
                .andExpect(status().isCreated());

        OutboundMessage sent = captureSend();
        assertThat(sent.messageType()).isNull();
        assertThat(sent.body()).isEqualTo("hello");
    }

    @Test
    @DisplayName("a lower-case message type is accepted, since nothing is gained by being strict about it")
    void lowerCaseMessageTypeIsAccepted() throws Exception {
        mockMvc.perform(post(URL).contentType("application/json")
                        .content("""
                                {"payload":"hello","messageType":"bytes"}"""))
                .andExpect(status().isCreated());

        assertThat(captureSend().messageType()).isEqualTo(MessageType.BYTES);
    }

    @Test
    @DisplayName("an unknown message type is a 400 that names the two valid values, not a 500 and not "
            + "Jackson's own message naming an internal type")
    void unknownMessageTypeIsABadRequest() throws Exception {
        mockMvc.perform(post(URL).contentType("application/json")
                        .content("""
                                {"payload":"hello","messageType":"BINARY"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("TEXT")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("BYTES")))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("com.baysansoft"))));
    }

    @Test
    @DisplayName("a payload is still required, and offering a message type does not change that")
    void payloadIsStillRequired() throws Exception {
        mockMvc.perform(post(URL).contentType("application/json")
                        .content("""
                                {"messageType":"BYTES"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("properties and a message type travel together, so choosing bytes does not quietly "
            + "drop the properties typed beside the body")
    void propertiesSurviveABytesSend() throws Exception {
        mockMvc.perform(post(URL).contentType("application/json")
                        .content("""
                                {"payload":"hello","messageType":"BYTES","properties":{"tenant":"acme"}}"""))
                .andExpect(status().isCreated());

        OutboundMessage sent = captureSend();
        assertThat(sent.messageType()).isEqualTo(MessageType.BYTES);
        assertThat(sent.properties()).containsEntry("tenant", "acme");
    }
}
