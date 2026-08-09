package com.baysansoft.mqmanager.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.baysansoft.mqmanager.domain.DestinationKind;
import com.baysansoft.mqmanager.service.SavedDestinationService;
import com.baysansoft.mqmanager.web.dto.SavedDestinationResponses.SavedDestinationResponse;

/** The remembered-destination endpoints at the HTTP layer. */
@WebMvcTest(controllers = SavedDestinationController.class)
class SavedDestinationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SavedDestinationService saved;

    private static SavedDestinationResponse response(String name, boolean pinned) {
        return new SavedDestinationResponse(name, "QUEUE", pinned, 3, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    @DisplayName("the list comes back in the order the server chose, pinned first")
    void listsInServerOrder() throws Exception {
        when(saved.list(1L)).thenReturn(List.of(response("pinned", true), response("recent", false)));

        mockMvc.perform(get("/api/connections/1/saved-destinations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("pinned"))
                .andExpect(jsonPath("$[0].pinned").value(true))
                .andExpect(jsonPath("$[1].name").value("recent"))
                // The name is the key within a connection, so no second identifier is exposed.
                .andExpect(jsonPath("$[0].id").doesNotExist());
    }

    @Test
    @DisplayName("recording an open passes the kind through when the picker supplied one")
    void recordOpenPassesTheKind() throws Exception {
        when(saved.recordOpen(eq(1L), eq("orders"), any())).thenReturn(response("orders", false));

        mockMvc.perform(post("/api/connections/1/saved-destinations")
                        .contentType("application/json")
                        .content("""
                                {"name":"orders","kind":"TOPIC"}"""))
                .andExpect(status().isOk());

        verify(saved).recordOpen(1L, "orders", DestinationKind.TOPIC);
    }

    @Test
    @DisplayName("with no kind the server is left to derive it, rather than being handed a guess")
    void recordOpenWithoutAKind() throws Exception {
        when(saved.recordOpen(eq(1L), eq("orders"), any())).thenReturn(response("orders", false));

        mockMvc.perform(post("/api/connections/1/saved-destinations")
                        .contentType("application/json")
                        .content("""
                                {"name":"orders"}"""))
                .andExpect(status().isOk());

        verify(saved).recordOpen(1L, "orders", null);
    }

    @Test
    @DisplayName("a blank name is a validation failure, not an empty row")
    void blankNameIsRejected() throws Exception {
        mockMvc.perform(post("/api/connections/1/saved-destinations")
                        .contentType("application/json")
                        .content("""
                                {"name":"  "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("an unknown kind is a 400 naming the valid ones")
    void unknownKindIsRejected() throws Exception {
        mockMvc.perform(post("/api/connections/1/saved-destinations")
                        .contentType("application/json")
                        .content("""
                                {"name":"orders","kind":"CHANNEL"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("pinning round-trips through a body, so a dotted name needs no escaping")
    void pinTakesABody() throws Exception {
        when(saved.pin(1L, "DEV.QUEUE.1", true)).thenReturn(response("DEV.QUEUE.1", true));

        mockMvc.perform(put("/api/connections/1/saved-destinations/pin")
                        .contentType("application/json")
                        .content("""
                                {"name":"DEV.QUEUE.1","pinned":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinned").value(true));
    }

    @Test
    @DisplayName("forgetting a dotted name works as a query parameter and answers 204")
    void forgetTakesAQueryParameter() throws Exception {
        mockMvc.perform(delete("/api/connections/1/saved-destinations").param("name", "DEV.QUEUE.1"))
                .andExpect(status().isNoContent());

        verify(saved).forget(1L, "DEV.QUEUE.1");
    }

    @Test
    @DisplayName("an unknown connection is the same 404 as everywhere else")
    void unknownConnectionIsNotFound() throws Exception {
        when(saved.list(99L))
                .thenThrow(new NotFoundException("CONNECTION_NOT_FOUND", "No connection 99."));

        mockMvc.perform(get("/api/connections/99/saved-destinations"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONNECTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("pinning a name that was never opened surfaces as a 400, not a 500")
    void pinningAnUnknownNameIsABadRequest() throws Exception {
        doThrow(new IllegalArgumentException("'ghost' is not a remembered destination on this connection."))
                .when(saved).pin(1L, "ghost", true);

        mockMvc.perform(put("/api/connections/1/saved-destinations/pin")
                        .contentType("application/json")
                        .content("""
                                {"name":"ghost","pinned":true}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
