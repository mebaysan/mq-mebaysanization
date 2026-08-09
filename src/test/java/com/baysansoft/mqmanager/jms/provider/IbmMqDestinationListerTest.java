package com.baysansoft.mqmanager.jms.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Hashtable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.messaging.model.DestinationListing;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.constants.MQConstants;

/**
 * Everything about the IBM MQ lister that can be asserted without a queue manager.
 *
 * <p>Same doctrine as {@code IbmMqConnectionFactoryBuilderTest}, and for the same reason: there is no
 * IBM MQ in this build's test suite, so reading the connection properties back key by key is the only
 * thing that catches a wrong {@code CMQC.*} constant before someone runs it against a real broker.
 */
class IbmMqDestinationListerTest {

    private static ConnectionProfile profile() {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setName("qm1");
        profile.setProvider(Provider.IBM_MQ);
        profile.setHost("mq.example.test");
        profile.setPort(1415);
        profile.setChannel("APP.SVRCONN");
        profile.setQueueManagerName("QM1");
        profile.setUsername("app");
        return profile;
    }

    @Test
    @DisplayName("the connection properties are client mode, with the same application tag as the JMS builder")
    void buildsClientModeProperties() {
        Hashtable<String, Object> connection =
                IbmMqDestinationLister.connectionProperties(profile(), "s3cret");

        assertThat(connection).containsEntry(CMQC.TRANSPORT_PROPERTY, CMQC.TRANSPORT_MQSERIES_CLIENT);
        assertThat(connection).containsEntry(CMQC.HOST_NAME_PROPERTY, "mq.example.test");
        assertThat(connection).containsEntry(CMQC.PORT_PROPERTY, 1415);
        assertThat(connection).containsEntry(CMQC.CHANNEL_PROPERTY, "APP.SVRCONN");
        assertThat(connection).containsEntry(CMQC.APPNAME_PROPERTY, "MQ mebaysanization");
    }

    @Test
    @DisplayName("MQCSP authentication is set explicitly, so a JVM-wide switch cannot silently disable it")
    void setsMqcspExplicitly() {
        Hashtable<String, Object> connection =
                IbmMqDestinationLister.connectionProperties(profile(), "s3cret");

        assertThat(connection).containsEntry(CMQC.USE_MQCSP_AUTHENTICATION_PROPERTY, true);
        assertThat(connection).containsEntry(CMQC.USER_ID_PROPERTY, "app");
        assertThat(connection).containsEntry(CMQC.PASSWORD_PROPERTY, "s3cret");
    }

    @Test
    @DisplayName("with no username, no credential keys are sent at all")
    void omitsCredentialsWhenThereIsNoUser() {
        ConnectionProfile anonymous = profile();
        anonymous.setUsername(null);

        Hashtable<String, Object> connection =
                IbmMqDestinationLister.connectionProperties(anonymous, null);

        assertThat(connection).doesNotContainKeys(CMQC.USER_ID_PROPERTY, CMQC.PASSWORD_PROPERTY,
                CMQC.USE_MQCSP_AUTHENTICATION_PROPERTY);
    }

    @Test
    @DisplayName("defaults fill in for a blank channel, port and queue manager")
    void appliesDefaults() {
        ConnectionProfile sparse = new ConnectionProfile();
        sparse.setProvider(Provider.IBM_MQ);
        sparse.setHost("mq.example.test");

        Hashtable<String, Object> connection =
                IbmMqDestinationLister.connectionProperties(sparse, null);

        assertThat(connection).containsEntry(CMQC.PORT_PROPERTY, 1414);
        assertThat(connection).containsEntry(CMQC.CHANNEL_PROPERTY,
                IbmMqConnectionFactoryBuilder.DEFAULT_CHANNEL);
        // An empty name is legal and means "the default queue manager".
        assertThat(IbmMqDestinationLister.queueManagerName(sparse)).isEmpty();
    }

    @Test
    @DisplayName("the PCF generic name is a prefix match, and a blank prefix means everything")
    void genericNameIsAPrefix() {
        assertThat(IbmMqDestinationLister.genericName("DEV.")).isEqualTo("DEV.*");
        assertThat(IbmMqDestinationLister.genericName("  DEV.  ")).isEqualTo("DEV.*");
        assertThat(IbmMqDestinationLister.genericName(null)).isEqualTo("*");
        assertThat(IbmMqDestinationLister.genericName("  ")).isEqualTo("*");
    }

    @Test
    @DisplayName("SYSTEM.* objects are the queue manager's, and are marked as such")
    void systemObjectsAreInternal() {
        assertThat(IbmMqDestinationLister.isInternal("SYSTEM.ADMIN.COMMAND.QUEUE")).isTrue();
        assertThat(IbmMqDestinationLister.isInternal("DEV.QUEUE.1")).isFalse();
        assertThat(IbmMqDestinationLister.isInternal(null)).isFalse();
    }

    @Test
    @DisplayName("each reason code maps to the listing reason a user can act on")
    void classifiesReasonCodes() {
        assertThat(IbmMqDestinationLister.classify(MQConstants.MQRC_NOT_AUTHORIZED))
                .isEqualTo(DestinationListing.NOT_PERMITTED);
        assertThat(IbmMqDestinationLister.classify(MQConstants.MQRC_UNKNOWN_OBJECT_NAME))
                .isEqualTo(DestinationListing.NOT_AVAILABLE);
        // The command server being stopped is by far the most common cause, so it gets its own reason.
        assertThat(IbmMqDestinationLister.classify(MQConstants.MQRC_NO_MSG_AVAILABLE))
                .isEqualTo(DestinationListing.TIMED_OUT);
        assertThat(IbmMqDestinationLister.classify(MQConstants.MQRCCF_COMMAND_FAILED))
                .isEqualTo(DestinationListing.NOT_AVAILABLE);
        assertThat(IbmMqDestinationLister.classify(MQConstants.MQRCCF_CFH_COMMAND_ERROR))
                .isEqualTo(DestinationListing.NOT_AVAILABLE);
        assertThat(IbmMqDestinationLister.classify(9999)).isEqualTo(DestinationListing.NOT_AVAILABLE);
    }

    @Test
    @DisplayName("the message for a refusal names the fix, not just the code")
    void describesTheFix() {
        assertThat(IbmMqDestinationLister.describe(MQConstants.MQRC_NOT_AUTHORIZED))
                .contains("MQRC_NOT_AUTHORIZED")
                .contains("+dsp");
        assertThat(IbmMqDestinationLister.describe(MQConstants.MQRC_NO_MSG_AVAILABLE))
                .contains("START CMDSERV");
    }
}
