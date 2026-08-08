package com.baysansoft.mqmanager.kafka;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.ConnectionProfile;

/**
 * Builds the client configuration for a profile.
 *
 * <p>Two things here are load-bearing.
 *
 * <p><strong>Timeouts.</strong> Kafka's defaults are measured in minutes — {@code max.block.ms} alone
 * is 60 seconds, and it is what a producer waits on when a topic does not exist. A human is waiting on
 * an HTTP request, so every bound is pulled down to a few seconds. Without this a dead broker hangs the
 * UI rather than reporting an error.
 *
 * <p><strong>The browse consumer has no {@code group.id} at all.</strong> Every read in this tool uses
 * {@code assign()}, never {@code subscribe()}, so no group is needed — and with no group id there is no
 * group to create, join, or leave an offset in. Combined with {@code enable.auto.commit=false} that is
 * what makes browsing provably unable to disturb a real consumer group.
 */
@Component
public class KafkaClientConfig {

    private final MqManagerProperties properties;

    public KafkaClientConfig(MqManagerProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> consumerConfig(ConnectionProfile profile, String plainPassword) {
        Map<String, Object> config = common(profile, plainPassword);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        // Deliberately no GROUP_ID_CONFIG. See the class javadoc: reads use assign(), so a group is
        // neither needed nor wanted, and its absence is what guarantees no offsets are ever committed.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Uncommitted transactional records are not really on the topic yet; showing them would be the
        // Kafka equivalent of browsing an IBM MQ queue's uncommitted messages, which is also not done.
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        return config;
    }

    public Map<String, Object> producerConfig(ConnectionProfile profile, String plainPassword) {
        Map<String, Object> config = common(profile, plainPassword);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        // The one that matters most: this is the bound on waiting for metadata for a topic that may
        // not exist. Left at its 60s default, a typo'd topic name freezes the request thread.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, timeoutMs());
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, timeoutMs());
        // An admin tool sends one record at a time; idempotence would only add an InitProducerId
        // round trip and require cluster-level IDEMPOTENT_WRITE that the user may not have.
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        config.put(ProducerConfig.RETRIES_CONFIG, 0);
        return config;
    }

    public Map<String, Object> adminConfig(ConnectionProfile profile, String plainPassword) {
        Map<String, Object> config = common(profile, plainPassword);
        config.put(AdminClientConfig.RETRIES_CONFIG, 0);
        return config;
    }

    private Map<String, Object> common(ConnectionProfile profile, String plainPassword) {
        int timeoutMs = timeoutMs();
        Map<String, Object> config = new HashMap<>();
        config.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(profile));
        config.put(CommonClientConfigs.CLIENT_ID_CONFIG, properties.getKafka().getClientId());
        config.put(CommonClientConfigs.REQUEST_TIMEOUT_MS_CONFIG, timeoutMs);
        config.put(CommonClientConfigs.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMs);
        // Defaults are 10s and 30s. A black-holed host would otherwise outlast every other bound here.
        config.put(CommonClientConfigs.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG, (long) timeoutMs);
        config.put(CommonClientConfigs.SOCKET_CONNECTION_SETUP_TIMEOUT_MAX_MS_CONFIG, (long) timeoutMs);
        applySecurity(config, profile, plainPassword);
        return config;
    }

    /**
     * v1 is plaintext only, matching the rest of this tool's scope. Credentials, when present, are sent
     * as SASL/PLAIN over an unencrypted connection — the README says so in as many words.
     */
    private static void applySecurity(Map<String, Object> config, ConnectionProfile profile,
            String plainPassword) {
        if (!StringUtils.hasText(profile.getUsername())) {
            config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.PLAINTEXT.name);
            return;
        }
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SASL_PLAINTEXT.name);
        config.put(SaslConfigs.SASL_MECHANISM, "PLAIN");
        config.put(SaslConfigs.SASL_JAAS_CONFIG,
                "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                        + escapeJaas(profile.getUsername()) + "\" password=\""
                        + escapeJaas(plainPassword == null ? "" : plainPassword) + "\";");
    }

    /**
     * The JAAS config is one string that Kafka re-parses, so a quote or a backslash in a password would
     * either break the parse or splice in an arbitrary extra option. Escape both, in that order.
     */
    static String escapeJaas(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String bootstrapServers(ConnectionProfile profile) {
        String value = profile.getBootstrapServers();
        return value == null ? "" : value.trim();
    }

    private int timeoutMs() {
        return Math.toIntExact(properties.getKafka().getApiTimeout().toMillis());
    }
}
