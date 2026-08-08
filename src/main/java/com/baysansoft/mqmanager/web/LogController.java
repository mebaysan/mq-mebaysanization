package com.baysansoft.mqmanager.web;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baysansoft.mqmanager.logs.LogBuffer;
import com.baysansoft.mqmanager.logs.LogLevel;

/**
 * The application's own recent log lines, for the monitoring page.
 *
 * <p>Read-only over the process's in-memory ring buffer. It never touches the filesystem, so there is
 * no path to traverse and nothing here can be pointed at a file it was not meant to read.
 *
 * <p><strong>This build has no authentication</strong>, so anyone who can reach the port can read these
 * lines. The application never logs a credential — passwords are encrypted at rest, the JAAS config is
 * masked by Kafka, and broker URLs are sanitised before they reach a message — but a log line does
 * carry hostnames, queue and topic names, and, if {@code MQMANAGER_LOG_PAYLOADS} is switched on at
 * DEBUG, message bodies. Set {@code mqmanager.logs.max-stack-trace-chars: 0} to keep stack traces out.
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
     */
    @GetMapping
    public LogBuffer.Snapshot recent(
            @RequestParam(required = false, defaultValue = "INFO") String level,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "0") int limit) {
        return buffer.recent(LogLevel.parse(level), q, clampLimit(limit));
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
