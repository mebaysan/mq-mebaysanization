package com.baysansoft.mqmanager.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;

import com.baysansoft.mqmanager.domain.ConnectionProfile;

/**
 * Creates the three Kafka clients an operation may need.
 *
 * <p>The return types are the <em>interfaces</em> on purpose: that is what lets a test hand back a
 * {@code MockProducer}, a {@code MockConsumer} or a Mockito {@code Admin} and drive the real
 * {@link KafkaMessagingOperations} without a broker anywhere.
 *
 * <p>Clients are created per operation and closed by the caller. This is a human-driven admin tool
 * with no steady traffic, so a pooled or long-lived client would only add ways to be stale.
 *
 * @see DefaultKafkaClientFactory
 */
public interface KafkaClientFactory {

    Producer<String, byte[]> producer(ConnectionProfile profile, String plainPassword);

    Consumer<String, byte[]> consumer(ConnectionProfile profile, String plainPassword);

    Admin admin(ConnectionProfile profile, String plainPassword);
}
