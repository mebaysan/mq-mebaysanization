package com.baysansoft.mqmanager.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.baysansoft.mqmanager.service.SavedRequestService;
import com.baysansoft.mqmanager.web.dto.SavedRequestResponses.SavedRequestResponse;

/** The remembered-request endpoints at the HTTP layer. */
@WebMvcTest(controllers = SavedRequestController.class)
class SavedRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SavedRequestService requests;

    private static SavedRequestResponse response(long id, String label, boolean named) {
        return new SavedRequestResponse(id, label, named, "body", Map.of("h", "1"), null, "TEXT", null,
                Instant.EPOCH);
    }

    @Test
    @DisplayName("the list comes back with an id per row, since history has no natural key")
    void listsWithIds() throws Exception {
        when(requests.list(1L, "orders"))
                .thenReturn(List.of(response(7, "smoke", true), response(8, null, false)));

        mockMvc.perform(get("/api/connections/1/queue/saved-requests").param("queueName", "orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].named").value(true))
                .andExpect(jsonPath("$[0].label").value("smoke"))
                .andExpect(jsonPath("$[1].id").value(8))
                .andExpect(jsonPath("$[1].named").value(false));
    }

    @Test
    @DisplayName("saving passes the destination through as a query parameter, never a path segment")
    void savePassesQueueNameThrough() throws Exception {
        when(requests.save(eq(1L), eq("DEV.QUEUE.1"), any())).thenReturn(response(1, null, false));

        mockMvc.perform(post("/api/connections/1/queue/saved-requests")
                        .param("queueName", "DEV.QUEUE.1")
                        .contentType("application/json")
                        .content("""
                                {"payload":"hello","properties":{"h":"1"}}"""))
                .andExpect(status().isOk());

        verify(requests).save(eq(1L), eq("DEV.QUEUE.1"), any());
    }

    @Test
    @DisplayName("a null payload is a validation failure, not an empty row")
    void nullPayloadIsRejected() throws Exception {
        mockMvc.perform(post("/api/connections/1/queue/saved-requests")
                        .param("queueName", "orders")
                        .contentType("application/json")
                        .content("""
                                {"label":"no body"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("forgetting a row works by id as a query parameter and answers 204")
    void forgetTakesQueryParameters() throws Exception {
        mockMvc.perform(delete("/api/connections/1/queue/saved-requests")
                        .param("queueName", "DEV.QUEUE.1")
                        .param("requestId", "42"))
                .andExpect(status().isNoContent());

        verify(requests).forget(1L, "DEV.QUEUE.1", 42L);
    }

    @Test
    @DisplayName("an unknown connection is the same 404 as everywhere else")
    void unknownConnectionIsNotFound() throws Exception {
        when(requests.list(99L, "orders"))
                .thenThrow(new NotFoundException("CONNECTION_NOT_FOUND", "No connection 99."));

        mockMvc.perform(get("/api/connections/99/queue/saved-requests").param("queueName", "orders"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONNECTION_NOT_FOUND"));
    }
}
