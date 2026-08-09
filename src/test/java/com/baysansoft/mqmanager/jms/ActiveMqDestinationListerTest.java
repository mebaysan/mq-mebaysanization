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
import com.baysansoft.mqmanager.support.EmbeddedActiveMqBroker;
import com.baysansoft.mqmanager.support.MessagingTestFixture;
import com.baysansoft.mqmanager.support.QueueSeeder;

/**
 * Destination listing against two real ActiveMQ Classic brokers: one with advisories on, one with them
 * off.
 *
 * <p>The second is the point. A broker running {@code advisorySupport=false} is exactly what a hardened
 * deployment looks like, and the thing that must never happen is reporting it as "this broker has no
 * queues". The test fixture already ran that way for every other test, so the locked-down case costs
 * nothing to cover.
 */
class ActiveMqDestinationListerTest {

    private static final EmbeddedActiveMqBroker WITH_ADVISORIES = new EmbeddedActiveMqBroker();
    private static final EmbeddedActiveMqBroker WITHOUT_ADVISORIES = new EmbeddedActiveMqBroker();

    @BeforeAll
    static void startBrokers() throws Exception {
        WITH_ADVISORIES.start(true);
        WITHOUT_ADVISORIES.start(false);

        // Advisories are retroactive: destinations that existed before the source subscribed are
        // replayed to it, so seeding once here is enough.
        QueueSeeder.seed(WITH_ADVISORIES.url(), "orders.new", 1, "body-");
        QueueSeeder.seed(WITH_ADVISORIES.url(), "orders.dispatched", 1, "body-");
        QueueSeeder.seed(WITH_ADVISORIES.url(), "audit.trail", 1, "body-");
        QueueSeeder.seed(WITHOUT_ADVISORIES.url(), "orders.new", 1, "body-");
    }

    @AfterAll
    static void stopBrokers() throws Exception {
        WITH_ADVISORIES.stop();
        WITHOUT_ADVISORIES.stop();
    }

    private static MqManagerProperties properties() {
        MqManagerProperties properties = new MqManagerProperties();
        // Short, so the suite does not spend a second and a half per listing waiting for quiescence.
        properties.getDestinations().setAdvisorySettle(Duration.ofMillis(600));
        properties.getDestinations().setAdvisoryQuietPeriod(Duration.ofMillis(100));
        return properties;
    }

    private static DestinationListing list(EmbeddedActiveMqBroker broker, DestinationQuery query) {
        MqManagerProperties properties = properties();
        ConnectionProfile profile =
                MessagingTestFixture.profileFor(Provider.ACTIVE_MQ, broker.url());
        return MessagingTestFixture.operations(properties).listDestinations(profile, query);
    }

    @Test
    @DisplayName("with advisories on, the seeded queues come back complete and in name order")
    void listsSeededQueues() {
        DestinationListing listing = list(WITH_ADVISORIES, DestinationQuery.all());

        assertThat(listing.availability()).isEqualTo(DestinationListing.Availability.COMPLETE);
        assertThat(listing.reason()).isNull();
        assertThat(listing.destinations())
                .extracting(DestinationEntry::name)
                .contains("audit.trail", "orders.dispatched", "orders.new");
        assertThat(listing.destinations())
                .filteredOn(entry -> entry.name().startsWith("orders."))
                .allSatisfy(entry -> assertThat(entry.kind()).isEqualTo(DestinationKind.QUEUE));
        // Sorted before capping, so the answer is stable across calls.
        assertThat(listing.destinations()).extracting(DestinationEntry::name).isSorted();
    }

    @Test
    @DisplayName("the broker's own advisory topics never reach the listing, so nothing shows the plumbing")
    void advisoryTopicsDoNotAppear() {
        DestinationListing listing = list(WITH_ADVISORIES, DestinationQuery.all());

        // ActiveMQ does not publish advisories ABOUT advisory destinations, so turning advisories on
        // does not fill the picker with ActiveMQ.Advisory.* rows. The lister's internal-name guard is
        // therefore belt and braces rather than the thing keeping them out.
        assertThat(listing.destinations())
                .extracting(DestinationEntry::name)
                .noneMatch(name -> name.startsWith("ActiveMQ.Advisory."));
        assertThat(listing.destinations())
                .filteredOn(entry -> entry.name().equals("orders.new"))
                .allSatisfy(entry -> assertThat(entry.internal()).isFalse());
    }

    @Test
    @DisplayName("a prefix narrows the answer, and it is a prefix rather than a substring")
    void prefixIsAPrefix() {
        DestinationListing listing =
                list(WITH_ADVISORIES, new DestinationQuery(null, "orders.", 0));

        assertThat(listing.destinations()).extracting(DestinationEntry::name)
                .containsExactly("orders.dispatched", "orders.new");

        // "rders" is a substring of every one of those and must match nothing.
        assertThat(list(WITH_ADVISORIES, new DestinationQuery(null, "rders", 0)).destinations())
                .isEmpty();
    }

    @Test
    @DisplayName("our own cap reports itself as partial and truncated, never as the whole answer")
    void ourCapIsReportedHonestly() {
        DestinationListing listing = list(WITH_ADVISORIES, new DestinationQuery(null, "orders.", 1));

        assertThat(listing.destinations()).hasSize(1);
        assertThat(listing.truncated()).isTrue();
        assertThat(listing.availability()).isEqualTo(DestinationListing.Availability.PARTIAL);
        assertThat(listing.reason()).isEqualTo(DestinationListing.CAPPED);
    }

    @Test
    @DisplayName("with advisories off it says it cannot tell, rather than claiming the broker is empty")
    void advisoriesOffIsUnavailableNotEmpty() {
        DestinationListing listing = list(WITHOUT_ADVISORIES, DestinationQuery.all());

        // The queue demonstrably exists — it was seeded above — so an empty COMPLETE here would be a lie.
        assertThat(listing.availability()).isEqualTo(DestinationListing.Availability.UNAVAILABLE);
        assertThat(listing.reason()).isEqualTo(DestinationListing.INDISTINGUISHABLE);
        assertThat(listing.destinations()).isEmpty();
        assertThat(listing.note()).contains("advisorySupport=false");
    }
}
