package com.baysansoft.mqmanager.jms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.messaging.model.BrowseResult;
import com.baysansoft.mqmanager.messaging.model.DeleteOutcome;
import com.baysansoft.mqmanager.messaging.model.DepthOutcome;
import com.baysansoft.mqmanager.messaging.model.PurgeOutcome;
import com.baysansoft.mqmanager.messaging.model.QueueMessageView;
import com.baysansoft.mqmanager.support.EmbeddedActiveMqBroker;
import com.baysansoft.mqmanager.support.MessagingTestFixture;
import com.baysansoft.mqmanager.web.MqOperationException;

/**
 * End-to-end coverage of the real {@link JmsMessagingOperations} against an in-process ActiveMQ Classic
 * broker. No Docker, no external services, no network.
 */
class ActiveMqClassicIntegrationTest {

    private static final EmbeddedActiveMqBroker BROKER = new EmbeddedActiveMqBroker();

    private static JmsMessagingOperations messaging;
    private static ConnectionProfile profile;

    @BeforeAll
    static void startBroker() throws Exception {
        BROKER.start();
        messaging = MessagingTestFixture.operations();
        profile = MessagingTestFixture.profileFor(Provider.ACTIVE_MQ, BROKER.url());
    }

    @AfterAll
    static void stopBroker() throws Exception {
        BROKER.stop();
    }

    private static String uniqueQueue(String prefix) {
        return prefix + "q" + UUID.randomUUID().toString().replace("-", "");
    }

    private static void seed(String queue, int count) {
        for (int i = 1; i <= count; i++) {
            messaging.send(profile, queue, "message-" + i, Map.of("seq", String.valueOf(i)));
        }
    }

    @Test
    @DisplayName("a sent message comes back from a browse with its body and custom properties")
    void sendThenBrowseRoundTrip() {
        String queue = uniqueQueue("plain.");

        String messageId = messaging.send(profile, queue, "hello world",
                Map.of("tenant", "acme", "kind", "greeting"));

        assertThat(messageId).startsWith("ID:");

        BrowseResult result = messaging.browse(profile, queue, 100);

        assertThat(result.returned()).isEqualTo(1);
        QueueMessageView view = result.messages().get(0);
        assertThat(view.messageId()).isEqualTo(messageId);
        assertThat(view.body()).isEqualTo("hello world");
        assertThat(view.enqueueTime()).isNotNull();
        assertThat(view.properties()).containsEntry("tenant", "acme").containsEntry("kind", "greeting");
        assertThat(view.headers()).containsKey("JMSMessageID").containsKey("JMSTimestamp");
    }

    @Test
    @DisplayName("browsing an empty queue returns nothing rather than failing")
    void browseEmptyQueue() {
        BrowseResult result = messaging.browse(profile, uniqueQueue("empty."), 100);

        assertThat(result.returned()).isZero();
        assertThat(result.messages()).isEmpty();
    }

    @Test
    @DisplayName("an empty queue reports depth 0 as exact — never '0+' with a truncation warning")
    void emptyDepthIsExactEvenOnACappedProvider() {
        // A browse cap can cut a long enumeration short, but it cannot turn a queue holding messages
        // into an empty one, so zero is trustworthy on every provider.
        DepthOutcome depth = messaging.depthDetailed(profile, uniqueQueue("emptydepth."));

        assertThat(depth.count()).isZero();
        assertThat(depth.exact()).isTrue();
        assertThat(depth.note()).isNull();
    }

    @Test
    @DisplayName("the browser stops at the broker's cap, silently — which is why the UI must not "
            + "present a browse count as a total")
    void browseIsSilentlyCappedByTheBroker() {
        String queue = uniqueQueue(EmbeddedActiveMqBroker.TRUNCATING_PREFIX);
        seed(queue, 50);

        // Asking for 100 with 50 on the queue: any shortfall here is the broker's doing, not ours.
        BrowseResult result = messaging.browse(profile, queue, 100);

        assertThat(result.returned())
                .as("ActiveMQ ends the enumeration at maxBrowsePageSize with no error at all")
                .isEqualTo(EmbeddedActiveMqBroker.BROWSE_CAP);
        assertThat(result.providerNote()).contains("maxBrowsePageSize");
    }

