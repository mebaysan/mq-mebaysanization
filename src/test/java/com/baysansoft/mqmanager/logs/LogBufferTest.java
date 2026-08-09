package com.baysansoft.mqmanager.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baysansoft.mqmanager.config.MqManagerProperties;

/**
 * The buffer lives in the heap of the process it reports on and is written to from every request
 * thread at once, so the properties that matter are that it stays bounded and stays consistent.
 */
class LogBufferTest {

    private static LogBuffer buffer(int capacity) {
        MqManagerProperties properties = new MqManagerProperties();
        properties.getLogs().setCapacity(capacity);
        return new LogBuffer(properties);
    }

    private static void add(LogBuffer buffer, LogLevel level, String message) {
        buffer.add(Instant.EPOCH, level, "c.b.m.Test", "main", message, null);
    }

    private static void addAt(LogBuffer buffer, Instant at, String message) {
        buffer.add(at, LogLevel.INFO, "c.b.m.Test", "main", message, null);
    }

    // ------------------------------------------------------------------ time window and sort

    @Test
    @DisplayName("the time window includes both of its own bounds")
    void windowIsInclusiveAtBothEnds() {
        LogBuffer buffer = buffer(10);
        addAt(buffer, Instant.parse("2026-08-09T10:00:00Z"), "before");
        addAt(buffer, Instant.parse("2026-08-09T10:00:05Z"), "at-from");
        addAt(buffer, Instant.parse("2026-08-09T10:00:07Z"), "inside");
        addAt(buffer, Instant.parse("2026-08-09T10:00:10Z"), "at-to");
        addAt(buffer, Instant.parse("2026-08-09T10:00:11Z"), "after");

        assertThat(buffer.recent(LogLevel.TRACE, null,
                        Instant.parse("2026-08-09T10:00:05Z"),
                        Instant.parse("2026-08-09T10:00:10Z"),
                        SortOrder.NEWEST_FIRST, 10)
                .entries())
                .extracting(LogEntry::message)
                .containsExactly("at-to", "inside", "at-from");
    }

    @Test
    @DisplayName("a limit always returns the NEWEST matches, even sorted oldest first")
    void oldestFirstStillReturnsTheNewestPage() {
        LogBuffer buffer = buffer(10);
        for (int i = 1; i <= 5; i++) {
            add(buffer, LogLevel.INFO, "line-" + i);
        }

        // The load-bearing assertion for the whole feature. "The first 2 ascending" would be line-1
        // and line-2 — startup noise, with everything that just happened hidden behind it.
        assertThat(buffer.recent(LogLevel.TRACE, null, null, null, SortOrder.OLDEST_FIRST, 2)
                .entries())
                .extracting(LogEntry::message)
                .containsExactly("line-4", "line-5");
    }

    @Test
    @DisplayName("windowTruncated says older lines were left inside the range, and is false when none were")
    void windowTruncatedReportsWhatWasLeftOut() {
        LogBuffer buffer = buffer(10);
        for (int i = 1; i <= 5; i++) {
            add(buffer, LogLevel.INFO, "line-" + i);
        }

        assertThat(buffer.recent(LogLevel.TRACE, null, null, null, SortOrder.OLDEST_FIRST, 2)
                .windowTruncated()).isTrue();
        assertThat(buffer.recent(LogLevel.TRACE, null, null, null, SortOrder.OLDEST_FIRST, 5)
                .windowTruncated()).isFalse();
    }

    @Test
    @DisplayName("a line stamped out of insertion order is still found, because the window is filtered not seeked")
    void outOfOrderTimestampsAreStillMatched() {
        LogBuffer buffer = buffer(10);
        // Two threads logging in the same millisecond can land a marginally older stamp after a newer
        // one. An early break at the window edge would drop this straggler.
        addAt(buffer, Instant.parse("2026-08-09T10:00:09Z"), "newer");
        addAt(buffer, Instant.parse("2026-08-09T10:00:08Z"), "straggler");
        addAt(buffer, Instant.parse("2026-08-09T09:00:00Z"), "well-outside");

        assertThat(buffer.recent(LogLevel.TRACE, null,
                        Instant.parse("2026-08-09T10:00:00Z"), null, SortOrder.NEWEST_FIRST, 10)
                .entries())
                .extracting(LogEntry::message)
                .containsExactly("straggler", "newer");
    }

