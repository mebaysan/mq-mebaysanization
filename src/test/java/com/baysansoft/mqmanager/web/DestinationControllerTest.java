package com.baysansoft.mqmanager.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.DestinationKind;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.messaging.MessagingOperations;
import com.baysansoft.mqmanager.messaging.model.DestinationEntry;
import com.baysansoft.mqmanager.messaging.model.DestinationListing;
import com.baysansoft.mqmanager.messaging.model.DestinationQuery;
import com.baysansoft.mqmanager.service.ConnectionProfileService;

/**
 * The destination-listing contract at the HTTP layer.
 *
 * <p>The central assertion is that a broker refusing to be listed is a <strong>200</strong>. That is
 * deliberate and it is the same rule {@code POST /test} follows: the caller asked what is on the
 * broker, and "I am not permitted to tell you" answers the question. Only an unreachable broker is an
 * error status.
 */
@WebMvcTest(controllers = DestinationController.class)
class DestinationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConnectionProfileService profiles;

    @MockitoBean
    private MessagingOperations messaging;

    @BeforeEach
    void connectionExists() {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setId(1L);
        profile.setName("broker");
        profile.setProvider(Provider.ACTIVE_MQ);
        when(profiles.require(1L)).thenReturn(profile);
    }

    @Test
    @DisplayName("a listing is returned with its availability, source and entries")
    void returnsTheListing() throws Exception {
        when(messaging.listDestinations(any(), any())).thenReturn(DestinationListing.complete(
                List.of(new DestinationEntry("orders.new", DestinationKind.QUEUE, false)),
                500, "advisory topics", "a note"));

        mockMvc.perform(get("/api/connections/1/destinations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("COMPLETE"))
                .andExpect(jsonPath("$.returned").value(1))
                .andExpect(jsonPath("$.source").value("advisory topics"))
                .andExpect(jsonPath("$.destinations[0].name").value("orders.new"))
                .andExpect(jsonPath("$.destinations[0].kind").value("QUEUE"))
                .andExpect(jsonPath("$.destinations[0].internal").value(false))
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    @DisplayName("a broker that refuses to be listed is a 200 with an empty list and a reason, not an error")
    void refusalIsTwoHundred() throws Exception {
        when(messaging.listDestinations(any(), any())).thenReturn(DestinationListing.unavailable(
                500, "advisory topics", DestinationListing.INDISTINGUISHABLE, "advisories are off"));

        mockMvc.perform(get("/api/connections/1/destinations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.destinations.length()").value(0))
                .andExpect(jsonPath("$.reason").value("DESTINATION_LIST_INDISTINGUISHABLE"))
                // The client must have a sentence to show without composing one itself.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("would not list")))
                .andExpect(jsonPath("$.note").value("advisories are off"));
    }

    @Test
    @DisplayName("a broker that cannot be reached is still an error in the standard shape")
    void unreachableBrokerIsAnError() throws Exception {
        when(messaging.listDestinations(any(), any())).thenThrow(new MqOperationException(
                "BROKER_UNREACHABLE", HttpStatus.BAD_GATEWAY, "Could not reach the broker."));

        mockMvc.perform(get("/api/connections/1/destinations"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("BROKER_UNREACHABLE"))
                .andExpect(jsonPath("$.status").value(502));
    }

    @Test
    @DisplayName("kind and prefix reach the messaging layer as given, dots and all")
    void passesQueryThrough() throws Exception {
        when(messaging.listDestinations(any(), any()))
                .thenReturn(DestinationListing.complete(List.of(), 500, "advisory topics", null));

        mockMvc.perform(get("/api/connections/1/destinations")
                        .param("kind", "queue")
                        .param("prefix", "DEV.QUEUE.")
                        .param("limit", "10"))
                .andExpect(status().isOk());

        ArgumentCaptor<DestinationQuery> query = ArgumentCaptor.forClass(DestinationQuery.class);
        verify(messaging).listDestinations(any(), query.capture());
        org.assertj.core.api.Assertions.assertThat(query.getValue().kind())
                .isEqualTo(DestinationKind.QUEUE);
        org.assertj.core.api.Assertions.assertThat(query.getValue().prefix()).isEqualTo("DEV.QUEUE.");
        org.assertj.core.api.Assertions.assertThat(query.getValue().limit()).isEqualTo(10);
    }

    @Test
    @DisplayName("an unknown kind is a 400 naming the valid ones, not a silent fallback")
    void unknownKindIsRejected() throws Exception {
        mockMvc.perform(get("/api/connections/1/destinations").param("kind", "BANANA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("QUEUE, TOPIC or UNKNOWN")));
    }

    @Test
    @DisplayName("an unknown connection is the same 404 as everywhere else")
    void unknownConnectionIsNotFound() throws Exception {
        when(profiles.require(eq(99L)))
                .thenThrow(new NotFoundException("CONNECTION_NOT_FOUND", "No connection 99."));

        mockMvc.perform(get("/api/connections/99/destinations"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONNECTION_NOT_FOUND"));
    }
}
