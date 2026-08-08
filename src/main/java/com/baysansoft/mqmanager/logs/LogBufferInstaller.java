package com.baysansoft.mqmanager.logs;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import com.baysansoft.mqmanager.config.MqManagerProperties;

import ch.qos.logback.classic.LoggerContext;

/**
 * Creates the {@link LogBuffer} and attaches {@link LogBufferAppender} to logback's root logger.
 *
 * <p><strong>This is a {@code SpringApplication} listener, not a bean, and that is the whole point.</strong>
 * As an ordinary {@code @Component} it initialised somewhere in the middle of context startup — after
 * Flyway had already run — so the monitoring page opened on a freshly deployed instance showed
 * everything except the migration output someone had just deployed to see. Listening for
 * {@link ApplicationEnvironmentPreparedEvent} attaches the appender before any bean exists, which is
 * as early as is possible while still being able to read configuration.
 *
 * <p>Ordered after Boot's own {@code LoggingApplicationListener}, which handles the same event and is
 * what initialises logback in the first place; attaching before it would attach to a context that is
 * about to be reset.
 *
 * <p>Configured straight off the {@code Environment} rather than from a bound
 * {@code @ConfigurationProperties} bean, because no bean exists yet. The properties are read through
 * the same {@link MqManagerProperties.Logs} defaults, so an unset value means exactly what it means
 * everywhere else.
 *
 * <p>Attached to the ROOT logger, so it sees whatever every other appender sees at whatever levels
 * {@code application.yml} configures. It filters nothing itself.
 */
public class LogBufferInstaller
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    private static final String APPENDER_NAME = "mqmanager-log-buffer";

    /**
     * Boot's LoggingApplicationListener sits at {@code DEFAULT_ORDER = HIGHEST_PRECEDENCE + 20}. One
     * past it is late enough for logback to exist and early enough to precede everything else.
     */
    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 21;

    private volatile LogBuffer buffer;
    private LoggerContext context;
    private LogBufferAppender appender;

    /**
     * The buffer, exposed to the context as a bean. Never null: created eagerly so a caller that
     * somehow runs before the event still gets a working, if empty, buffer rather than an NPE.
     */
    public synchronized LogBuffer buffer() {
        if (buffer == null) {
            buffer = new LogBuffer(new MqManagerProperties());
        }
        return buffer;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public synchronized void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        attach(propertiesFrom(event.getEnvironment()));
    }

    /** The attachment itself, separated so a test can drive it without fabricating a Boot event. */
    synchronized void attach(MqManagerProperties properties) {
        if (appender != null) {
            return; // a restarted context reuses the listener; one attachment is enough
        }

        buffer = new LogBuffer(properties);

        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext loggerContext)) {
            // Not logback. The page will be empty, which is a far better outcome than refusing to
            // start over a diagnostic aid.
            return;
        }

        context = loggerContext;
        appender = new LogBufferAppender(buffer);
        appender.setName(APPENDER_NAME);
        appender.setContext(context);
        appender.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    /** Detaches, so a test or an embedded restart does not leave an appender behind. */
    public synchronized void detach() {
        if (context != null && appender != null) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
            appender.stop();
        }
        context = null;
        appender = null;
    }

    private static MqManagerProperties propertiesFrom(ConfigurableEnvironment environment) {
        MqManagerProperties properties = new MqManagerProperties();
        MqManagerProperties.Logs logs = properties.getLogs();
        logs.setCapacity(environment.getProperty("mqmanager.logs.capacity", Integer.class,
                logs.getCapacity()));
        logs.setMaxMessageChars(environment.getProperty("mqmanager.logs.max-message-chars",
                Integer.class, logs.getMaxMessageChars()));
        logs.setMaxStackTraceChars(environment.getProperty("mqmanager.logs.max-stack-trace-chars",
                Integer.class, logs.getMaxStackTraceChars()));
        return properties;
    }
}
