package com.baysansoft.mqmanager.support;

import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

/**
 * Fills a queue over a single connection.
 *
 * <p>The application deliberately opens one connection per operation, which is right for a
 * human-driven admin tool but makes seeding thousands of test messages through {@code send()}
 * pathologically slow. Tests that only need messages on a queue use this instead.
 */
public final class QueueSeeder {

    private QueueSeeder() {
    }

    public static void seed(String brokerUrl, String queueName, int count, String bodyPrefix)
            throws Exception {
        org.apache.activemq.ActiveMQConnectionFactory factory =
                new org.apache.activemq.ActiveMQConnectionFactory(brokerUrl);

        try (Connection connection = factory.createConnection()) {
            connection.start();
            try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Queue queue = session.createQueue(queueName);
                try (MessageProducer producer = session.createProducer(queue)) {
                    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
                    for (int i = 0; i < count; i++) {
                        TextMessage message = session.createTextMessage(bodyPrefix + i);
                        message.setStringProperty("seq", String.valueOf(i));
                        producer.send(message);
                    }
                }
            }
        }
    }
}
