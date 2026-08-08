package com.baysansoft.mqmanager.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.baysansoft.mqmanager.messaging.MessagingOperations;
import com.baysansoft.mqmanager.service.ConnectionProfileService;

/** The validation matrix and the error contract, at the HTTP layer. */
@WebMvcTest(controllers = {ConnectionController.class, QueueController.class})
class ConnectionControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConnectionProfileService service;

    @MockitoBean
    private MessagingOperations messaging;

    @Test
    @DisplayName("IBM MQ without a queue manager or channel is rejected, naming both fields")
    void ibmMqRequiresQueueManagerAndChannel() throws Exception {
        mockMvc.perform(post("/api/connections")
                        .contentType("application/json")
                        .content("""
                                {"name":"ibm","provider":"IBM_MQ","host":"h","port":1414}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("queueManagerName"),
                                org.hamcrest.Matchers.containsString("channel"))));
    }

    @Test
    @DisplayName("a broker URL override is rejected for IBM MQ, which has no such concept")
    void ibmMqRejectsBrokerUrlOverride() throws Exception {
        mockMvc.perform(post("/api/connections")
                        .contentType("application/json")
                        .content("""
                                {"name":"ibm","provider":"IBM_MQ","host":"h","port":1414,
                                 "queueManagerName":"QM1","channel":"DEV.APP.SVRCONN",
                                 "brokerUrlOverride":"tcp://elsewhere:61616"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("brokerUrlOverride")));
    }

    @Test
    @DisplayName("an Apache profile needs either host and port or a broker URL")
    void apacheRequiresHostAndPortOrOverride() throws Exception {
        mockMvc.perform(post("/api/connections")
                        .contentType("application/json")
                        .content("""
                                {"name":"amq","provider":"ACTIVE_MQ"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("broker URL override")));
    }

    @Test
    @DisplayName("Kafka without bootstrap servers is rejected, naming the field it actually needs")
    void kafkaRequiresBootstrapServers() throws Exception {
        mockMvc.perform(post("/api/connections")
                        .contentType("application/json")
                        .content("""
                                {"name":"kafka","provider":"KAFKA"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("bootstrapServers")));
    }

    @Test
    @DisplayName("a bootstrap entry without a port is rejected before it becomes a confusing timeout")
    void kafkaBootstrapServersMustCarryPorts() throws Exception {
        mockMvc.perform(post("/api/connections")
                        .contentType("application/json")
                        .content("""
                                {"name":"kafka","provider":"KAFKA","bootstrapServers":"broker1:9092,broker2"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("host:port")));
    }

    @Test
    @DisplayName("host and port are rejected for Kafka, so a row never carries two addressing styles")
    void kafkaRejectsHostAndPort() throws Exception {
        mockMvc.perform(post("/api/connections")
                        .contentType("application/json")
                        .content("""
                                {"name":"kafka","provider":"KAFKA","bootstrapServers":"broker:9092",
                                 "host":"broker","port":9092}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("host"),
                                org.hamcrest.Matchers.containsString("port"))));
    }

    @Test
    @DisplayName("queue manager, channel and broker URL override are all rejected for Kafka")
    void kafkaRejectsJmsOnlyFields() throws Exception {
        mockMvc.perform(post("/api/connections")
                        .contentType("application/json")
                        .content("""
                                {"name":"kafka","provider":"KAFKA","bootstrapServers":"broker:9092",
                                 "queueManagerName":"QM1","channel":"DEV.APP.SVRCONN",
                                 "brokerUrlOverride":"tcp://elsewhere:61616"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("queueManagerName"),
                                org.hamcrest.Matchers.containsString("channel"),
                                org.hamcrest.Matchers.containsString("brokerUrlOverride"))));
    }

    @Test
    @DisplayName("bootstrap servers are rejected for the JMS providers, symmetrically")
    void jmsProvidersRejectBootstrapServers() throws Exception {
        mockMvc.perform(post("/api/connections")
                        .contentType("application/json")
                        .content("""
                                {"name":"amq","provider":"ACTIVE_MQ","host":"h","port":61616,
                                 "bootstrapServers":"broker:9092"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("bootstrapServers"),
                                // Derived from the capability, not hard-coded, so it stays right.
                                org.hamcrest.Matchers.containsString("only applies to Apache Kafka"))));
    }

    @Test
    @DisplayName("a valid Kafka profile passes validation and reaches the service")
    void kafkaWithBootstrapServersIsAccepted() throws Exception {
        org.mockito.Mockito.when(service.create(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.baysansoft.mqmanager.web.dto.ConnectionProfileResponse(
                        1L, "kafka", com.baysansoft.mqmanager.domain.Provider.KAFKA, "Apache Kafka",
                        null, null, null, false, true, null, "broker1:9092, broker2:9092", null, null,
                        java.time.Instant.EPOCH, java.time.Instant.EPOCH));

        mockMvc.perform(post("/api/connections")
                        .contentType("application/json")
                        .content("""
                                {"name":"kafka","provider":"KAFKA",
                                 "bootstrapServers":"broker1:9092, broker2:9092"}"""))
                .andExpect(status().isCreated())
                // Whitespace after a comma is normal in a pasted bootstrap list and must not fail it.
                .andExpect(jsonPath("$.bootstrapServers").value("broker1:9092, broker2:9092"))
                // The response type has no password member at all, for any provider.
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    @DisplayName("a missing name is rejected")
    void nameIsRequired() throws Exception {
        mockMvc.perform(post("/api/connections")
                        .contentType("application/json")
                        .content("""
                                {"provider":"ACTIVE_MQ","host":"h","port":61616}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")));
    }

    @Test
    @DisplayName("a queue operation without a queue name is a 400, not a 500")
    void queueNameIsRequired() throws Exception {
        mockMvc.perform(get("/api/connections/1/queue/depth"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("errors always carry the three contract fields, never a stack trace")
    void errorShapeIsConsistent() throws Exception {
        mockMvc.perform(post("/api/connections")
                        .contentType("application/json")
                        .content("""
                                {"provider":"ACTIVE_MQ"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }
}
