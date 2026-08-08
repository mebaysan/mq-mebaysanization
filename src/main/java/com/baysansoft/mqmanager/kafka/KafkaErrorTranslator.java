package com.baysansoft.mqmanager.kafka;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.PolicyViolationException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.jms.BrokerUrls;
import com.baysansoft.mqmanager.web.MqOperationException;

/**
 * Turns a Kafka client failure into the same stable {@code {status, error, message, code}} contract the
 * JMS providers use, so the UI needs no Kafka-specific error handling.
 *
 * <p>Codes are shared with {@code JmsErrorTranslator} wherever the meaning is genuinely the same
 * ({@code QUEUE_NOT_FOUND}, {@code BROKER_AUTH_FAILED}, {@code BROKER_UNREACHABLE}) and new only where
 * Kafka can fail in a way no JMS broker can.
 *
 * <p>A stack trace is never returned; the underlying exception is attached as a cause so it can be
 * logged at DEBUG and nowhere else.
 */
@Component
public class KafkaErrorTranslator {

    /** The one action that names no topic, so topic-shaped advice would only mislead. */
    public static final String CONNECT_ACTION = "connect";

    public MqOperationException translate(Throwable throwable, ConnectionProfile profile, String action) {
        if (throwable instanceof MqOperationException already) {
            return already;
        }

        String target = BrokerUrls.describe(profile);
        Throwable cause = unwrap(throwable);

        if (cause instanceof UnknownTopicOrPartitionException) {
            return error(cause, "QUEUE_NOT_FOUND", HttpStatus.NOT_FOUND,
                    "That topic does not exist on " + target + ". Kafka only creates topics on demand "
                            + "when the broker has auto.create.topics.enable turned on, which most "
                            + "clusters do not.");
        }
        if (cause instanceof InvalidTopicException) {
            return error(cause, "QUEUE_NAME_INVALID", HttpStatus.BAD_REQUEST,
                    "Kafka rejected that topic name. Names may use letters, digits, dot, underscore and "
                            + "hyphen, and may not be \".\" or \"..\".");
        }
        if (cause instanceof SaslAuthenticationException || cause instanceof AuthenticationException) {
            return error(cause, "BROKER_AUTH_FAILED", HttpStatus.UNAUTHORIZED,
                    "Authentication to " + target + " failed. Check the username and password.");
        }
        if (cause instanceof AuthorizationException) {
            // Covers TopicAuthorizationException, ClusterAuthorizationException and friends: connected
            // and authenticated, but the ACL for this operation is missing. Different from a bad password.
            return error(cause, "BROKER_NOT_AUTHORIZED", HttpStatus.FORBIDDEN,
                    "This user is not authorized for that operation on " + target
                            + ". Kafka needs an explicit ACL for it — a purge, for example, requires "
                            + "Delete on the topic.");
        }
        if (cause instanceof PolicyViolationException) {
            return error(cause, "OPERATION_NOT_SUPPORTED", HttpStatus.CONFLICT,
                    "The broker refused that operation on this topic. A compacted topic "
                            + "(cleanup.policy=compact) cannot have its records deleted.");
        }
        if (cause instanceof UnsupportedVersionException) {
            return error(cause, "BROKER_ERROR", HttpStatus.BAD_GATEWAY,
                    "The broker at " + target + " is too old for this request. The bundled client "
                            + "supports Kafka 0.10.2 and newer.");
        }
        if (cause instanceof TimeoutException) {
            // A producer blocked on max.block.ms for a topic it cannot find says so in as many words:
            // "Topic X not present in metadata after N ms". Without this, sending to an unknown topic
            // would report BROKER_UNREACHABLE while reading the same topic reports QUEUE_NOT_FOUND —
            // two different answers about one situation. Matching on a broker message has precedent
            // here: JmsErrorTranslator does the same for Artemis's AMQ219007.
            String detail = cause.getMessage();
            if (detail != null && detail.contains("not present in metadata")) {
                return error(cause, "QUEUE_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "That topic does not exist on " + target + ", and the cluster did not create "
                                + "it — Kafka only does that when the broker has "
                                + "auto.create.topics.enable turned on, which most clusters do not.");
            }
            // The "topic may not exist" clause is only true of operations that name one. On a plain
            // connection test it would send someone looking for a topic problem that cannot exist yet.
            String orTheTopic = CONNECT_ACTION.equals(action) ? "" : ", or the topic may not exist";
            return error(cause, "BROKER_UNREACHABLE", HttpStatus.GATEWAY_TIMEOUT,
                    "Timed out trying to " + action + " on " + target + ". The cluster may be "
                            + "unreachable, the listener may require TLS (this build is plaintext "
                            + "only)" + orTheTopic + ".");
        }
        if (cause instanceof UnknownHostException) {
            return error(cause, "BROKER_UNKNOWN_HOST", HttpStatus.BAD_GATEWAY,
                    "The host in " + target + " could not be resolved.");
        }
        if (cause instanceof ConnectException || cause instanceof NoRouteToHostException) {
            return error(cause, "BROKER_CONNECTION_REFUSED", HttpStatus.BAD_GATEWAY,
                    "Nothing accepted a connection at " + target + ".");
        }
        if (cause instanceof UnsatisfiedLinkError || cause instanceof NoClassDefFoundError) {
            // The compression codecs ship native libraries for every mainstream platform, but an exotic
            // one (musl, an unusual ARM variant) could still fail to load. Say which, rather than 500.
            return error(cause, "COMPRESSION_CODEC_UNAVAILABLE", HttpStatus.BAD_GATEWAY,
                    "This topic uses a compression codec whose native library could not be loaded on "
                            + "this platform. Only uncompressed and gzip batches can be read here.");
        }
        if (cause instanceof IOException) {
            return error(cause, "BROKER_CONNECTION_LOST", HttpStatus.BAD_GATEWAY,
                    "The connection to " + target + " dropped while trying to " + action + ".");
        }

        String detail = firstUsefulMessage(throwable);
        return error(cause, "BROKER_ERROR", HttpStatus.BAD_GATEWAY,
                "Could not " + action + " on " + target + (detail == null ? "." : ": " + detail));
    }

    /**
     * {@code KafkaFuture.get} wraps everything in {@code ExecutionException}, and a {@code KafkaException}
     * often wraps the socket-level failure that actually explains the problem. Walk down to the first
     * cause that is neither.
     */
    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        if (isGenericKafkaWrapper(current) && current.getCause() != null && current.getCause() != current) {
            return unwrap(current.getCause());
        }
        return current;
    }

    /** A bare {@code KafkaException} carries no information a subclass would not carry better. */
    private static boolean isGenericKafkaWrapper(Throwable throwable) {
        return throwable != null
                && throwable.getClass() == org.apache.kafka.common.KafkaException.class;
    }

    private static MqOperationException error(Throwable cause, String code, HttpStatus status,
            String message) {
        return new MqOperationException(code, status, message, cause);
    }

    private static String firstUsefulMessage(Throwable throwable) {
        for (Throwable current = throwable; current != null && current.getCause() != current;
                current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                return BrokerUrls.sanitize(message);
            }
        }
        return null;
    }
}
