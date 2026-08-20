package com.baysansoft.mqmanager.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.CreateTopicsOptions;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.errors.InvalidReplicationFactorException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.messaging.model.CreateTopicCommand;
import com.baysansoft.mqmanager.messaging.model.CreateTopicOutcome;
import com.baysansoft.mqmanager.support.KafkaTestFixture;
import com.baysansoft.mqmanager.web.MqOperationException;

/**
 * Creating a topic, over a mocked {@code Admin}.
 *
 * <p>{@code Admin} is mocked rather than faked because {@code CreateTopicsResult} has no public
 * constructor — the same reason the read paths in this tool go through the consumer. The value here is
 * proving that the partition count, replication factor and configs the caller stated reach the
 * {@code NewTopic} verbatim, and that the two client-side mistakes ("already exists", "too many
 * replicas") come back as the honest status rather than a generic 502.
 */
class KafkaCreateTopicTest {

    private Admin admin;
    private KafkaMessagingOperations messaging;
    private ConnectionProfile profile;

    @BeforeEach
    void setUp() {
        admin = mock(Admin.class);
        messaging = KafkaTestFixture.operations(KafkaTestFixture.producer(),
                KafkaTestFixture.consumer(), admin);
        profile = KafkaTestFixture.profile();
    }

    private void createSucceeds() {
        CreateTopicsResult result = mock(CreateTopicsResult.class);
        when(admin.createTopics(anyCollection(), any(CreateTopicsOptions.class))).thenReturn(result);
        when(result.all()).thenReturn(KafkaFuture.completedFuture(null));
    }

    @Test
    @DisplayName("the partition count, replication factor and configs reach the NewTopic as given")
    void passesEveryFieldThrough() {
        createSucceeds();

        CreateTopicOutcome outcome = messaging.createTopic(profile, new CreateTopicCommand("orders", 6,
                (short) 3, Map.of("retention.ms", "604800000", "cleanup.policy", "compact")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<NewTopic>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(admin).createTopics(captor.capture(), any(CreateTopicsOptions.class));
        assertThat(captor.getValue()).hasSize(1);
        NewTopic sent = captor.getValue().iterator().next();
        assertThat(sent.name()).isEqualTo("orders");
        assertThat(sent.numPartitions()).isEqualTo(6);
        assertThat(sent.replicationFactor()).isEqualTo((short) 3);
        assertThat(sent.configs())
                .containsEntry("retention.ms", "604800000")
                .containsEntry("cleanup.policy", "compact");

        // The result echoes back what was accepted, so the UI states the topic rather than the request.
        assertThat(outcome.name()).isEqualTo("orders");
        assertThat(outcome.partitions()).isEqualTo(6);
        assertThat(outcome.replicationFactor()).isEqualTo((short) 3);
        assertThat(outcome.note()).contains("admin API");
    }

    @Test
    @DisplayName("no configs leaves the NewTopic's own configs untouched, so the broker uses its defaults")
    void noConfigsIsFine() {
        createSucceeds();

        messaging.createTopic(profile, new CreateTopicCommand("plain", 1, (short) 1, Map.of()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<NewTopic>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(admin).createTopics(captor.capture(), any(CreateTopicsOptions.class));
        // configs() is never called for an empty map, so NewTopic keeps its own null — the broker then
        // applies its cluster defaults rather than being handed an empty override set.
        assertThat(captor.getValue().iterator().next().configs()).isNull();
    }

    @Test
    @DisplayName("the admin client is closed once the create completes, since one is opened per operation")
    void closesAfterSuccess() {
        createSucceeds();

        messaging.createTopic(profile, new CreateTopicCommand("plain", 1, (short) 1, Map.of()));

        verify(admin).close(any());
    }

    @Test
    @DisplayName("a topic that already exists is a 409, not a create that silently did nothing")
    void alreadyExistsIsConflict() throws Exception {
        failCreateWith(new TopicExistsException("orders already exists"));

        assertThatThrownBy(() ->
                messaging.createTopic(profile, new CreateTopicCommand("orders", 1, (short) 1, Map.of())))
                .isInstanceOfSatisfying(MqOperationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("QUEUE_ALREADY_EXISTS");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("orders");
                });
        verify(admin).close(any());
    }

    @Test
    @DisplayName("a replication factor larger than the cluster is a 400 client mistake, not a 502")
    void tooManyReplicasIsBadRequest() throws Exception {
        failCreateWith(new InvalidReplicationFactorException("replication factor 3 larger than 1 broker"));

        assertThatThrownBy(() ->
                messaging.createTopic(profile, new CreateTopicCommand("orders", 1, (short) 3, Map.of())))
                .isInstanceOfSatisfying(MqOperationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVALID_REPLICATION_FACTOR");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @SuppressWarnings("unchecked")
    private void failCreateWith(Throwable cause) throws Exception {
        CreateTopicsResult result = mock(CreateTopicsResult.class);
        KafkaFuture<Void> future = mock(KafkaFuture.class);
        when(future.get(anyLong(), any())).thenThrow(new ExecutionException(cause));
        when(result.all()).thenReturn(future);
        when(admin.createTopics(anyCollection(), any(CreateTopicsOptions.class))).thenReturn(result);
    }
}
