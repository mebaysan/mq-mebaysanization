package com.baysansoft.mqmanager.jms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.messaging.model.BrowseResult;
import com.baysansoft.mqmanager.messaging.model.DeleteOutcome;
import com.baysansoft.mqmanager.messaging.model.DepthOutcome;
import com.baysansoft.mqmanager.messaging.model.PurgeOutcome;
import com.baysansoft.mqmanager.messaging.model.QueueMessageView;
import com.baysansoft.mqmanager.support.EmbeddedArtemisBroker;
import com.baysansoft.mqmanager.support.MessagingTestFixture;

/**
 * The same operations as the ActiveMQ Classic test, against an in-process Artemis broker.
 *
 * <p>Running both is the point: Artemis browses the whole queue and its selectors reach any depth, so
 * the results deliberately differ from Classic's. That asymmetry is what the UI has to communicate.
 */
class ArtemisIntegrationTest {

    private static final EmbeddedArtemisBroker BROKER = new EmbeddedArtemisBroker();

    private static JmsMessagingOperations messaging;
    private static ConnectionProfile profile;

    @BeforeAll
    static void startBroker() throws Exception {
        BROKER.start();
        messaging = MessagingTestFixture.operations();
        profile = MessagingTestFixture.profileFor(Provider.ARTEMIS, BROKER.url());
    }

    @AfterAll
    static void stopBroker() throws Exception {
        BROKER.stop();
    }

    private static String uniqueQueue() {
        return "artemis" + UUID.randomUUID().toString().replace("-", "");
    }

    private static void seed(String queue, int count) {
        for (int i = 1; i <= count; i++) {
            messaging.send(profile, queue, "message-" + i, Map.of("seq", String.valueOf(i)));
        }
    }

    @Test
    void connectsSuccessfully() {
        var result = messaging.testConnection(profile);

        assertThat(result.success()).isTrue();
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("send and browse round-trip, including custom properties")
    void sendThenBrowse() {
        String queue = uniqueQueue();

        String messageId = messaging.send(profile, queue, "artemis payload", Map.of("tenant", "acme"));

        BrowseResult result = messaging.browse(profile, queue, 100);

        assertThat(result.returned()).isEqualTo(1);
        QueueMessageView view = result.messages().get(0);
        assertThat(view.messageId()).isEqualTo(messageId);
        assertThat(view.body()).isEqualTo("artemis payload");
        assertThat(view.properties()).containsEntry("tenant", "acme");
    }

    @Test
    @DisplayName("unlike ActiveMQ Classic, a browse of 50 returns all 50 — no broker-side cap")
    void browseIsNotCapped() {
        String queue = uniqueQueue();
        seed(queue, 50);

        BrowseResult result = messaging.browse(profile, queue, 100);

        assertThat(result.returned()).isEqualTo(50);
        assertThat(result.truncated()).isFalse();
        assertThat(result.providerNote()).contains("complete");
    }

    @Test
    @DisplayName("depth is exact on Artemis, so the UI shows a number rather than 'N+'")
    void depthIsExact() {
        String queue = uniqueQueue();
        seed(queue, 12);

        DepthOutcome depth = messaging.depthDetailed(profile, queue);

        assertThat(depth.count()).isEqualTo(12);
        assertThat(depth.exact()).isTrue();
        assertThat(depth.note()).isNull();
    }

    @Test
    @DisplayName("a deep message is deleted by id, where ActiveMQ Classic may not reach it")
    void deletesDeepMessage() {
        String queue = uniqueQueue();
        seed(queue, 40);
        String deepId = messaging.browse(profile, queue, 40).messages().get(30).messageId();

        DeleteOutcome outcome = messaging.deleteMessageDetailed(profile, queue, deepId);

        assertThat(outcome).isEqualTo(DeleteOutcome.DELETED);
        assertThat(messaging.depthDetailed(profile, queue).count()).isEqualTo(39);
        assertThat(messaging.browse(profile, queue, 40).messages())
                .extracting(QueueMessageView::messageId)
                .doesNotContain(deepId);
    }

    @Test
    void reportsNotFoundForAnAbsentMessage() {
        String queue = uniqueQueue();
        seed(queue, 3);

        DeleteOutcome outcome =
                messaging.deleteMessageDetailed(profile, queue, "ID:11111111-2222-3333-4444-555555555555");

        assertThat(outcome).isEqualTo(DeleteOutcome.NOT_FOUND);
        assertThat(messaging.depthDetailed(profile, queue).count()).isEqualTo(3);
    }

    @Test
    void purgeEmptiesTheQueue() {
        String queue = uniqueQueue();
        seed(queue, 50);

        PurgeOutcome outcome = messaging.purgeDetailed(profile, queue);

        assertThat(outcome.purged()).isEqualTo(50);
        assertThat(outcome.stopReason()).isEqualTo(PurgeOutcome.StopReason.QUEUE_EMPTY);
        assertThat(messaging.depthDetailed(profile, queue).count()).isZero();
    }

    @Test
    @DisplayName("browseOne returns the untruncated body without consuming the message")
    void browseOneReturnsFullBody() {
        String queue = uniqueQueue();
        String body = "y".repeat(9_000);
        String messageId = messaging.send(profile, queue, body, Map.of());

        QueueMessageView full = messaging.browseOne(profile, queue, messageId);

        assertThat(full.body()).isEqualTo(body);
        assertThat(full.bodyTruncated()).isFalse();
        assertThat(messaging.depthDetailed(profile, queue).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a dead port fails with a translated message, not a stack trace")
    void failsCleanlyAgainstADeadPort() {
        ConnectionProfile unreachable = MessagingTestFixture.profileFor(
                Provider.ARTEMIS, "tcp://127.0.0.1:1?connect-timeout-millis=2000");

        var result = messaging.testConnection(unreachable);

        assertThat(result.success()).isFalse();
        assertThat(result.code())
                .as("Artemis must classify an unreachable broker the same way the others do, rather "
                        + "than falling through to the generic code")
                .isEqualTo("BROKER_CONNECTION_REFUSED");
        assertThat(result.message()).doesNotContain("Exception").doesNotContain("\tat ");
    }
}
