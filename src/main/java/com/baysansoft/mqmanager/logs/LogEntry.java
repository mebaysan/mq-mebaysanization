package com.baysansoft.mqmanager.logs;

import java.time.Instant;

/**
 * One captured log line, as the UI sees it.
 *
 * @param sequence monotonic per-process counter. The UI uses it as a stable React key and to tell a
 *                 genuinely new line from a re-render of one it already had
 * @param logger   the shortened logger name, e.g. {@code c.b.m.kafka.KafkaMessagingOperations}
 * @param stackTrace the formatted throwable, or null. Present because a monitoring page that hides the
 *                 stack trace is not a monitoring page — unlike an API error response, where it is
 *                 deliberately withheld
 */
public record LogEntry(
        long sequence,
        Instant timestamp,
        LogLevel level,
        String logger,
        String thread,
        String message,
        String stackTrace) {
}
