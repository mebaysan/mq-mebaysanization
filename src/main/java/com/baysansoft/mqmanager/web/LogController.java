package com.baysansoft.mqmanager.web;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baysansoft.mqmanager.logs.LogBuffer;
import com.baysansoft.mqmanager.logs.LogLevel;
import com.baysansoft.mqmanager.logs.SortOrder;

/**
 * The application's own recent log lines, for the monitoring page.
 *
 * <p>Read-only over the process's in-memory ring buffer. It never touches the filesystem, so there is
 * no path to traverse and nothing here can be pointed at a file it was not meant to read.
 *
 * <p><strong>This build has no authentication</strong>, so anyone who can reach the port can read these
 * lines. The application never logs a credential — passwords are encrypted at rest, the JAAS config is
 * masked by Kafka, and broker URLs are sanitised before they reach a message — but a log line does
 * carry hostnames, queue and topic names, and message bodies whenever DEBUG is enabled for this
 * application's logger — {@code MQMANAGER_LOG_PAYLOADS} ships enabled, so the level is the only thing
 * keeping them out. Set {@code MQMANAGER_LOG_PAYLOADS=false} to keep bodies out at any level, and
 * {@code mqmanager.logs.max-stack-trace-chars: 0} to keep stack traces out.
 */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    /** Enough to fill a screen several times over without shipping the whole buffer on every poll. */
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 2_000;

    private final LogBuffer buffer;

    public LogController(LogBuffer buffer) {
        this.buffer = buffer;
    }

    /**
     * @param level minimum severity, one of TRACE/DEBUG/INFO/WARN/ERROR. An unknown value is a 400
     *              rather than a silent fallback, so a typo in a bookmarked URL is visible
     * @param q     case-insensitive substring over the message and the logger name
     * @param from  inclusive ISO-8601 lower bound on the timestamp, or absent for no lower bound
     * @param to    inclusive ISO-8601 upper bound
     * @param sort  NEWEST_FIRST or OLDEST_FIRST. This changes the ORDER only: a limited read always
     *              returns the most recent matching lines, never the oldest ones
     */
    @GetMapping
    public LogBuffer.Snapshot recent(
            @RequestParam(required = false, defaultValue = "INFO") String level,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "NEWEST_FIRST") String sort,
            @RequestParam(required = false, defaultValue = "0") int limit) {
        Instant fromAt = parseInstant(from, "from");
        Instant toAt = parseInstant(to, "to");
        if (fromAt != null && toAt != null && fromAt.isAfter(toAt)) {
            throw new IllegalArgumentException("'from' is after 'to', so nothing could ever match.");
        }
        return buffer.recent(LogLevel.parse(level), q, fromAt, toAt, SortOrder.parse(sort),
                clampLimit(limit));
    }

    /**
     * Absolute instants only. A wall-clock string carries no zone, and the server's zone is not
     * necessarily the reader's — guessing would silently shift the window by hours. The UI converts
     * its local-time inputs before sending them.
     */
    private static Instant parseInstant(String value, String parameter) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + parameter + "' must be an ISO-8601 instant, for "
                    + "example 2026-08-09T14:03:11Z or 2026-08-09T15:03:11+01:00. Got '" + value + "'.");
        }
    }

    /**
     * Empties the buffer. Affects only what this page shows — stdout is untouched, so nothing that
     * ships logs off the box loses anything. Useful for "clear, then reproduce".
     */
    @DeleteMapping
    public void clear() {
        buffer.clear();
    }

    private static int clampLimit(int requested) {
        if (requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