    @Test
    @DisplayName("our own limit truncates and says so")
    void browseRespectsRequestedLimit() {
        String queue = uniqueQueue("limit.");
        seed(queue, 10);

        BrowseResult result = messaging.browse(profile, queue, 4);

        assertThat(result.returned()).isEqualTo(4);
        assertThat(result.limit()).isEqualTo(4);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    @DisplayName("depth counts the queue and reports itself inexact on a capped provider")
    void depthCountsMessages() {
        String queue = uniqueQueue("depth.");
        seed(queue, 5);

        DepthOutcome depth = messaging.depthDetailed(profile, queue);

        assertThat(depth.count()).isEqualTo(5);
        assertThat(depth.exact())
                .as("ActiveMQ cannot promise a browse-derived depth is the whole truth")
                .isFalse();
    }

    @Test
    @DisplayName("deleting a message near the front succeeds and drops the depth by one")
    void deleteShallowMessage() {
        String queue = uniqueQueue(EmbeddedActiveMqBroker.DEEP_PREFIX);
        seed(queue, 5);
        String targetId = messaging.browse(profile, queue, 5).messages().get(2).messageId();

        DeleteOutcome outcome = messaging.deleteMessageDetailed(profile, queue, targetId);

        assertThat(outcome).isEqualTo(DeleteOutcome.DELETED);
        assertThat(messaging.depthDetailed(profile, queue).count()).isEqualTo(4);
        assertThat(messaging.browse(profile, queue, 10).messages())
                .extracting(QueueMessageView::messageId)
                .doesNotContain(targetId);
    }

    @Test
    @DisplayName("a message well past maxPageSize is still deleted on this broker — the selector-stall "
            + "guard is defensive, not a routine path")
    void deleteDeepMessageSucceedsHere() {
        String queue = uniqueQueue(EmbeddedActiveMqBroker.DEEP_PREFIX);
        seed(queue, 40);

        // #30 is well past this destination's maxPageSize of 10. ActiveMQ's documented selector-paging
        // stall does NOT reproduce at this scale: with a small non-persistent queue the broker has
        // everything in memory and the selector matches. Attempts to force it with a queue memory limit
        // only trigger producer flow control instead, so the stall needs a real deployment's cursor
        // pressure. The MESSAGE_UNREACHABLE branch is therefore covered by JmsDeleteDisambiguationTest,
        // which drives the exact consumer/browser combination deterministically with mocks.
        String deepId = messaging.browse(profile, queue, 40).messages().get(30).messageId();

        DeleteOutcome outcome = messaging.deleteMessageDetailed(profile, queue, deepId);

        assertThat(outcome).isEqualTo(DeleteOutcome.DELETED);
        assertThat(messaging.depthDetailed(profile, queue).count()).isEqualTo(39);
    }

    @Test
    @DisplayName("a well-formed id that is not on the queue is reported as not found, not as an error")
    void deleteMissingMessageReportsNotFound() {
        String queue = uniqueQueue("missing.");
        seed(queue, 3);

        DeleteOutcome outcome = messaging.deleteMessageDetailed(profile, queue,
                "ID:does-not-exist-1:1:1:1");

        assertThat(outcome).isEqualTo(DeleteOutcome.NOT_FOUND);
        assertThat(messaging.depthDetailed(profile, queue).count()).isEqualTo(3);
    }

    @Test
    @DisplayName("the IBM MQ all-zeros wildcard id is refused before any consumer is opened")
    void rejectsWildcardMessageId() {
        String queue = uniqueQueue("wildcard.");
        seed(queue, 3);

        assertThatThrownBy(() -> messaging.deleteMessageDetailed(profile, queue,
                "ID:000000000000000000000000000000000000000000000000"))
                .isInstanceOf(MqOperationException.class)
                .satisfies(thrown -> assertThat(((MqOperationException) thrown).getCode())
                        .isEqualTo("MESSAGE_ID_INVALID"));

        assertThat(messaging.depthDetailed(profile, queue).count())
                .as("the wildcard must never consume an arbitrary message")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("purge drains the queue and reports why it stopped")
    void purgeEmptiesTheQueue() {
        String queue = uniqueQueue("purge.");
        seed(queue, 50);

        PurgeOutcome outcome = messaging.purgeDetailed(profile, queue);

        assertThat(outcome.purged()).isEqualTo(50);
        assertThat(outcome.stopReason()).isEqualTo(PurgeOutcome.StopReason.QUEUE_EMPTY);
        assertThat(outcome.complete()).isTrue();
        assertThat(messaging.depthDetailed(profile, queue).count()).isZero();
    }

    @Test
    @DisplayName("a purge stopped by the message cap says so, so the UI cannot claim the queue is empty")
    void purgeStopsAtMessageCapAndSaysSo() {
        MqManagerProperties capped = new MqManagerProperties();
        capped.getPurge().setMaxMessages(5);
        JmsMessagingOperations cappedMessaging = MessagingTestFixture.operations(capped);

        String queue = uniqueQueue("cap.");
        seed(queue, 20);

        PurgeOutcome outcome = cappedMessaging.purgeDetailed(profile, queue);

        assertThat(outcome.purged()).isEqualTo(5);
        assertThat(outcome.stopReason()).isEqualTo(PurgeOutcome.StopReason.MESSAGE_CAP);
        assertThat(outcome.complete()).isFalse();
        assertThat(messaging.depthDetailed(profile, queue).count())
                .as("the messages the cap left behind are still on the queue, not lost")
                .isEqualTo(15);
    }

    @Test
    @DisplayName("browseOne returns the full body for the expanded row")
    void browseOneReturnsFullBodyWithoutConsuming() {
        String queue = uniqueQueue("one.");
        String body = "x".repeat(9_000); // larger than the 4 KB list preview
        String messageId = messaging.send(profile, queue, body, Map.of());

        QueueMessageView preview = messaging.browse(profile, queue, 10).messages().get(0);
        assertThat(preview.bodyTruncated()).isTrue();
        assertThat(preview.body()).hasSize(new MqManagerProperties().getBrowse().getPreviewBytes());

        QueueMessageView full = messaging.browseOne(profile, queue, messageId);

        assertThat(full.body()).isEqualTo(body);
        assertThat(full.bodyTruncated()).isFalse();
        assertThat(messaging.depthDetailed(profile, queue).count())
                .as("reading one message must not consume it")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("testConnection succeeds against a live broker and fails cleanly against a dead port")
    void testConnectionReportsBothOutcomes() {
        assertThat(messaging.testConnection(profile).success()).isTrue();

        ConnectionProfile wrongPort =
                MessagingTestFixture.profileFor(Provider.ACTIVE_MQ, "tcp://127.0.0.1:1?connectionTimeout=2000");

        var failure = messaging.testConnection(wrongPort);

        assertThat(failure.success()).isFalse();
        assertThat(failure.code()).isEqualTo("BROKER_CONNECTION_REFUSED");
        assertThat(failure.message()).doesNotContain("Exception");
    }

    @Test
    @DisplayName("sending to a queue that does not exist auto-creates it on ActiveMQ")
    void sendAutoCreatesDestination() {
        String queue = uniqueQueue("autocreate.");

        messaging.send(profile, queue, "first", Map.of());

        assertThat(messaging.depthDetailed(profile, queue).count()).isEqualTo(1);
        assertThat(List.of(Provider.ACTIVE_MQ.autoCreatesDestinations())).containsExactly(true);
    }
}
