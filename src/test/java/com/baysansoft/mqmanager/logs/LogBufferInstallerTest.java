package com.baysansoft.mqmanager.logs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baysansoft.mqmanager.config.MqManagerProperties;

/**
 * Proves the wiring end to end: a real SLF4J call on a real logger lands in the buffer.
 *
 * <p>Worth having as a test rather than trusting the ten lines of setup, because the failure mode is
 * silent — an appender that is never attached produces an empty page, not an error.
 *
 * <p>This attaches to the JVM-wide root logger, so it detaches again in {@link #tearDown()}; leaving it
 * on would funnel every later test's logging into a stray buffer.
 */
class LogBufferInstallerTest {

    private static final Logger log = LoggerFactory.getLogger(LogBufferInstallerTest.class);

    private LogBuffer buffer;
    private LogBufferInstaller installer;

    @BeforeEach
    void setUp() {
        installer = new LogBufferInstaller();
        installer.attach(new MqManagerProperties());
        buffer = installer.buffer();
    }

    @AfterEach
    void tearDown() {
        installer.detach();
    }

    @Test
    @DisplayName("a real log call is captured, with its level, thread and shortened logger name")
    void capturesRealLogging() {
        log.warn("a marker line for the installer test");

        LogEntry entry = buffer.recent(LogLevel.TRACE, "a marker line", 10).entries().get(0);

        assertThat(entry.level()).isEqualTo(LogLevel.WARN);
        assertThat(entry.message()).isEqualTo("a marker line for the installer test");
        assertThat(entry.logger()).isEqualTo("c.b.m.logs.LogBufferInstallerTest");
        assertThat(entry.thread()).isNotBlank();
        assertThat(entry.timestamp()).isNotNull();
        assertThat(entry.stackTrace()).isNull();
    }

    @Test
    @DisplayName("placeholders are resolved, so the page shows the message a human would read")
    void formatsPlaceholders() {
        log.info("Purged {} record(s) from '{}' across {} partition(s)", 501, "orders", 3);

        assertThat(buffer.recent(LogLevel.TRACE, "Purged", 10).entries().get(0).message())
                .isEqualTo("Purged 501 record(s) from 'orders' across 3 partition(s)");
    }

    @Test
    @DisplayName("an exception is captured as a stack trace — this page is where you go to see one")
    void capturesStackTraces() {
        log.error("something went wrong", new IllegalStateException("the cause"));

        LogEntry entry = buffer.recent(LogLevel.ERROR, "something went wrong", 10).entries().get(0);

        assertThat(entry.stackTrace())
                .contains("java.lang.IllegalStateException")
                .contains("the cause")
                .contains("at " + LogBufferInstallerTest.class.getName());
    }

    @Test
    @DisplayName("detaching stops capture, so shutdown does not leave an appender on the root logger")
    void destroyDetaches() {
        installer.detach();
        buffer.clear();

        log.warn("emitted after the installer was destroyed");

        assertThat(buffer.recent(LogLevel.TRACE, null, 10).entries()).isEmpty();

        // Re-attach so tearDown's second detach() is a no-op rather than a surprise.
        installer.attach(new MqManagerProperties());
    }

    @Test
    @DisplayName("every package segment but the last two is abbreviated, predictably")
    void shortensLoggerNames() {
        // A fixed rule, unlike logback's width-driven %logger{39}, so the column stays aligned and
        // the value is worth searching on.
        assertThat(LogBufferAppender.shorten("com.baysansoft.mqmanager.kafka.KafkaMessagingOperations"))
                .isEqualTo("c.b.m.kafka.KafkaMessagingOperations");
        assertThat(LogBufferAppender.shorten("org.apache.kafka.clients.NetworkClient"))
                .isEqualTo("o.a.k.clients.NetworkClient");
        // Nothing to abbreviate.
        assertThat(LogBufferAppender.shorten("web.Thing")).isEqualTo("web.Thing");
        assertThat(LogBufferAppender.shorten("ROOT")).isEqualTo("ROOT");
        assertThat(LogBufferAppender.shorten(null)).isEmpty();
    }
}
