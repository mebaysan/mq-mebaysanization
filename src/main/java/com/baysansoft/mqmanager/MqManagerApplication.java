package com.baysansoft.mqmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jms.JmsAutoConfiguration;
import org.springframework.boot.autoconfigure.jms.activemq.ActiveMQAutoConfiguration;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import com.baysansoft.mqmanager.logs.LogBuffer;
import com.baysansoft.mqmanager.logs.LogBufferInstaller;

/**
 * Spring Boot's JMS auto-configuration is excluded deliberately.
 *
 * <p>It exists to build one static {@code ConnectionFactory} from {@code application.yml}, which is the
 * opposite of what this tool does: connection details are supplied by the user at runtime and there are
 * many profiles, each with its own factory built on demand.
 *
 * <p>It is also actively harmful here. Merely having Artemis on the classpath triggers
 * {@code ArtemisAutoConfiguration}, and with an Artemis server artifact present (as in the test
 * classpath) Boot fails to introspect its embedded-server configuration and the context refuses to start.
 */
@SpringBootApplication(exclude = {
        JmsAutoConfiguration.class,
        ActiveMQAutoConfiguration.class,
        ArtemisAutoConfiguration.class
})
@ConfigurationPropertiesScan
public class MqManagerApplication {

    /**
     * Static because it has to exist before the context does.
     *
     * <p>The monitoring page reads an in-memory buffer fed by a logback appender, and an appender
     * attached by an ordinary bean starts capturing too late — Flyway has already migrated by then, so
     * the one thing someone opens the page to see after a deploy is the one thing missing from it.
     * Registering it as a {@code SpringApplication} listener attaches it before any bean exists.
     */
    private static final LogBufferInstaller LOG_CAPTURE = new LogBufferInstaller();

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(MqManagerApplication.class);
        application.addListeners(LOG_CAPTURE);
        application.run(args);
    }

    /** Hands the already-populated buffer to the context, rather than building a second, empty one. */
    @Bean
    LogBuffer logBuffer() {
        return LOG_CAPTURE.buffer();
    }
}