    @Test
    @DisplayName("the short form is unchanged: no window, newest first")
    void shortFormStillMeansEverythingNewestFirst() {
        LogBuffer buffer = buffer(10);
        addAt(buffer, Instant.parse("2020-01-01T00:00:00Z"), "old");
        addAt(buffer, Instant.parse("2026-08-09T10:00:00Z"), "new");

        LogBuffer.Snapshot snapshot = buffer.recent(LogLevel.TRACE, null, 10);

        assertThat(snapshot.entries()).extracting(LogEntry::message).containsExactly("new", "old");
        assertThat(snapshot.order()).isEqualTo(SortOrder.NEWEST_FIRST);
        assertThat(snapshot.windowTruncated()).isFalse();
    }

    @Test
    @DisplayName("an unknown sort order is rejected by name, like an unknown level")
    void sortOrderParseRejectsUnknownValues() {
        assertThat(SortOrder.parse(" oldest_first ")).isEqualTo(SortOrder.OLDEST_FIRST);
        assertThatThrownBy(() -> SortOrder.parse("sideways"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NEWEST_FIRST")
                .hasMessageContaining("OLDEST_FIRST");
    }

    @Test
    @DisplayName("the newest line comes first, because that is the one being waited for")
    void newestFirst() {
        LogBuffer buffer = buffer(10);
        add(buffer, LogLevel.INFO, "first");
        add(buffer, LogLevel.INFO, "second");
        add(buffer, LogLevel.INFO, "third");

        assertThat(buffer.recent(LogLevel.TRACE, null, 10).entries())
                .extracting(LogEntry::message)
                .containsExactly("third", "second", "first");
    }

    @Test
    @DisplayName("past its capacity the oldest lines are evicted and counted, never silently lost")
    void evictsOldestAndCountsIt() {
        LogBuffer buffer = buffer(3);
        for (int i = 1; i <= 5; i++) {
            add(buffer, LogLevel.INFO, "line-" + i);
        }

        LogBuffer.Snapshot snapshot = buffer.recent(LogLevel.TRACE, null, 10);

        assertThat(snapshot.entries()).extracting(LogEntry::message)
                .containsExactly("line-5", "line-4", "line-3");
        assertThat(snapshot.held()).isEqualTo(3);
        assertThat(snapshot.capacity()).isEqualTo(3);
        // The page says "showing a partial history" off this number. Reporting 0 here would let it
        // claim completeness it does not have.
        assertThat(snapshot.dropped()).isEqualTo(2);
    }

    @Test
    @DisplayName("the sequence number keeps rising across evictions, so it stays a stable identity")
    void sequenceSurvivesEviction() {
        LogBuffer buffer = buffer(2);
        for (int i = 1; i <= 4; i++) {
            add(buffer, LogLevel.INFO, "line-" + i);
        }

        assertThat(buffer.recent(LogLevel.TRACE, null, 10).entries())
                .extracting(LogEntry::sequence)
                .containsExactly(4L, 3L);
    }

    @Test
    @DisplayName("a level filter means 'at least this severe', not 'exactly this'")
    void filtersByMinimumSeverity() {
        LogBuffer buffer = buffer(10);
        add(buffer, LogLevel.DEBUG, "debug");
        add(buffer, LogLevel.INFO, "info");
        add(buffer, LogLevel.WARN, "warn");
        add(buffer, LogLevel.ERROR, "error");

        assertThat(buffer.recent(LogLevel.WARN, null, 10).entries())
                .extracting(LogEntry::message)
                .containsExactly("error", "warn");
        assertThat(buffer.recent(LogLevel.TRACE, null, 10).entries()).hasSize(4);
    }

    @Test
    @DisplayName("search matches the message or the logger, case-insensitively")
    void searchesMessageAndLogger() {
        LogBuffer buffer = buffer(10);
        buffer.add(Instant.EPOCH, LogLevel.INFO, "c.b.m.kafka.KafkaMessagingOperations", "main",
                "Purged 501 record(s)", null);
        buffer.add(Instant.EPOCH, LogLevel.INFO, "c.b.m.jms.JmsMessagingOperations", "main",
                "Sent 11 character(s)", null);

        assertThat(buffer.recent(LogLevel.TRACE, "PURGED", 10).entries()).hasSize(1);
        assertThat(buffer.recent(LogLevel.TRACE, "kafka", 10).entries()).hasSize(1);
        assertThat(buffer.recent(LogLevel.TRACE, "  ", 10).entries()).hasSize(2);
        assertThat(buffer.recent(LogLevel.TRACE, "nothing matches this", 10).entries()).isEmpty();
    }

    @Test
    @DisplayName("the limit caps what is returned without capping what is searched")
    void limitIsAppliedAfterMatching() {
        LogBuffer buffer = buffer(100);
        for (int i = 1; i <= 50; i++) {
            add(buffer, i % 10 == 0 ? LogLevel.ERROR : LogLevel.INFO, "line-" + i);
        }

        // The five errors are scattered through the buffer, so finding two of them means the scan
        // walked past the INFO lines rather than stopping at the first two entries.
        assertThat(buffer.recent(LogLevel.ERROR, null, 2).entries())
                .extracting(LogEntry::message)
                .containsExactly("line-50", "line-40");
    }

    @Test
    @DisplayName("an over-long message is cut and says so, so one line cannot dominate the buffer")
    void truncatesLongMessages() {
        MqManagerProperties properties = new MqManagerProperties();
        properties.getLogs().setMaxMessageChars(80); // clamped to a floor of 80
        LogBuffer buffer = new LogBuffer(properties);

        add(buffer, LogLevel.INFO, "x".repeat(500));

        String message = buffer.recent(LogLevel.TRACE, null, 1).entries().get(0).message();
        assertThat(message).hasSize(80 + "… (truncated)".length()).endsWith("… (truncated)");
    }

    @Test
    @DisplayName("stack traces can be switched off entirely, for an unauthenticated deployment")
    void stackTracesCanBeSuppressed() {
        MqManagerProperties properties = new MqManagerProperties();
        properties.getLogs().setMaxStackTraceChars(0);
        LogBuffer buffer = new LogBuffer(properties);

        buffer.add(Instant.EPOCH, LogLevel.ERROR, "c.b.m.Test", "main", "boom",
                "java.lang.IllegalStateException\n\tat com.example.Thing.run(Thing.java:1)");

        assertThat(buffer.recent(LogLevel.TRACE, null, 1).entries().get(0).stackTrace()).isNull();
    }

    @Test
    @DisplayName("clearing empties the buffer and resets the dropped count")
    void clearResetsEverything() {
        LogBuffer buffer = buffer(2);
        for (int i = 0; i < 5; i++) {
            add(buffer, LogLevel.INFO, "line-" + i);
        }

        buffer.clear();
        LogBuffer.Snapshot snapshot = buffer.recent(LogLevel.TRACE, null, 10);

        assertThat(snapshot.entries()).isEmpty();
        assertThat(snapshot.held()).isZero();
        assertThat(snapshot.dropped()).isZero();
    }

    @Test
    @DisplayName("concurrent writers never exceed the capacity or lose a sequence number")
    void staysBoundedUnderConcurrency() throws Exception {
        LogBuffer buffer = buffer(100);
        int writers = 8;
        int perWriter = 500;

        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int w = 0; w < writers; w++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        add(buffer, LogLevel.INFO, "line");
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        LogBuffer.Snapshot snapshot = buffer.recent(LogLevel.TRACE, null, 1_000);
        assertThat(snapshot.held()).isEqualTo(100);
        assertThat(snapshot.entries()).hasSize(100);
        // Every line is either held or counted as dropped. A lost update would break this sum.
        assertThat(snapshot.held() + snapshot.dropped()).isEqualTo((long) writers * perWriter);
        assertThat(snapshot.entries()).extracting(LogEntry::sequence).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("an unknown level name is refused with a message naming the valid ones")
    void unknownLevelIsRejected() {
        assertThatThrownBy(() -> LogLevel.parse("VERBOSE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TRACE, DEBUG, INFO, WARN, ERROR");
        assertThatThrownBy(() -> LogLevel.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(LogLevel.parse(" warn ")).isEqualTo(LogLevel.WARN);
    }
}
