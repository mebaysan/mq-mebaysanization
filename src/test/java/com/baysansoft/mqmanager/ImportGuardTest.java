package com.baysansoft.mqmanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards four mistakes that compile cleanly and only fail later.
 *
 * <p>All are cheap to make and expensive to diagnose, which is exactly what a build-time guard is for.
 */
class ImportGuardTest {

    /**
     * Simple names that exist twice on this classpath. Importing either one compiles perfectly and
     * fails at runtime, or — worse — works against one broker and not the other.
     */
    private static final List<String> AMBIGUOUS_SIMPLE_NAMES = List.of(
            // org.apache.activemq.* (Classic) vs org.apache.activemq.artemis.jms.client.* (Artemis)
            "ActiveMQConnectionFactory",
            "ActiveMQConnection",
            "ActiveMQDestination",
            "ActiveMQQueue",
            "ActiveMQTopic",
            // com.ibm.mq.MQQueueManager vs
            // com.ibm.msg.client.jakarta.wmq.compat.base.internal.MQQueueManager
            "MQQueueManager",
            // com.ibm.mq.MQDestination (the base Java API's, an MQManagedObject subclass) vs
            // com.ibm.mq.jakarta.jms.MQDestination. Neither is final, so `queue instanceof
            // MQDestination` compiles against either — and against the wrong one it is simply always
            // false. That would silently stop IbmMqConnectionFactoryBuilder disabling read-ahead, which
            // shows up much later as a purge count that under-reports.
            "MQDestination",
            // com.ibm.mq.MQQueue vs com.ibm.mq.jakarta.jms.MQQueue vs
            // com.ibm.msg.client.jakarta.wmq.compat.base.internal.MQQueue
            "MQQueue");

    /** Resolved from the project root, not the working directory, which varies by runner. */
    private static Path projectDir() {
        String basedir = System.getProperty("project.basedir");
        return basedir != null ? Path.of(basedir) : Path.of("").toAbsolutePath();
    }

    private static List<Path> filesUnder(Path root, String extension) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(extension))
                    .toList();
        }
    }

    @Test
    @DisplayName("no class whose simple name exists twice on the classpath is ever imported")
    void noAmbiguousSimpleNameImports() throws IOException {
        Path sourceRoot = projectDir().resolve("src/main/java");
        assumeTrue(Files.isDirectory(sourceRoot), "backend sources not present");

        List<String> offenders = new ArrayList<>();
        for (Path file : filesUnder(sourceRoot, ".java")) {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.strip();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                for (String ambiguous : AMBIGUOUS_SIMPLE_NAMES) {
                    // Anchored to the last segment, so importing a genuinely unambiguous type whose
                    // name merely contains one of these (e.g. ActiveMQQueueBrowser) is not flagged.
                    if (trimmed.endsWith("." + ambiguous + ";")) {
                        offenders.add(file.getFileName() + ": " + trimmed);
                    }
                }
            }
        }

        assertThat(offenders)
                .as("""
                        Each of %s names two different classes on this classpath — ActiveMQ Classic \
                        against Artemis, or com.ibm.mq against the compat layer inside the Jakarta \
                        client. Importing either compiles cleanly and fails only at runtime. Write the \
                        fully-qualified name inline instead.""", AMBIGUOUS_SIMPLE_NAMES)
                .isEmpty();
    }

    @Test
    @DisplayName("the frontend never imports react-router-dom, which does not exist in React Router v8")
    void noReactRouterDomImports() throws IOException {
        Path frontendSources = projectDir().resolve("frontend/src");
        assumeTrue(Files.isDirectory(frontendSources), "frontend sources not present");

        List<String> offenders = new ArrayList<>();
        for (Path file : filesUnder(frontendSources, ".tsx")) {
            collectReactRouterDom(file, offenders);
        }
        for (Path file : filesUnder(frontendSources, ".ts")) {
            collectReactRouterDom(file, offenders);
        }

        assertThat(offenders)
                .as("react-router-dom was removed in React Router v8; import from 'react-router'")
                .isEmpty();
    }

    @Test
    @DisplayName("the two messaging implementations never import each other's client library")
    void kafkaAndJmsStayBehindTheirOwnSeams() throws IOException {
        Path sourceRoot = projectDir().resolve("src/main/java");
        assumeTrue(Files.isDirectory(sourceRoot), "backend sources not present");

        Path kafkaPackage = sourceRoot.resolve("com/baysansoft/mqmanager/kafka");
        Path jmsProviderPackage = sourceRoot.resolve("com/baysansoft/mqmanager/jms/provider");
        List<String> offenders = new ArrayList<>();
        for (Path file : filesUnder(sourceRoot, ".java")) {
            boolean inKafkaPackage = file.startsWith(kafkaPackage);
            boolean inJmsProviderPackage = file.startsWith(jmsProviderPackage);
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.strip();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                if (!inKafkaPackage && trimmed.contains("org.apache.kafka")) {
                    offenders.add(file.getFileName() + ": " + trimmed);
                }
                if (inKafkaPackage && trimmed.contains("jakarta.jms")) {
                    offenders.add(file.getFileName() + ": " + trimmed);
                }
                // PCF is IBM's base Java API, not JMS. It belongs to the one lister that speaks it and
                // must not spread into code the other three providers share.
                if (!inJmsProviderPackage && trimmed.contains("com.ibm.mq.headers")) {
                    offenders.add(file.getFileName() + ": " + trimmed);
                }
                // spring-kafka is deliberately not a dependency; an import of it would compile only
                // after someone quietly added the artifact, along with its ZooKeeper test baggage.
                if (trimmed.contains("org.springframework.kafka")) {
                    offenders.add(file.getFileName() + ": " + trimmed);
                }
            }
        }

        assertThat(offenders)
                .as("""
                        Kafka is not JMS and shares no client code with the three JMS providers. \
                        kafka-clients belongs behind com.baysansoft.mqmanager.kafka, jakarta.jms behind \
                        com.baysansoft.mqmanager.jms, com.ibm.mq.headers (PCF) behind \
                        com.baysansoft.mqmanager.jms.provider, and spring-kafka is not a dependency of \
                        this project at all — see the comment on kafka-clients in pom.xml.""")
                .isEmpty();
    }

    private static void collectReactRouterDom(Path file, List<String> offenders) throws IOException {
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.strip();
            // Only real module specifiers count. A comment explaining why the package must not be used
            // is not a violation of the rule it is describing.
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            if (trimmed.contains("'react-router-dom'") || trimmed.contains("\"react-router-dom\"")) {
                offenders.add(file.getFileName() + ": " + trimmed);
            }
        }
    }
}
