package com.baysansoft.mqmanager.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.stereotype.Component;

import com.baysansoft.mqmanager.domain.ConnectionProfile;

/**
 * The real clients. Constructing any of these opens no connection — Kafka connects lazily on the first
 * call — so this is safe to do per operation and safe to do at a point where the broker may be dead.
 */
@Component
public class DefaultKafkaClientFactory implements KafkaClientFactory {

    private final KafkaClientConfig config;

    public DefaultKafkaClientFactory(KafkaClientConfig config) {
        this.config = config;
    }

    @Override
    public Producer<String, byte[]> producer(ConnectionProfile profile, String plainPassword) {
        return new KafkaProducer<>(config.producerConfig(profile, plainPassword));
    }

    @Override
    public Consumer<String, byte[]> consumer(ConnectionProfile profile, String plainPassword) {
        return new KafkaConsumer<>(config.consumerConfig(profile, plainPassword));
    }

    @Override
    public Admin admin(ConnectionProfile profile, String plainPassword) {
        return Admin.create(config.adminConfig(profile, plainPassword));
    }
}
