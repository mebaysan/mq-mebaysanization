package com.baysansoft.mqmanager.jms.provider;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.jms.ConnectionFactoryBuilder;
// The Jakarta client. Note the mandatory `.jakarta.` infix in every IBM package below: this artifact
// contains no com.ibm.mq.jms package and no com.ibm.msg.client.wmq.WMQConstants at all, so any snippet
// copied from the web (or from IBM's own Jakarta sample, which is wrong) will not compile.
import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.mq.jakarta.jms.MQDestination;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Queue;

/**
 * Builds an IBM MQ {@code ConnectionFactory} for client (TCP) mode.
 *
 * <p>This class cannot be validated against a real queue manager in v1, so correctness rests on two
 * things: the constant names are verified to exist in the shipped jar, and
 * {@code IbmMqConnectionFactoryBuilderTest} reads every property back off the factory.
 */
@Component
public class IbmMqConnectionFactoryBuilder implements ConnectionFactoryBuilder {

    /** IBM's default server-connection channel, used when the profile leaves it blank. */
    public static final String DEFAULT_CHANNEL = "SYSTEM.DEF.SVRCONN";

    /** Shows up in {@code DISPLAY CONN(*) APPLTAG} on the queue manager. Max 28 characters. */
    static final String APPLICATION_NAME = "MQ mebaysanization";

    @Override
    public Provider provider() {
        return Provider.IBM_MQ;
    }

    @Override
    public ConnectionFactory build(ConnectionProfile profile, String plainPassword) throws JMSException {
        MQConnectionFactory factory = new MQConnectionFactory();

        // Client (TCP) mode, always, hard-coded. WMQ_CM_BINDINGS routes into com.ibm.mq.jmqi.local.LocalMQ,
        // the only code in the jar that calls System.loadLibrary. That needs a native IBM MQ server
        // installation and would break the "Java 21 JRE and nothing else" promise with an
        // UnsatisfiedLinkError that reads like a bug. Bindings mode is never exposed in the UI.
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);

        factory.setStringProperty(WMQConstants.WMQ_HOST_NAME, profile.getHost());
        factory.setIntProperty(WMQConstants.WMQ_PORT, profile.getPort() == null ? 1414 : profile.getPort());
        factory.setStringProperty(WMQConstants.WMQ_CHANNEL, channelOrDefault(profile));

        // An empty queue manager name is legal and means "the default queue manager".
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER,
                profile.getQueueManagerName() == null ? "" : profile.getQueueManagerName());

        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, APPLICATION_NAME);

        if (StringUtils.hasText(profile.getUsername())) {
            // MQCSP is already the default since the 9.3.0 client when both a user and a password are
            // supplied. Setting it explicitly anyway means a JVM-wide
            // -Dcom.ibm.mq.cfg.jmqi.useMQCSPauthentication=N or an mqclient.ini stanza cannot silently
            // drop us into compatibility mode, which truncates user IDs at 12 characters and shows up
            // as a baffling 2035 MQRC_NOT_AUTHORIZED.
            factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
            factory.setStringProperty(WMQConstants.USERID, profile.getUsername());
            factory.setStringProperty(WMQConstants.PASSWORD, plainPassword == null ? "" : plainPassword);
        }

        // Credentials live on the factory, so the shared code path can always call the no-arg
        // createConnection() and stay identical across all three providers.
        return factory;
    }

    /**
     * Disables read-ahead. IBM's own wording: when a MessageConsumer closes, "any unprocessed messages
     * in the internal buffer is lost". During a purge that means messages vanish without being counted,
     * so the reported total silently under-reports and the post-purge depth never reconciles.
     */
    @Override
    public void tuneDestination(Queue queue) throws JMSException {
        if (queue instanceof MQDestination destination) {
            destination.setReadAheadAllowed(WMQConstants.WMQ_READ_AHEAD_ALLOWED_DISABLED);
        }
        // Any other Queue implementation (including test stubs) is left untouched rather than
        // triggering a ClassCastException.
    }

    private static String channelOrDefault(ConnectionProfile profile) {
        return StringUtils.hasText(profile.getChannel()) ? profile.getChannel() : DEFAULT_CHANNEL;
    }
}
