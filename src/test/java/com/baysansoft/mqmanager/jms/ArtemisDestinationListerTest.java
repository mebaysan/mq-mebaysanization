package com.baysansoft.mqmanager.jms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.DestinationKind;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.messaging.model.DestinationEntry;
import com.baysansoft.mqmanager.messaging.model.DestinationListing;
import com.baysansoft.mqmanager.messaging.model.DestinationQuery;
import com.baysansoft.mqmanager.support.EmbeddedArtemisBroker;
import com.baysansoft.mqmanager.support.MessagingTestFixture;

/**
 * Destination listing against a real embedded Artemis broker.
 *
 * <p>The refusal case here is a management address that does not answer, which is what a broker with
 * management disabled looks like from a client. A permission refusal would need a full Artemis security
 * manager configured in the embedded broker — that is a documented gap, not an oversight.
 */
class ArtemisDestinationListerTest {

    private static final EmbeddedArtemisBroker BROKER = new EmbeddedArtemisBroker();

    @BeforeAll
    static void startBroker() throws Exception {
        BROKER.start();
        // Sending creates the address and the queue; the embedded broker has auto-delete turned off.
        MessagingTestFixture.operations().send(profile(), "orders.new", "body", null);
        MessagingTestFixture.operations().send(profile(), "audit.trail", "body", null);
    }

    @AfterAll
    static void stopBroker() throws Exception {
        BROKER.stop();
    }

    private static ConnectionProfile profile() {
        return MessagingTestFixture.profileFor(Provider.ARTEMIS, BROKER.url());
    }

    private static DestinationListing list(MqManagerProperties properties, DestinationQuery query) {
        return MessagingTestFixture.operations(properties).listDestinations(profile(), query);
    }

    @Test
    @DisplayName("the seeded queues come back complete, sorted, and marked as queues")
    void listsQueues() {
        DestinationListing listing = list(new MqManagerProperties(), DestinationQuery.all());

        assertThat(listing.availability()).isEqualTo(DestinationListing.Availability.COMPLETE);
        assertThat(listing.destinations())
                .extracting(DestinationEntry::name)
                .contains("audit.trail", "orders.new")
                .isSorted();
        assertThat(listing.destinations())
                .filteredOn(entry -> entry.name().equals("orders.new"))
                .allSatisfy(entry -> assertThat(entry.kind()).isEqualTo(DestinationKind.QUEUE));
    }

    @Test
    @DisplayName("the broker's own notification address is marked internal rather than shown as a topic")
    void brokerOwnedAddressesAreMarkedInternal() {
        DestinationListing listing = list(new MqManagerProperties(), DestinationQuery.all());

        assertThat(listing.destinations())
                .filteredOn(entry -> entry.name().equals("activemq.notifications"))
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.internal()).isTrue());
        assertThat(listing.destinations())
                .filteredOn(entry -> entry.name().equals("orders.new"))
                .allSatisfy(entry -> assertThat(entry.internal()).isFalse());
    }

    @Test
    @DisplayName("the reply queue this listing creates for itself never appears in its own answer")
    void ownReplyQueueIsNotListed() {
        DestinationListing listing = list(new MqManagerProperties(), DestinationQuery.all());

        // A management request needs somewhere to be answered, and that temporary queue exists on the
        // broker while the listing runs. Without filtering it out, the picker shows a UUID that is gone
        // by the time anyone clicks it.
        assertThat(listing.destinations())
                .extracting(DestinationEntry::name)
                .noneMatch(name -> name.matches("[0-9a-f]{8}-[0-9a-f]{4}-.*"));
    }

    @Test
    @DisplayName("filtering to topics returns addresses that have no queue of their own name")
    void topicsAreAddressesWithoutAMatchingQueue() {
        DestinationListing listing =
                list(new MqManagerProperties(), new DestinationQuery(DestinationKind.TOPIC, null, 0));

        // Whatever is here, none of it may be a name already reported as a queue — that is the whole
        // rule for mapping Artemis's address/queue model onto queues and topics.
        assertThat(listing.destinations())
                .allSatisfy(entry -> assertThat(entry.kind()).isEqualTo(DestinationKind.TOPIC));
        assertThat(listing.destinations())
                .extracting(DestinationEntry::name)
                .doesNotContain("orders.new", "audit.trail");
    }

    @Test
    @DisplayName("a management address that never answers says so, rather than reporting an empty broker")
    void unreachableManagementAddressIsUnavailable() {
        MqManagerProperties properties = new MqManagerProperties();
        properties.getDestinations().setManagementAddress("nothing.is.listening.here");
        properties.getDestinations().setTimeout(Duration.ofMillis(300));

        DestinationListing listing = list(properties, DestinationQuery.all());

        // The queues demonstrably exist, so an empty COMPLETE would be a lie.
        assertThat(listing.availability()).isEqualTo(DestinationListing.Availability.UNAVAILABLE);
        assertThat(listing.reason()).isEqualTo(DestinationListing.TIMED_OUT);
        assertThat(listing.destinations()).isEmpty();
        assertThat(listing.note()).contains("nothing.is.listening.here");
    }
}
