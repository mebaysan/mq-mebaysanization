package com.baysansoft.mqmanager.jms;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Optional;

import javax.net.ssl.SSLException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.jms.provider.IbmMqDiagnostics;
import com.baysansoft.mqmanager.web.MqOperationException;

import jakarta.jms.InvalidDestinationException;
import jakarta.jms.JMSException;
import jakarta.jms.JMSSecurityException;

/**
 * Turns a provider exception into a stable code and a sentence a person can act on.
 *
 * <p>The obvious implementation — read {@code JMSException.getErrorCode()} and
 * {@code getLinkedException()} — silently fails for two of the three brokers. ActiveMQ Classic never
 * sets an error code, and Artemis sets neither an error code nor a linked exception, only
 * {@code initCause}. So classification walks the whole cause chain and leans on exception <em>types</em>
 * for the Apache brokers, and on numeric reason codes for IBM MQ.
 *
 * <p>Broker faults map to 502, not 500: the failure is downstream of this application and the UI says so.
 */
@Component
public class JmsErrorTranslator {

    private final IbmMqDiagnostics ibmDiagnostics;

    public JmsErrorTranslator(IbmMqDiagnostics ibmDiagnostics) {
        this.ibmDiagnostics = ibmDiagnostics;
    }

    public MqOperationException translate(Throwable throwable, ConnectionProfile profile, String action) {
        if (throwable instanceof MqOperationException already) {
            return already;
        }

        String target = BrokerUrls.describe(profile);

        // IBM MQ first: a numeric reason code is far more precise than any exception type.
        Optional<Integer> reason = ibmDiagnostics.reasonCode(throwable);
        if (reason.isPresent()) {
            return fromIbmReasonCode(reason.get(), throwable, profile, target, action);
        }

        // Apache brokers: the useful signal is the exception type, at the root of the chain.
        Throwable root = rootCause(throwable);

        if (root instanceof UnknownHostException) {
            return error("BROKER_UNKNOWN_HOST", HttpStatus.BAD_GATEWAY, throwable,
                    "Cannot resolve host '" + profile.getHost() + "'. Check the host name.");
        }
        if (root instanceof ConnectException || root instanceof NoRouteToHostException) {
            return error("BROKER_CONNECTION_REFUSED", HttpStatus.BAD_GATEWAY, throwable,
                    "Nothing accepted a connection at " + target
                            + ". Check the broker is running and the port is correct.");
        }
        if (root instanceof SSLException) {
            return error("TLS_HANDSHAKE_FAILED", HttpStatus.BAD_GATEWAY, throwable,
                    "TLS handshake with " + target + " failed. Note that v1 connects in plaintext, so a "
                            + "TLS-only listener will not accept it.");
        }
        if (root instanceof SocketTimeoutException) {
            return error("BROKER_UNREACHABLE", HttpStatus.GATEWAY_TIMEOUT, throwable,
                    "Timed out connecting to " + target + ".");
        }
        if (root instanceof EOFException || root instanceof SocketException) {
            return error("BROKER_CONNECTION_LOST", HttpStatus.BAD_GATEWAY, throwable,
                    "The connection to " + target + " was closed during " + action + ".");
        }
        if (throwable instanceof JMSSecurityException) {
            return error("BROKER_AUTH_FAILED", HttpStatus.UNAUTHORIZED, throwable,
                    "The broker at " + target + " rejected the credentials.");
        }
        if (throwable instanceof InvalidDestinationException) {
            return error("QUEUE_NOT_FOUND", HttpStatus.NOT_FOUND, throwable,
                    "The queue does not exist on " + target + ".");
        }

        // Artemis reports an unreachable broker as its own ActiveMQNotConnectedException with nothing
        // useful underneath: no ConnectException in the chain, no error code, no linked exception. Left
        // alone it would be the only provider whose "broker is down" looks like a generic failure, so it
        // is matched on its documented signature rather than by type — deliberately loose coupling, to
        // keep an Artemis import out of the shared code path.
        if (matchesArtemisNotConnected(throwable)) {
            return error("BROKER_CONNECTION_REFUSED", HttpStatus.BAD_GATEWAY, throwable,
                    "Could not connect to " + target
                            + ". Check the broker is running and the port is correct.");
        }

        String detail = firstUsefulMessage(throwable);
        return error("BROKER_ERROR", HttpStatus.BAD_GATEWAY, throwable,
                "Could not " + action + " on " + target + (detail == null ? "." : ": " + detail));
    }

