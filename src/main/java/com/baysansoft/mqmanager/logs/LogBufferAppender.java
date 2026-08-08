package com.baysansoft.mqmanager.logs;

import java.time.Instant;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;

/**
 * Copies each log event into {@link LogBuffer}. Attached to the root logger by
 * {@link LogBufferInstaller}, alongside — never instead of — the console appender: stdout stays the
 * source of truth for anything shipping logs off the box.
 *
 * <p>Two rules govern everything in here, and both come from running inside the logging pipeline:
 *
 * <ol>
 *   <li><strong>Never log.</strong> A log statement on this path recurses until the stack runs out.
 *       That includes indirectly, via anything this calls.
 *   <li><strong>Never throw.</strong> An exception escaping an appender is swallowed by logback's
 *       status manager and shows up as nothing at all. Failing quietly here is the lesser evil: the
 *       monitoring page is a convenience, and it must not be able to break the application it watches.
 * </ol>
 */
public class LogBufferAppender extends AppenderBase<ILoggingEvent> {

    private final LogBuffer buffer;

    public LogBufferAppender(LogBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            buffer.add(
                    Instant.ofEpochMilli(event.getTimeStamp()),
                    LogLevel.from(event.getLevel()),
                    shorten(event.getLoggerName()),
                    event.getThreadName(),
                    // Formatted, not the raw pattern: the UI wants "Purged 501 record(s)", not
                    // "Purged {} record(s)".
                    event.getFormattedMessage(),
                    event.getThrowableProxy() == null
                            ? null
                            : ThrowableProxyUtil.asString(event.getThrowableProxy()));
        } catch (RuntimeException e) {
            // Deliberately swallowed - see rule 2. There is nowhere safe to report this to.
        }
    }

    /**
     * Abbreviates every package segment except the last two to its initial:
     * {@code com.baysansoft.mqmanager.kafka.KafkaMessagingOperations} becomes
     * {@code c.b.m.kafka.KafkaMessagingOperations}.
     *
     * <p>Close to what the console shows, but deliberately not the same algorithm. Logback's
     * {@code %logger{39}} abbreviates only as much as it needs to hit a width, so the same logger can
     * render differently depending on how long its neighbours are. A fixed rule gives the page a
     * column that stays aligned and a value worth searching on.
     */
    static String shorten(String loggerName) {
        if (loggerName == null) {
            return "";
        }
        String[] segments = loggerName.split("\\.");
        if (segments.length <= 2) {
            return loggerName;
        }
        StringBuilder shortened = new StringBuilder(loggerName.length());
        for (int i = 0; i < segments.length - 2; i++) {
            if (!segments[i].isEmpty()) {
                shortened.append(segments[i].charAt(0)).append('.');
            }
        }
        return shortened
                .append(segments[segments.length - 2]).append('.')
                .append(segments[segments.length - 1])
                .toString();
    }
}
