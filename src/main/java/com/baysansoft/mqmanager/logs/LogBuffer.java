package com.baysansoft.mqmanager.logs;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.util.StringUtils;

import com.baysansoft.mqmanager.config.MqManagerProperties;

/**
 * The last N log lines, in memory.
 *
 * <p>A ring buffer rather than a log file on purpose. The application writes to stdout, and where that
 * ends up depends entirely on how someone launched it — a terminal, a service manager, a container
 * runtime. A bounded in-memory buffer gives the monitoring page something to read without the
 * application having to own a file, rotate it, or serve arbitrary paths off disk.
 *
 * <p>The cost is that it is not durable: it holds only this process's recent history and it is gone on
 * restart. That is the right trade for "watch what is happening right now", which is what the page is
 * for; it is not an audit trail, and the README says so.
 *
 * <p>Not a {@code @Component}: it is created by {@code LogBufferInstaller} before the context exists,
 * and handed to the context by {@code MqManagerApplication#logBuffer()}. Scanning it as well would be
 * a second, empty instance.
 *
 * <p><strong>Nothing in this class may log.</strong> It is called from inside the logging pipeline, so
 * a single log statement here would recurse until the stack ran out.
 */
public class LogBuffer {

    private final int capacity;
    private final int maxMessageChars;
    private final int maxStackTraceChars;

    private final AtomicLong sequence = new AtomicLong();
    private final Deque<LogEntry> entries;

    /** Lines evicted to make room, so the UI can say the history is incomplete rather than imply it is not. */
    private long dropped;

    public LogBuffer(MqManagerProperties properties) {
        MqManagerProperties.Logs logs = properties.getLogs();
        this.capacity = Math.max(1, logs.getCapacity());
        this.maxMessageChars = Math.max(80, logs.getMaxMessageChars());
        this.maxStackTraceChars = Math.max(0, logs.getMaxStackTraceChars());
        this.entries = new ArrayDeque<>(Math.min(this.capacity, 1024));
    }

    /**
     * Appends a line, evicting the oldest once full. Synchronized because logging happens on every
     * request thread at once; the critical section is an array write and is measured in nanoseconds.
     */
    public synchronized void add(Instant timestamp, LogLevel level, String logger, String thread,
            String message, String stackTrace) {
        while (entries.size() >= capacity) {
            entries.removeFirst();
            dropped++;
        }
        entries.addLast(new LogEntry(
                sequence.incrementAndGet(),
                timestamp,
                level,
                logger,
                thread,
                truncate(message, maxMessageChars),
                maxStackTraceChars == 0 ? null : truncate(stackTrace, maxStackTraceChars)));
    }

    /**
     * The most recent matching lines, newest first — a log tail is read from the top, and putting the
     * newest line there means the interesting one is visible without scrolling.
     *
     * @param minimum only lines at least this severe
     * @param query   case-insensitive substring over the message and the logger name; blank matches all
     */
    public synchronized Snapshot recent(LogLevel minimum, String query, int limit) {
        String needle = StringUtils.hasText(query) ? query.trim().toLowerCase(Locale.ROOT) : null;
        List<LogEntry> matched = new ArrayList<>(Math.min(limit, entries.size()));

        // Descending, so the scan stops as soon as the limit is reached rather than after walking the
        // whole buffer and throwing most of it away.
        for (var iterator = entries.descendingIterator(); iterator.hasNext() && matched.size() < limit;) {
            LogEntry entry = iterator.next();
            if (entry.level().atLeast(minimum) && matches(entry, needle)) {
                matched.add(entry);
            }
        }
        return new Snapshot(matched, entries.size(), capacity, dropped);
    }

    public synchronized void clear() {
        entries.clear();
        dropped = 0;
    }

    private static boolean matches(LogEntry entry, String needle) {
        if (needle == null) {
            return true;
        }
        return contains(entry.message(), needle) || contains(entry.logger(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        // Says it was cut rather than leaving a sentence that just stops.
        return value.substring(0, max) + "… (truncated)";
    }

    /**
     * @param held    lines currently in the buffer, matched or not
     * @param dropped lines evicted since startup. Non-zero means the page is not showing everything
     */
    public record Snapshot(List<LogEntry> entries, int held, int capacity, long dropped) {
    }
}
