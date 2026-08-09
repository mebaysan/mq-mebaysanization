package com.baysansoft.mqmanager.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.TopicListing;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baysansoft.mqmanager.domain.DestinationKind;
import com.baysansoft.mqmanager.messaging.model.DestinationEntry;
import com.baysansoft.mqmanager.messaging.model.DestinationListing;
import com.baysansoft.mqmanager.messaging.model.DestinationQuery;
import com.baysansoft.mqmanager.support.KafkaTestFixture;
import com.baysansoft.mqmanager.web.MqOperationException;

/**
 * Listing topics over a mocked {@code Admin}.
 *
 * <p>The assertion that matters most is the split: a cluster that <em>refuses</em> is a listing that
 * says so, while a cluster that cannot be <em>reached</em> is still an error. Blurring the two would
 * either hide a broken connection or make broker policy look like a bug in this tool.
 */
class KafkaListDestinationsTest {

    private static ListTopicsResult resultOf(TopicListing... listings) {
        ListTopicsResult result = mock(ListTopicsResult.class);
        Map<String, TopicListing> byName = new LinkedHashMap<>();
        for (TopicListing listing : listings) {
            byName.put(listing.name(), listing);
        }
        when(result.namesToListings()).thenReturn(KafkaFuture.completedFuture(byName));
        return result;
    }

    /**
     * A result whose future fails with {@code cause} wrapped the way {@code KafkaFuture.get} wraps it.
     *
     * <p>The future is mocked rather than built: {@code KafkaFutureImpl.completeExceptionally} is not
     * public API, and what this test actually cares about is how the production code unwraps an
     * {@link ExecutionException}.
     */
    private static ListTopicsResult failingWith(Throwable cause) {
        ListTopicsResult result = mock(ListTopicsResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Map<String, TopicListing>> throwing = mock(KafkaFuture.class);
        try {
            when(throwing.get(anyLong(), any())).thenThrow(new ExecutionException(cause));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(result.namesToListings()).thenReturn(throwing);
        return result;
    }

    private static TopicListing topic(String name, boolean internal) {
        return new TopicListing(name, Uuid.randomUuid(), internal);
    }

    private static DestinationListing list(Admin admin, DestinationQuery query) {
        return KafkaTestFixture.operations(KafkaTestFixture.producer(), KafkaTestFixture.consumer(),
                        admin)
                .listDestinations(KafkaTestFixture.profile(), query);
    }

    @Test
    @DisplayName("every topic is a topic, and internal is the broker's answer rather than a name guess")
    void listsTopicsAndTakesInternalFromTheBroker() {
        Admin admin = mock(Admin.class);
        ListTopicsResult result = resultOf(topic("orders", false), topic("__consumer_offsets", true));
        when(admin.listTopics(any())).thenReturn(result);

        DestinationListing listing = list(admin, DestinationQuery.all());

        assertThat(listing.availability()).isEqualTo(DestinationListing.Availability.COMPLETE);
        assertThat(listing.destinations())
                .extracting(DestinationEntry::kind)
                .containsOnly(DestinationKind.TOPIC);
        assertThat(listing.destinations())
                .filteredOn(entry -> entry.name().equals("__consumer_offsets"))
                .allSatisfy(entry -> assertThat(entry.internal()).isTrue());
        assertThat(listing.destinations())
                .filteredOn(entry -> entry.name().equals("orders"))
                .allSatisfy(entry -> assertThat(entry.internal()).isFalse());
    }

    @Test
    @DisplayName("the admin client is always closed with an explicit timeout")
    void closesTheAdminClient() {
        Admin admin = mock(Admin.class);
        ListTopicsResult result = resultOf(topic("orders", false));
        when(admin.listTopics(any())).thenReturn(result);

        list(admin, DestinationQuery.all());

        verify(admin).close(any(Duration.class));
    }

    @Test
    @DisplayName("a cluster that refuses to be described says so, and does not report zero topics")
    void authorizationFailureIsUnavailable() {
        Admin admin = mock(Admin.class);
        ListTopicsResult result = failingWith(new ClusterAuthorizationException("no Describe"));
        when(admin.listTopics(any())).thenReturn(result);

        DestinationListing listing = list(admin, DestinationQuery.all());

        assertThat(listing.availability()).isEqualTo(DestinationListing.Availability.UNAVAILABLE);
        assertThat(listing.reason()).isEqualTo(DestinationListing.NOT_PERMITTED);
        assertThat(listing.destinations()).isEmpty();
    }

    @Test
    @DisplayName("a cluster that does not answer in time is unavailable, not an error")
    void timeoutIsUnavailable() {
        Admin admin = mock(Admin.class);
        ListTopicsResult result =
                failingWith(new org.apache.kafka.common.errors.TimeoutException("took too long"));
        when(admin.listTopics(any())).thenReturn(result);

        DestinationListing listing = list(admin, DestinationQuery.all());

        assertThat(listing.availability()).isEqualTo(DestinationListing.Availability.UNAVAILABLE);
        assertThat(listing.reason()).isEqualTo(DestinationListing.TIMED_OUT);
    }

    @Test
    @DisplayName("a cluster that cannot be reached is still an error, not a polite empty list")
    void connectionFailureStillThrows() {
        Admin admin = mock(Admin.class);
        ListTopicsResult result = failingWith(new ConnectException("connection refused"));
        when(admin.listTopics(any())).thenReturn(result);

        // The other half of the contract. Degrading this would hide a broken connection behind a
        // message about broker policy.
        assertThatThrownBy(() -> list(admin, DestinationQuery.all()))
                .isInstanceOf(MqOperationException.class);
    }

    @Test
    @DisplayName("a prefix and our own cap are applied after the broker answers, in name order")
    void prefixAndCapAreApplied() {
        Admin admin = mock(Admin.class);
        ListTopicsResult result = resultOf(
                topic("orders.new", false), topic("orders.dispatched", false), topic("audit", false));
        when(admin.listTopics(any())).thenReturn(result);

        assertThat(list(admin, new DestinationQuery(null, "orders.", 0)).destinations())
                .extracting(DestinationEntry::name)
                .containsExactly("orders.dispatched", "orders.new");

        DestinationListing capped = list(admin, new DestinationQuery(null, null, 2));
        assertThat(capped.truncated()).isTrue();
        assertThat(capped.reason()).isEqualTo(DestinationListing.CAPPED);
        // Sorted before capping, so "the first N by name" is deterministic across calls.
        assertThat(capped.destinations()).extracting(DestinationEntry::name)
                .containsExactly("audit", "orders.dispatched");
    }
}
