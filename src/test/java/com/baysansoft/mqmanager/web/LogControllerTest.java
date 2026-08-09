package com.baysansoft.mqmanager.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import com.baysansoft.mqmanager.logs.LogBuffer;
import com.baysansoft.mqmanager.logs.LogLevel;

/**
 * The log endpoint's contract at the HTTP layer, over a real buffer rather than a mock.
 *
 * <p>The buffer comes from {@code MqManagerApplication#logBuffer()} — declaring one here as well
 * would be a second bean of the same name and the context would refuse to start. It is process-wide,
 * so every test seeds it from a known state in {@link #seed()}.
 */
@WebMvcTest(controllers = LogController.class)
class LogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LogBuffer buffer;

    @BeforeEach
    void seed() {
        buffer.clear();
        buffer.add(Instant.EPOCH, LogLevel.DEBUG, "c.b.m.Test", "main", "a debug line", null);
        buffer.add(Instant.EPOCH, LogLevel.INFO, "c.b.m.kafka.Ops", "http-1", "Purged 3 records", null);
        buffer.add(Instant.EPOCH, LogLevel.ERROR, "c.b.m.Test", "http-2", "it broke", "trace here");
    }

    @Test
    @DisplayName("the default view is INFO and above, newest first")
    void defaultsToInfoNewestFirst() throws Exception {
        mockMvc.perform(get("/api/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].message").value("it broke"))
                .andExpect(jsonPath("$.entries[0].level").value("ERROR"))
                .andExpect(jsonPath("$.entries[1].message").value("Purged 3 records"))
                .andExpect(jsonPath("$.capacity").value(2000))
                .andExpect(jsonPath("$.held").value(3))
                .andExpect(jsonPath("$.dropped").value(0));
    }

    @Test
    @DisplayName("a lower level widens the view rather than replacing it")
    void levelIsAMinimum() throws Exception {
        mockMvc.perform(get("/api/logs").param("level", "trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3));

        mockMvc.perform(get("/api/logs").param("level", "ERROR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1));
    }

    @Test
    @DisplayName("search narrows by message or logger")
    void searchNarrows() throws Exception {
        mockMvc.perform(get("/api/logs").param("level", "TRACE").param("q", "purged"))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].message").value("Purged 3 records"));

        mockMvc.perform(get("/api/logs").param("level", "TRACE").param("q", "kafka"))
                .andExpect(jsonPath("$.entries.length()").value(1));
    }

    @Test
    @DisplayName("an unknown level is a 400 that names the valid ones, not a silent fallback")
    void unknownLevelIsRejected() throws Exception {
        mockMvc.perform(get("/api/logs").param("level", "VERBOSE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("TRACE, DEBUG, INFO, WARN, ERROR")));
    }

    @Test
    @DisplayName("the response carries a stack trace, unlike an API error response")
    void stackTracesAreVisibleHere() throws Exception {
        // Deliberately the opposite of ApiExceptionHandler's rule. On a monitoring page the stack
        // trace is the payload; mqmanager.logs.max-stack-trace-chars: 0 turns it off.
        mockMvc.perform(get("/api/logs").param("level", "ERROR"))
                .andExpect(jsonPath("$.entries[0].stackTrace").value("trace here"));
    }

    @Test
    @DisplayName("clearing empties what the page shows")
    void clearEmptiesTheBuffer() throws Exception {
        mockMvc.perform(delete("/api/logs")).andExpect(status().isOk());

        assertThat(buffer.recent(LogLevel.TRACE, null, 10).entries()).isEmpty();
    }

    @Test
    @DisplayName("the limit is clamped, so a huge one cannot be used to dump the whole heap")
    void limitIsClamped() throws Exception {
        mockMvc.perform(get("/api/logs").param("level", "TRACE").param("limit", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3));
    }

    // ------------------------------------------------------------------ time range and sort

    @Test
    @DisplayName("a time range narrows the view, at both ends")
    void timeRangeNarrowsTheView() throws Exception {
        buffer.clear();
        buffer.add(Instant.parse("2026-08-09T10:00:00Z"), LogLevel.INFO, "c.b.m.Test", "main",
                "before", null);
        buffer.add(Instant.parse("2026-08-09T10:00:05Z"), LogLevel.INFO, "c.b.m.Test", "main",
                "inside", null);
        buffer.add(Instant.parse("2026-08-09T10:00:20Z"), LogLevel.INFO, "c.b.m.Test", "main",
                "after", null);

        mockMvc.perform(get("/api/logs")
                        .param("from", "2026-08-09T10:00:01Z")
                        .param("to", "2026-08-09T10:00:10Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].message").value("inside"));
    }

    @Test
    @DisplayName("an explicit offset is accepted, not only a Z instant")
    void offsetFormsAreAccepted() throws Exception {
        mockMvc.perform(get("/api/logs")
                        .param("level", "TRACE")
                        .param("from", "1970-01-01T01:00:00+01:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3));
    }

    @Test
    @DisplayName("sort changes the order and is echoed back, case-insensitively")
    void sortChangesOrderOnly() throws Exception {
        mockMvc.perform(get("/api/logs").param("level", "TRACE").param("sort", "oldest_first"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order").value("OLDEST_FIRST"))
                .andExpect(jsonPath("$.entries[0].message").value("a debug line"))
                .andExpect(jsonPath("$.entries[2].message").value("it broke"));
    }

    @Test
    @DisplayName("a limit returns the newest lines whichever way they are sorted")
    void limitAlwaysTakesTheNewest() throws Exception {
        mockMvc.perform(get("/api/logs")
                        .param("level", "TRACE").param("sort", "OLDEST_FIRST").param("limit", "2"))
                .andExpect(status().isOk())
                // Not "a debug line" and "Purged 3 records" — those are the OLDEST two.
                .andExpect(jsonPath("$.entries[0].message").value("Purged 3 records"))
                .andExpect(jsonPath("$.entries[1].message").value("it broke"))
                .andExpect(jsonPath("$.windowTruncated").value(true));
    }

    @Test
    @DisplayName("an unparseable timestamp is a 400 that names the format")
    void badTimestampIsRejected() throws Exception {
        mockMvc.perform(get("/api/logs").param("from", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("ISO-8601")));
    }

    @Test
    @DisplayName("an unknown sort order is a 400 that names both valid ones")
    void badSortIsRejected() throws Exception {
        mockMvc.perform(get("/api/logs").param("sort", "sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("NEWEST_FIRST")));
    }

    @Test
    @DisplayName("a range that runs backwards is refused rather than silently returning nothing")
    void reversedRangeIsRejected() throws Exception {
        mockMvc.perform(get("/api/logs")
                        .param("from", "2026-08-09T10:00:00Z")
                        .param("to", "2026-08-09T09:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
