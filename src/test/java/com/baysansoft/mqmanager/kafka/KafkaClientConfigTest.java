package com.baysansoft.mqmanager.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.support.KafkaTestFixture;

/**
 * Reads every setting back off the built configuration, the same technique
 * {@code IbmMqConnectionFactoryBuilderTest} uses — and for the same reason. Kafka <em>silently
 * ignores</em> a key it does not recognise (it logs "supplied but isn't a known config" at WARN and
 * carries on), so a typo'd constant would leave the default in place and no mock could ever notice.
 */
class KafkaClientConfigTest {

    private final KafkaClientConfig config = new KafkaClientConfig(new MqManagerProperties());

    @Test
    @DisplayName("the browse consumer has no group id at all and never auto-commits")
    void browseCannotDisturbAConsumerGroup() {
        Map<String, Object> consumer = config.consumerConfig(KafkaTestFixture.profile(), null);

        // These two together are the entire "browsing is non-destructive" guarantee. Reads use
        // assign(), so no group is needed — and with no group id there is no group to create or
        // leave an offset in, whatever the code does afterwards.
        assertThat(consumer).doesNotContainKey(ConsumerConfig.GROUP_ID_CONFIG);
        assertThat(consumer).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        assertThat(consumer).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        assertThat(consumer).containsEntry(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    }

    @Test
    @DisplayName("the producer bounds its metadata wait, which is what a missing topic hangs on")
    void producerFailsFastOnAnUnknownTopic() {
        Map<String, Object> producer = config.producerConfig(KafkaTestFixture.profile(), null);

        // Kafka's default max.block.ms is 60 seconds. Left alone, a typo'd topic name freezes the
        // request thread for a minute before anything is reported.
        assertThat(producer).containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        assertThat(producer).containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000);
        assertThat(producer).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
        assertThat(producer).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
    }

    @Test
    @DisplayName("every client bounds connection setup, so a black-holed host cannot outlast the request")
    void connectionSetupIsBounded() {
        for (Map<String, Object> client : allClients()) {
            assertThat(client).containsEntry(CommonClientConfigs.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
            assertThat(client).containsEntry(CommonClientConfigs.DEFAULT_API_TIMEOUT_MS_CONFIG, 5_000);
            // Defaults here are 10s and 30s, both longer than every other bound in this tool.
            assertThat(client)
                    .containsEntry(CommonClientConfigs.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG, 5_000L)
                    .containsEntry(CommonClientConfigs.SOCKET_CONNECTION_SETUP_TIMEOUT_MAX_MS_CONFIG,
                            5_000L);
            assertThat(client).containsEntry(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG,
                    "localhost:9092");
            assertThat(client).containsEntry(CommonClientConfigs.CLIENT_ID_CONFIG, "mq-mebaysanization");
        }
    }

    @Test
    @DisplayName("without a username the connection is plain PLAINTEXT and carries no SASL settings")
    void noCredentialsMeansNoSasl() {
        Map<String, Object> client = config.adminConfig(KafkaTestFixture.profile(), null);

        assertThat(client).containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT");
        assertThat(client).doesNotContainKey(SaslConfigs.SASL_JAAS_CONFIG);
        assertThat(client).doesNotContainKey(SaslConfigs.SASL_MECHANISM);
    }

    @Test
    @DisplayName("a username switches on SASL/PLAIN — over an unencrypted connection, by design")
    void credentialsSwitchOnSaslPlaintext() {
        ConnectionProfile profile = KafkaTestFixture.profile();
        profile.setUsername("app");

        Map<String, Object> client = config.adminConfig(profile, "passw0rd");

        assertThat(client).containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG,
                "SASL_PLAINTEXT");
        assertThat(client).containsEntry(SaslConfigs.SASL_MECHANISM, "PLAIN");
        assertThat(client.get(SaslConfigs.SASL_JAAS_CONFIG))
                .isEqualTo("org.apache.kafka.common.security.plain.PlainLoginModule required "
                        + "username=\"app\" password=\"passw0rd\";");
    }

    @Test
    @DisplayName("a password containing a quote or a backslash is escaped, not spliced into the JAAS config")
    void jaasSpecialCharactersAreEscaped() {
        ConnectionProfile profile = KafkaTestFixture.profile();
        profile.setUsername("app");

        // Unescaped, the closing quote would end the password early and the rest would be parsed as
        // further JAAS options — a config-injection hole, not just a broken login.
        Map<String, Object> client = config.adminConfig(profile, "pa\"ss\\word");

        assertThat(client.get(SaslConfigs.SASL_JAAS_CONFIG))
                .isEqualTo("org.apache.kafka.common.security.plain.PlainLoginModule required "
                        + "username=\"app\" password=\"pa\\\"ss\\\\word\";");
    }

    @Test
    @DisplayName("a null password with a username still produces a parseable JAAS config")
    void missingPasswordDoesNotProduceLiteralNull() {
        ConnectionProfile profile = KafkaTestFixture.profile();
        profile.setUsername("app");

        assertThat(config.adminConfig(profile, null).get(SaslConfigs.SASL_JAAS_CONFIG))
                .asString()
                .contains("password=\"\";")
                .doesNotContain("null");
    }

    private java.util.List<Map<String, Object>> allClients() {
        ConnectionProfile profile = KafkaTestFixture.profile();
        return java.util.List.of(
                config.consumerConfig(profile, null),
                config.producerConfig(profile, null),
                config.adminConfig(profile, null));
    }
}
