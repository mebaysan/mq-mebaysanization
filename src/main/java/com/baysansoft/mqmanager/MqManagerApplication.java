package com.baysansoft.mqmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jms.JmsAutoConfiguration;
import org.springframework.boot.autoconfigure.jms.activemq.ActiveMQAutoConfiguration;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

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

    public static void main(String[] args) {
        SpringApplication.run(MqManagerApplication.class, args);
    }
}
