package com.baysansoft.mqmanager.jms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.messaging.model.DeleteOutcome;
import com.baysansoft.mqmanager.jms.provider.ActiveMqClassicDestinationLister;
import com.baysansoft.mqmanager.jms.provider.ArtemisDestinationLister;
import com.baysansoft.mqmanager.jms.provider.IbmMqDestinationLister;
import com.baysansoft.mqmanager.jms.provider.IbmMqDiagnostics;
import com.baysansoft.mqmanager.web.MqOperationException;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Queue;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;

/**
 * Drives the delete path with a mocked JMS provider.
 *
 * <p>This is where the three outcomes of a failed selector consume are pinned down. They cannot be
 * produced on demand against a real embedded broker — ActiveMQ's selector-paging stall needs a
 * production-scale queue under cursor pressure — but they are exactly the cases where getting it wrong
 * tells a user a message is gone when it is still on the queue.
 */
class JmsDeleteDisambiguationTest {

    private static final String TARGET_ID = "ID:target-message-1:1:1:1";

    private Session session;
    private MessageConsumer consumer;
    private QueueBrowser browser;
    private JmsMessagingOperations messaging;
    private ConnectionProfile profile;

    @BeforeEach
    void setUp() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        session = mock(Session.class);
        consumer = mock(MessageConsumer.class);
        browser = mock(QueueBrowser.class);
        Queue queue = mock(Queue.class);

        when(factory.createConnection()).thenReturn(connection);
        when(connection.createSession(anyBoolean(), anyInt())).thenReturn(session);
        when(session.createQueue(anyString())).thenReturn(queue);
        when(session.createConsumer(any(), anyString())).thenReturn(consumer);
        when(session.createBrowser(any())).thenReturn(browser);

        ConnectionFactoryBuilder builder = new StubBuilder(factory);
        StubRegistry registry = new StubRegistry(builder);
        MqManagerProperties properties = new MqManagerProperties();
        messaging = new JmsMessagingOperations(
                registry,
                // The delete path never lists, so the real listers are wired in only to satisfy the
                // registry's "every JMS provider must have one" check.
                new DestinationListerRegistry(List.of(
                        new ActiveMqClassicDestinationLister(registry, properties),
                        new ArtemisDestinationLister(registry, properties),
                        new IbmMqDestinationLister(properties))),
                p -> null,
                new JmsErrorTranslator(new IbmMqDiagnostics()),
                new MessageMapper(),
                properties);

