package com.baysansoft.mqmanager.jms.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.messaging.model.TargetClient;
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

    /**
     * A destination is a local property bag until it is used, so this needs no queue manager — the same
     * reason the factory assertions above need none. Written fully qualified: {@code MQQueue} names
     * three different classes in this jar, and {@code ImportGuardTest} bans the import in {@code
     * src/main} for exactly that reason.
     */
    private static com.ibm.mq.jakarta.jms.MQQueue queue() throws Exception {
        return new com.ibm.mq.jakarta.jms.MQQueue("DEV.QUEUE.1");
    }

    @Test
    @DisplayName("asking for target client MQ sets TARGCLIENT(MQ), which is the one thing that stops an "
            + "MQRFH2 header being written ahead of the body")
    void targetClientMqTurnsOffTheRfh2Header() throws Exception {
        com.ibm.mq.jakarta.jms.MQQueue destination = queue();

        builder.applyTargetClient(destination, TargetClient.MQ);

        // This is the assertion the whole fix rests on, and the only one here that proves anything on
        // its own: getTargetClient() swallows a JMSException and answers 0, so a "still JMS" assertion
        // cannot tell a real 0 from a failed read. A 1 can only have been set.
        assertThat(destination.getTargetClient())
                .as("TARGCLIENT(MQ) — the queue holds the body and nothing else")
                .isEqualTo(WMQConstants.WMQ_CLIENT_NONJMS_MQ);
    }

    @Test
    @DisplayName("asking for target client JMS pins TARGCLIENT(JMS) rather than trusting the client "
            + "default, so an mqclient.ini stanza cannot quietly change what a JMS send puts on the wire")
    void targetClientJmsIsPinnedRatherThanLeftToTheClientDefault() throws Exception {
        com.ibm.mq.jakarta.jms.MQQueue destination = queue();
        // Set the other value first, so this proves the setter ran rather than proving the default.
        builder.applyTargetClient(destination, TargetClient.MQ);

        builder.applyTargetClient(destination, TargetClient.JMS);

        assertThat(destination.getTargetClient()).isEqualTo(WMQConstants.WMQ_CLIENT_JMS_COMPLIANT);
    }

    @Test
    @DisplayName("the two destination tunings compose: choosing a target client leaves read-ahead "
            + "disabled, so a send option can never start a purge count under-reporting")
    void readAheadSurvivesATargetClient() throws Exception {
        com.ibm.mq.jakarta.jms.MQQueue destination = queue();

        builder.tuneDestination(destination);
        builder.applyTargetClient(destination, TargetClient.MQ);

        assertThat(destination.getReadAheadAllowed())
                .isEqualTo(WMQConstants.WMQ_READ_AHEAD_ALLOWED_DISABLED);
        assertThat(destination.getTargetClient()).isEqualTo(WMQConstants.WMQ_CLIENT_NONJMS_MQ);
    }

    @Test
    @DisplayName("tuneDestination on its own never touches the target client, which is what keeps "
            + "browse, depth, purge and delete off a property only a PUT reads")
    void tuneDestinationAloneNeverTouchesTheTargetClient() throws Exception {
        com.ibm.mq.jakarta.jms.MQQueue destination = queue();
        builder.applyTargetClient(destination, TargetClient.MQ);

        builder.tuneDestination(destination);

        assertThat(destination.getTargetClient()).isEqualTo(WMQConstants.WMQ_CLIENT_NONJMS_MQ);
    }

    @Test
    @DisplayName("applying a target client to a queue that is not an MQDestination is ignored instead "
            + "of throwing")
    void applyTargetClientIgnoresAQueueThatIsNotAnMqDestination() {
        Queue notAnMqDestination = Mockito.mock(Queue.class);

        assertThatCode(() -> builder.applyTargetClient(notAnMqDestination, TargetClient.MQ))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the application name fits IBM MQ's 28-character APPLTAG, which truncates silently")
    void applicationNameFitsTheApplTagLimit() {
        // MQCSP/APPLTAG is capped at 28 characters and the queue manager simply cuts anything longer,
        // so a rename that overflows would show up only as a puzzling half-name in DISPLAY CONN(*).
        assertThat(IbmMqConnectionFactoryBuilder.APPLICATION_NAME)
                .isNotBlank()
                .hasSizeLessThanOrEqualTo(28);
    }
}
