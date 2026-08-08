package com.baysansoft.mqmanager.jms.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Queue;

/**
 * The whole IBM MQ verification strategy for v1, because there is no queue manager to test against.
 *
 * <p>It needs no broker and no network: it builds the factory and reads every property straight back
 * off it. That catches the failure mode that actually matters here — a wrong constant name or a
 * property that never got set — at build time rather than in front of a real queue manager.
 */
class IbmMqConnectionFactoryBuilderTest {

    private final IbmMqConnectionFactoryBuilder builder = new IbmMqConnectionFactoryBuilder();

    private static ConnectionProfile profile() {
        ConnectionProfile p = new ConnectionProfile();
        p.setName("ibm");
        p.setProvider(Provider.IBM_MQ);
        p.setHost("mq.example.internal");
        p.setPort(1414);
        p.setChannel("DEV.APP.SVRCONN");
        p.setQueueManagerName("QM1");
        p.setUsername("app");
        return p;
    }

    @Test
    void registersItselfForIbmMq() {
        assertThat(builder.provider()).isEqualTo(Provider.IBM_MQ);
    }

    @Test
    @DisplayName("every connection property round-trips off the built factory")
    void propertiesRoundTrip() throws Exception {
        ConnectionFactory built = builder.build(profile(), "passw0rd");

        assertThat(built).isInstanceOf(MQConnectionFactory.class);
        MQConnectionFactory factory = (MQConnectionFactory) built;

        assertThat(factory.getIntProperty(WMQConstants.WMQ_CONNECTION_MODE))
                .as("must be client/TCP mode - bindings mode would require a native MQ install")
                .isEqualTo(WMQConstants.WMQ_CM_CLIENT);

        assertThat(factory.getStringProperty(WMQConstants.WMQ_HOST_NAME)).isEqualTo("mq.example.internal");
        assertThat(factory.getIntProperty(WMQConstants.WMQ_PORT)).isEqualTo(1414);
        assertThat(factory.getStringProperty(WMQConstants.WMQ_CHANNEL)).isEqualTo("DEV.APP.SVRCONN");
        assertThat(factory.getStringProperty(WMQConstants.WMQ_QUEUE_MANAGER)).isEqualTo("QM1");
        assertThat(factory.getStringProperty(WMQConstants.WMQ_APPLICATIONNAME))
                .isEqualTo(IbmMqConnectionFactoryBuilder.APPLICATION_NAME);

        assertThat(factory.getBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP))
                .as("MQCSP keeps user IDs from being truncated at 12 chars into a confusing 2035")
                .isTrue();
        assertThat(factory.getStringProperty(WMQConstants.USERID)).isEqualTo("app");
        assertThat(factory.getStringProperty(WMQConstants.PASSWORD)).isEqualTo("passw0rd");
    }

    @Test
    @DisplayName("a blank channel falls back to SYSTEM.DEF.SVRCONN")
    void blankChannelUsesDefault() throws Exception {
        ConnectionProfile p = profile();
        p.setChannel("  ");

        MQConnectionFactory factory = (MQConnectionFactory) builder.build(p, null);

        assertThat(factory.getStringProperty(WMQConstants.WMQ_CHANNEL))
                .isEqualTo(IbmMqConnectionFactoryBuilder.DEFAULT_CHANNEL);
    }

    @Test
    @DisplayName("a blank queue manager name is sent as empty, meaning the default queue manager")
    void blankQueueManagerIsEmptyString() throws Exception {
        ConnectionProfile p = profile();
        p.setQueueManagerName(null);

        MQConnectionFactory factory = (MQConnectionFactory) builder.build(p, null);

        assertThat(factory.getStringProperty(WMQConstants.WMQ_QUEUE_MANAGER)).isEmpty();
    }

    @Test
    @DisplayName("no username means no credential properties are set at all")
    void withoutUsernameNoCredentialsAreSet() throws Exception {
        ConnectionProfile p = profile();
        p.setUsername(null);

        MQConnectionFactory factory = (MQConnectionFactory) builder.build(p, null);

        assertThat(factory.getStringProperty(WMQConstants.USERID)).isNullOrEmpty();
        assertThat(factory.getStringProperty(WMQConstants.PASSWORD)).isNullOrEmpty();
    }

    @Test
    @DisplayName("tuneDestination ignores a queue that is not an MQDestination instead of throwing")
    void tuneDestinationIsSafeForForeignQueueTypes() {
        Queue notAnMqDestination = Mockito.mock(Queue.class);

        assertThatCode(() -> builder.tuneDestination(notAnMqDestination)).doesNotThrowAnyException();
    }
}