        profile = new ConnectionProfile();
        profile.setName("stub");
        profile.setProvider(Provider.ACTIVE_MQ);
        profile.setHost("localhost");
        profile.setPort(61616);
    }

    private static Message messageWithId(String id) throws Exception {
        Message message = mock(Message.class);
        when(message.getJMSMessageID()).thenReturn(id);
        return message;
    }

    private void browserReturns(Message... messages) throws Exception {
        when(browser.getEnumeration()).thenReturn(Collections.enumeration(List.of(messages)));
    }

    @Test
    @DisplayName("the consumer delivers the requested message: commit, and report it deleted")
    void deletesWhenSelectorFindsTheMessage() throws Exception {
        // Built before the outer when(...), never inside it: messageWithId does its own stubbing, and
        // Mockito rejects a stub started while another is still open.
        Message delivered = messageWithId(TARGET_ID);
        when(consumer.receive(anyLong())).thenReturn(delivered);

        assertThat(messaging.deleteMessageDetailed(profile, "q", TARGET_ID))
                .isEqualTo(DeleteOutcome.DELETED);

        verify(session).commit();
        verify(session, never()).rollback();
    }

    @Test
    @DisplayName("nothing delivered and the message is genuinely absent: report not found")
    void reportsNotFoundWhenTheMessageIsReallyGone() throws Exception {
        when(consumer.receive(anyLong())).thenReturn(null);
        browserReturns(messageWithId("ID:someone-else-1:1:1:1"), messageWithId("ID:another-1:1:1:1"));

        assertThat(messaging.deleteMessageDetailed(profile, "q", TARGET_ID))
                .isEqualTo(DeleteOutcome.NOT_FOUND);

        verify(session).rollback();
        verify(session, never()).commit();
    }

    @Test
    @DisplayName("nothing delivered but the message IS on the queue: 409 unreachable, never 'not found'")
    void reportsUnreachableWhenTheMessageIsStillThere() throws Exception {
        when(consumer.receive(anyLong())).thenReturn(null);
        browserReturns(messageWithId("ID:other-1:1:1:1"), messageWithId(TARGET_ID));

        assertThatThrownBy(() -> messaging.deleteMessageDetailed(profile, "q", TARGET_ID))
                .isInstanceOf(MqOperationException.class)
                .extracting(thrown -> ((MqOperationException) thrown).getCode())
                .isEqualTo("MESSAGE_UNREACHABLE");

        verify(session, never()).commit();
    }

    @Test
    @DisplayName("nothing delivered and the browse itself was cut short: refuse to guess")
    void reportsNotLocatableWhenTheBrowseWasTruncated() throws Exception {
        MqManagerProperties properties = new MqManagerProperties();
        properties.getDelete().setDisambiguationLimit(3);
        StubRegistry registry = new StubRegistry(new StubBuilder(factoryReturning()));
        messaging = new JmsMessagingOperations(
                registry,
                new DestinationListerRegistry(List.of(
                        new ActiveMqClassicDestinationLister(registry, properties),
                        new ArtemisDestinationLister(registry, properties),
                        new IbmMqDestinationLister(properties))),
                p -> null,
                new JmsErrorTranslator(new IbmMqDiagnostics()),
                new MessageMapper(),
                properties);

        when(consumer.receive(anyLong())).thenReturn(null);
        // More messages than the scan limit, none of them the target: the scan cannot rule anything out.
        browserReturns(messageWithId("ID:a-1:1:1:1"), messageWithId("ID:b-1:1:1:1"),
                messageWithId("ID:c-1:1:1:1"), messageWithId("ID:d-1:1:1:1"));

        assertThatThrownBy(() -> messaging.deleteMessageDetailed(profile, "q", TARGET_ID))
                .isInstanceOf(MqOperationException.class)
                .extracting(thrown -> ((MqOperationException) thrown).getCode())
                .isEqualTo("MESSAGE_NOT_LOCATABLE");
    }

    @Test
    @DisplayName("a mismatched delivery is rolled back, never committed")
    void rollsBackWhenTheBrokerReturnsTheWrongMessage() throws Exception {
        Message wrongMessage = messageWithId("ID:completely-different:1:1:1");
        when(consumer.receive(anyLong())).thenReturn(wrongMessage);

        assertThatThrownBy(() -> messaging.deleteMessageDetailed(profile, "q", TARGET_ID))
                .isInstanceOf(MqOperationException.class)
                .extracting(thrown -> ((MqOperationException) thrown).getCode())
                .isEqualTo("MESSAGE_ID_MISMATCH");

        InOrder inOrder = Mockito.inOrder(session);
        inOrder.verify(session).rollback();
        verify(session, never()).commit();
    }

    @Test
    @DisplayName("an invalid id is rejected before any consumer is opened")
    void rejectsBadIdsWithoutTouchingTheBroker() throws Exception {
        for (String bad : List.of("ID:000000000000000000000000000000000000000000000000",
                "not-an-id", "ID:", "")) {
            assertThatThrownBy(() -> messaging.deleteMessageDetailed(profile, "q", bad))
                    .isInstanceOf(MqOperationException.class)
                    .extracting(thrown -> ((MqOperationException) thrown).getCode())
                    .isEqualTo("MESSAGE_ID_INVALID");
        }
        assertThatThrownBy(() -> messaging.deleteMessageDetailed(profile, "q", null))
                .isInstanceOf(MqOperationException.class);

        verify(session, never()).createConsumer(any(), anyString());
    }

    private ConnectionFactory factoryReturning() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createSession(anyBoolean(), anyInt())).thenReturn(session);
        return factory;
    }

    /** Minimal stand-ins so the test drives the real operations class, not a mock of it. */
    private record StubBuilder(ConnectionFactory factory) implements ConnectionFactoryBuilder {

        @Override
        public Provider provider() {
            return Provider.ACTIVE_MQ;
        }

        @Override
        public ConnectionFactory build(ConnectionProfile profile, String plainPassword) {
            return factory;
        }
    }

    private static final class StubRegistry extends ConnectionFactoryRegistry {

        private final ConnectionFactoryBuilder builder;

        private StubRegistry(ConnectionFactoryBuilder builder) {
            super(List.of(builder, new NoopBuilder(Provider.ARTEMIS), new NoopBuilder(Provider.IBM_MQ)));
            this.builder = builder;
        }

        @Override
        public ConnectionFactoryBuilder forProvider(Provider provider) {
            return builder;
        }
    }

    private record NoopBuilder(Provider provider) implements ConnectionFactoryBuilder {

        @Override
        public ConnectionFactory build(ConnectionProfile profile, String plainPassword) {
            throw new UnsupportedOperationException();
        }
    }
}
