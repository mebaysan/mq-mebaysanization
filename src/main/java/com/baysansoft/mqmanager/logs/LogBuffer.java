package com.baysansoft.mqmanager.logs;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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
     * The most recent matching lines in a time window.
     *
     * <p><strong>Always the newest {@code limit} matches, whatever {@code order} is.</strong> The order
     * decides only how they are handed back. "The first N ascending" would be the <em>oldest</em> N,
     * which on a full buffer means showing startup and hiding what just happened — the opposite of what
     * a tail is for. It also keeps the descending scan's early stop: ascending is a reverse of a list
     * that is at most {@code limit} long, so O(limit) rather than O(capacity).
     *
     * <p>Because an ascending page therefore starts partway through the window, {@link Snapshot#windowTruncated}
     * says so. Without it a list beginning at 10:03:11 would imply nothing matched before then.
     *
     * @param minimum only lines at least this severe
     * @param query   case-insensitive substring over the message and the logger name; blank matches all
     * @param from    inclusive lower bound on the timestamp, or null for no lower bound
     * @param to      inclusive upper bound on the timestamp, or null for no upper bound
     */
    public synchronized Snapshot recent(LogLevel minimum, String query, Instant from, Instant to,
            SortOrder order, int limit) {
        String needle = StringUtils.hasText(query) ? query.trim().toLowerCase(Locale.ROOT) : null;
        List<LogEntry> matched = new ArrayList<>(Math.min(limit, entries.size()));
        boolean windowTruncated = false;

        // Descending, so the scan stops as soon as the limit is reached rather than after walking the
        // whole buffer and throwing most of it away.
        //
        // The window is FILTERED, not seeked: there is deliberately no early break when the walk
        // crosses `from`. Entries are ordered by insertion, and two threads logging in the same
        // millisecond can put a marginally older timestamp after a newer one, so stopping at the first
        // out-of-window line could drop a straggler. The scan is bounded by capacity either way.
        for (var iterator = entries.descendingIterator(); iterator.hasNext();) {
            if (matched.size() >= limit) {
                // Older lines remain inside the window. They may or may not have matched, so this can
                // over-report — the safe direction, exactly as BrowseResult.truncated is. Being exact
                // would mean scanning the rest of the buffer for one more match, which is a lot of work
                // for a flag whose whole job is to say "this may not be everything".
                windowTruncated = true;
                break;
            }
            LogEntry entry = iterator.next();
            if (entry.level().atLeast(minimum) && inWindow(entry, from, to) && matches(entry, needle)) {
                matched.add(entry);
            }
        }

        if (order == SortOrder.OLDEST_FIRST) {
            Collections.reverse(matched);
        }
        return new Snapshot(matched, entries.size(), capacity, dropped, windowTruncated, order);
    }

    /** The unbounded, newest-first read. Kept because most callers want exactly that. */
    public synchronized Snapshot recent(LogLevel minimum, String query, int limit) {
        return recent(minimum, query, null, null, SortOrder.NEWEST_FIRST, limit);
    }

    public synchronized void clear() {
        entries.clear();
        dropped = 0;
    }

    private static boolean inWindow(LogEntry entry, Instant from, Instant to) {
        Instant at = entry.timestamp();
        if (at == null) {
            // An undated line cannot be placed, so it is never silently swept into a window.
            return from == null && to == null;
        }
        return (from == null || !at.isBefore(from)) && (to == null || !at.isAfter(to));
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
     * @param held            lines currently in the buffer, matched or not
     * @param dropped         lines evicted since startup. Non-zero means the page is not showing everything
     * @param windowTruncated true when the limit stopped the scan while older lines remained inside the
     *                        window. Under OLDEST_FIRST this is the difference between "nothing happened
     *                        before the first line shown" and "we stopped looking there"
     * @param order           echoed back, so a client describes the answer it got rather than the
     *                        request it made
     */
    public record Snapshot(List<LogEntry> entries, int held, int capacity, long dropped,
            boolean windowTruncated, SortOrder order) {
    }
}
