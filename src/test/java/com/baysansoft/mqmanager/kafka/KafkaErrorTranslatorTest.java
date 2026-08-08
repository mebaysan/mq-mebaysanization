package com.baysansoft.mqmanager.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.PolicyViolationException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.support.KafkaTestFixture;
import com.baysansoft.mqmanager.web.MqOperationException;

/**
 * Kafka failures have to arrive at the UI as the same stable {@code code} contract the JMS providers
 * use, or the frontend would need a second error vocabulary just for one provider.
 */
class KafkaErrorTranslatorTest {

    private final KafkaErrorTranslator translator = new KafkaErrorTranslator();
    private final ConnectionProfile profile = KafkaTestFixture.profile();

    @Test
    @DisplayName("a timeout is a 504, because a dead cluster is a gateway problem and not the user's")
    void timeoutIsUnreachable() {
        assertCode(new TimeoutException("Timed out waiting for a node assignment"),
                "BROKER_UNREACHABLE", HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    @DisplayName("a producer blocked on an unknown topic is a 404, the same answer reading it gives")
    void producerMetadataTimeoutIsTheSameAnswerAsReading() {
        // Kafka reports this as a plain TimeoutException off max.block.ms. Left as BROKER_UNREACHABLE
        // it would mean sending to a missing topic said 504 while reading the very same topic said
        // 404 — two different answers about one situation.
        MqOperationException translated = translator.translate(
                new TimeoutException("Topic orders not present in metadata after 5000 ms."),
                profile, "send a message");

        assertThat(translated.getCode()).isEqualTo("QUEUE_NOT_FOUND");
        assertThat(translated.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(translated.getMessage()).contains("auto.create.topics.enable");
    }

    @Test
    @DisplayName("a timeout on a plain connection test does not blame a topic that was never named")
    void connectTimeoutDoesNotMentionTopics() {
        TimeoutException timeout = new TimeoutException("Timed out");

        assertThat(translator.translate(timeout, profile, KafkaErrorTranslator.CONNECT_ACTION)
                .getMessage()).doesNotContain("topic");
        // But an operation that does name one should still offer it as an explanation.
        assertThat(translate(timeout).getMessage()).contains("topic may not exist");
    }

    @Test
    @DisplayName("an unknown topic is a 404 that names the auto-create setting people forget")
    void unknownTopicIsNotFound() {
        MqOperationException translated = translate(new UnknownTopicOrPartitionException("orders"));

        assertThat(translated.getCode()).isEqualTo("QUEUE_NOT_FOUND");
        assertThat(translated.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(translated.getMessage()).contains("auto.create.topics.enable");
    }

    @Test
    @DisplayName("bad credentials are 401 and a missing ACL is 403 — they need different fixes")
    void authenticationAndAuthorizationAreDistinct() {
        assertCode(new SaslAuthenticationException("Authentication failed"),
                "BROKER_AUTH_FAILED", HttpStatus.UNAUTHORIZED);
        assertCode(new TopicAuthorizationException("Not authorized to access topics: [orders]"),
                "BROKER_NOT_AUTHORIZED", HttpStatus.FORBIDDEN);
        assertCode(new ClusterAuthorizationException("Cluster authorization failed"),
                "BROKER_NOT_AUTHORIZED", HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a policy violation names compaction, which is the reason a purge is refused")
    void policyViolationExplainsCompaction() {
        MqOperationException translated =
                translate(new PolicyViolationException("Records cannot be deleted"));

        assertThat(translated.getCode()).isEqualTo("OPERATION_NOT_SUPPORTED");
        assertThat(translated.getMessage()).contains("cleanup.policy=compact");
    }

    @Test
    @DisplayName("the remaining Kafka failures map onto the codes the JMS providers already use")
    void reusesExistingCodesWhereTheMeaningIsTheSame() {
        assertCode(new InvalidTopicException("Topic name is invalid"),
                "QUEUE_NAME_INVALID", HttpStatus.BAD_REQUEST);
        assertCode(new UnsupportedVersionException("The broker is too old"),
                "BROKER_ERROR", HttpStatus.BAD_GATEWAY);
        assertCode(new KafkaException(new UnknownHostException("broker")),
                "BROKER_UNKNOWN_HOST", HttpStatus.BAD_GATEWAY);
        assertCode(new KafkaException(new ConnectException("Connection refused")),
                "BROKER_CONNECTION_REFUSED", HttpStatus.BAD_GATEWAY);
    }

    @Test
    @DisplayName("an ExecutionException from a KafkaFuture is unwrapped rather than reported as itself")
    void unwrapsFuturesAndWrappers() {
        // Everything from admin calls arrives wrapped like this. Reporting the wrapper would lose the
        // only piece of information worth having.
        assertCode(new ExecutionException(new UnknownTopicOrPartitionException("orders")),
                "QUEUE_NOT_FOUND", HttpStatus.NOT_FOUND);
        assertCode(new ExecutionException(new KafkaException(new TimeoutException("timed out"))),
                "BROKER_UNREACHABLE", HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    @DisplayName("a missing compression native is named, rather than surfacing as a 500")
    void missingCompressionCodecIsExplained() {
        MqOperationException translated =
                translate(new KafkaException(new NoClassDefFoundError("org/xerial/snappy/Snappy")));

        assertThat(translated.getCode()).isEqualTo("COMPRESSION_CODEC_UNAVAILABLE");
        assertThat(translated.getMessage()).contains("compression");
    }

    @Test
    @DisplayName("an already-translated failure passes through unchanged")
    void doesNotDoubleTranslate() {
        MqOperationException original =
                new MqOperationException("MESSAGE_ID_INVALID", HttpStatus.BAD_REQUEST, "bad id");

        assertThat(translate(original)).isSameAs(original);
    }

    @Test
    @DisplayName("no translated message leaks a stack trace or the bootstrap credentials")
    void neverLeaksInternals() {
        for (Throwable failure : new Throwable[] {
                new TimeoutException("timed out"),
                new KafkaException("something odd happened"),
                new IllegalStateException("unexpected"),
                new ExecutionException(new TopicAuthorizationException("nope"))}) {
            String message = translate(failure).getMessage();
            assertThat(message)
                    .doesNotContain("\tat ")
                    .doesNotContain("Exception:")
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("an unrecognised failure still names what was being attempted and where")
    void fallbackStaysUseful() {
        MqOperationException translated = translate(new IllegalStateException("something odd"));

        assertThat(translated.getCode()).isEqualTo("BROKER_ERROR");
        assertThat(translated.getMessage())
                .contains("browse the topic")
                .contains("localhost:9092");
    }

    private MqOperationException translate(Throwable failure) {
        return translator.translate(failure, profile, "browse the topic");
    }

    private void assertCode(Throwable failure, String code, HttpStatus status) {
        MqOperationException translated = translate(failure);
        assertThat(translated.getCode()).as("code for %s", failure.getClass().getSimpleName())
                .isEqualTo(code);
        assertThat(translated.getStatus()).as("status for %s", failure.getClass().getSimpleName())
                .isEqualTo(status);
    }
}