    private MqOperationException fromIbmReasonCode(int reasonCode, Throwable throwable,
                                                   ConnectionProfile profile, String target,
                                                   String action) {
        String symbol = ibmDiagnostics.reasonName(reasonCode);
        String hint = ibmDiagnostics.explanation(throwable).map(text -> " " + text).orElse("");

        if (reasonCode == IbmMqDiagnostics.MQRC_NOT_AUTHORIZED) {
            String extra = "";
            if (profile.getUsername() != null && profile.getUsername().length() > 12) {
                // A real and very confusing failure mode: compatibility-mode authentication truncates
                // the user ID at 12 characters, so a long name fails as "not authorized".
                extra = " The user name is longer than 12 characters, which fails if the queue manager "
                        + "falls back to compatibility-mode authentication.";
            }
            return error("BROKER_AUTH_FAILED", HttpStatus.UNAUTHORIZED, throwable,
                    "IBM MQ rejected the connection to " + target + " (" + symbol + "/" + reasonCode
                            + "). Check the user name and password, and that the queue manager allows "
                            + "this user on this channel." + extra + hint);
        }
        if (reasonCode == IbmMqDiagnostics.MQRC_UNKNOWN_OBJECT_NAME) {
            return error("QUEUE_NOT_FOUND", HttpStatus.NOT_FOUND, throwable,
                    "The queue does not exist on queue manager " + profile.getQueueManagerName()
                            + " (" + symbol + "/" + reasonCode + "). IBM MQ does not create queues on "
                            + "demand, so it must already be defined." + hint);
        }
        if (reasonCode == IbmMqDiagnostics.MQRC_UNKNOWN_CHANNEL_NAME) {
            return error("CHANNEL_NOT_FOUND", HttpStatus.BAD_GATEWAY, throwable,
                    "Channel '" + profile.getChannel() + "' does not exist on the queue manager ("
                            + symbol + "/" + reasonCode + ")." + hint);
        }
        if (reasonCode == IbmMqDiagnostics.MQRC_CHANNEL_NOT_AVAILABLE) {
            return error("CHANNEL_NOT_AVAILABLE", HttpStatus.BAD_GATEWAY, throwable,
                    "Channel '" + profile.getChannel() + "' is not available (" + symbol + "/"
                            + reasonCode + "). It may be stopped or at its connection limit." + hint);
        }
        if (reasonCode == IbmMqDiagnostics.MQRC_Q_MGR_NAME_ERROR) {
            return error("QUEUE_MANAGER_NAME_INVALID", HttpStatus.BAD_GATEWAY, throwable,
                    "Queue manager name '" + profile.getQueueManagerName() + "' is not valid ("
                            + symbol + "/" + reasonCode + ")." + hint);
        }
        if (reasonCode == IbmMqDiagnostics.MQRC_Q_MGR_NOT_AVAILABLE) {
            return error("QUEUE_MANAGER_UNAVAILABLE", HttpStatus.BAD_GATEWAY, throwable,
                    "Queue manager '" + profile.getQueueManagerName() + "' is not available at " + target
                            + " (" + symbol + "/" + reasonCode + ")." + hint);
        }
        if (reasonCode == IbmMqDiagnostics.MQRC_HOST_NOT_AVAILABLE) {
            return error("BROKER_CONNECTION_REFUSED", HttpStatus.BAD_GATEWAY, throwable,
                    "Could not reach " + target + " (" + symbol + "/" + reasonCode
                            + "). Check the host, port and that a listener is running." + hint);
        }
        if (reasonCode == IbmMqDiagnostics.MQRC_CONNECTION_BROKEN) {
            return error("BROKER_CONNECTION_LOST", HttpStatus.BAD_GATEWAY, throwable,
                    "The connection to " + target + " broke during " + action + " (" + symbol + "/"
                            + reasonCode + ")." + hint);
        }
        if (reasonCode == IbmMqDiagnostics.MQRC_JSSE_ERROR
                || reasonCode == IbmMqDiagnostics.MQRC_UNSUPPORTED_CIPHER_SUITE) {
            return error("TLS_HANDSHAKE_FAILED", HttpStatus.BAD_GATEWAY, throwable,
                    "TLS negotiation with " + target + " failed (" + symbol + "/" + reasonCode
                            + "). v1 connects in plaintext and cannot use a TLS-protected channel." + hint);
        }

        return error("BROKER_ERROR", HttpStatus.BAD_GATEWAY, throwable,
                "IBM MQ could not " + action + " on " + target + " (" + symbol + "/" + reasonCode + ")."
                        + hint);
    }

    /** AMQ219007 is Artemis's "cannot connect to server(s)"; the type name is the belt-and-braces check. */
    private static boolean matchesArtemisNotConnected(Throwable throwable) {
        for (Throwable current = throwable; current != null && current.getCause() != current;
                current = current.getCause()) {
            if (current.getClass().getSimpleName().equals("ActiveMQNotConnectedException")) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("AMQ219007")) {
                return true;
            }
        }
        return false;
    }

    private static MqOperationException error(String code, HttpStatus status, Throwable cause,
                                              String message) {
        return new MqOperationException(code, status, message, cause);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (true) {
            Throwable next = current instanceof JMSException jms && jms.getLinkedException() != null
                    ? jms.getLinkedException()
                    : current.getCause();
            if (next == null || next == current) {
                return current;
            }
            current = next;
        }
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
