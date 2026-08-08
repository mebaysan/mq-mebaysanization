package com.baysansoft.mqmanager.jms.provider;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;
import com.ibm.msg.client.jakarta.jms.JmsExceptionDetail;

import jakarta.jms.JMSException;

/**
 * The only class besides the IBM builder that touches {@code com.ibm.*}. Everything IBM-specific about
 * interpreting a failure lives here so the shared messaging code stays provider-agnostic.
 */
@Component
public class IbmMqDiagnostics {

    /**
     * Digs the numeric MQ reason code out of an exception chain.
     *
     * <p>Both links are walked: IBM nests {@link MQException} under the JMS exception's <em>linked</em>
     * exception, which is not the same thing as its cause, and only one of the two is populated
     * depending on where the failure originated.
     */
    public Optional<Integer> reasonCode(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = nextInChain(current)) {
            if (current instanceof MQException mqException) {
                return Optional.of(mqException.getReason());
            }
        }
        return Optional.empty();
    }

    /** Symbolic name for a reason code, e.g. 2035 to {@code MQRC_NOT_AUTHORIZED}. */
    public String reasonName(int reasonCode) {
        try {
            return MQConstants.lookupReasonCode(reasonCode);
        } catch (RuntimeException e) {
            return "MQRC_" + reasonCode;
        }
    }

    /**
     * IBM's own explanation and suggested action, when present. Cast to the interface rather than to
     * {@code DetailedJMSException}, because the concrete type varies across the Detailed* family.
     */
    public Optional<String> explanation(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = nextInChain(current)) {
            if (current instanceof JmsExceptionDetail detail) {
                String explanation = detail.getExplanation();
                String userAction = detail.getUserAction();
                if (explanation != null || userAction != null) {
                    return Optional.of(join(explanation, userAction));
                }
            }
        }
        return Optional.empty();
    }

    private static String join(String explanation, String userAction) {
        if (explanation == null) {
            return userAction;
        }
        if (userAction == null) {
            return explanation;
        }
        return explanation + " " + userAction;
    }

    private static Throwable nextInChain(Throwable current) {
        if (current instanceof JMSException jmsException && jmsException.getLinkedException() != null) {
            return jmsException.getLinkedException();
        }
        return current.getCause() == current ? null : current.getCause();
    }

    // Reason codes referenced by the error translator, named rather than inlined as magic integers.
    public static final int MQRC_CONNECTION_BROKEN = MQConstants.MQRC_CONNECTION_BROKEN;         // 2009
    public static final int MQRC_NOT_AUTHORIZED = MQConstants.MQRC_NOT_AUTHORIZED;               // 2035
    public static final int MQRC_Q_MGR_NAME_ERROR = MQConstants.MQRC_Q_MGR_NAME_ERROR;           // 2058
    public static final int MQRC_Q_MGR_NOT_AVAILABLE = MQConstants.MQRC_Q_MGR_NOT_AVAILABLE;     // 2059
    public static final int MQRC_UNKNOWN_OBJECT_NAME = MQConstants.MQRC_UNKNOWN_OBJECT_NAME;     // 2085
    public static final int MQRC_HOST_NOT_AVAILABLE = MQConstants.MQRC_HOST_NOT_AVAILABLE;       // 2538
    public static final int MQRC_UNKNOWN_CHANNEL_NAME = MQConstants.MQRC_UNKNOWN_CHANNEL_NAME;   // 2540
    public static final int MQRC_CHANNEL_NOT_AVAILABLE = MQConstants.MQRC_CHANNEL_NOT_AVAILABLE; // 2537
    public static final int MQRC_JSSE_ERROR = MQConstants.MQRC_JSSE_ERROR;                       // 2397
    public static final int MQRC_UNSUPPORTED_CIPHER_SUITE = MQConstants.MQRC_UNSUPPORTED_CIPHER_SUITE; // 2400
}
