package com.baysansoft.mqmanager.support;

import java.util.List;
import java.util.UUID;

import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.TransportConnector;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;

/**
 * An in-process ActiveMQ Classic broker for integration tests. No external service, no Docker, no disk.
 *
 * <p>The destination policy is the load-bearing part. A default {@code BrokerService} has
 * {@code destinationPolicy == null}, which leaves browsing <em>uncapped</em> and means the paging
 * behaviour that breaks delete-by-id in production never reproduces — a truncation test would pass here
 * while real deployments silently truncate.
 *
 * <p>Two policies, not one, because a single cap cannot serve both tests: the truncation assertion needs
 * a small browse cap, while the "message is unreachable" assertion needs a small <em>page</em> cap but a
 * browse cap large enough for the disambiguation scan to still find the message.
 */
public class EmbeddedActiveMqBroker {

    /** Queue prefix whose browser stops after {@link #BROWSE_CAP} messages. */
    public static final String TRUNCATING_PREFIX = "trunc.";

    /** Queue prefix where a selector consumer stalls past {@link #PAGE_CAP} but browsing sees plenty. */
    public static final String DEEP_PREFIX = "deep.";

    public static final int BROWSE_CAP = 20;
    public static final int PAGE_CAP = 10;

    private BrokerService broker;
    private String url;

    /** Advisories off, which is what every message-level test wants. */
    public void start() throws Exception {
        start(false);
    }

    /**
     * @param advisorySupport true to let the broker publish {@code ActiveMQ.Advisory.*}. Off for every
     *                        message-level test, because the advisory destinations would otherwise show
     *                        up in their listings — and off is also the exact shape of the locked-down
     *                        broker that {@code ActiveMqDestinationListerTest} needs to prove the
     *                        "cannot tell you" path, so it is reproduced here for free
     */
    public void start(boolean advisorySupport) throws Exception {
        broker = new BrokerService();
        broker.setBrokerName("mqm-test-" + UUID.randomUUID());
        broker.setPersistent(false);        // MemoryPersistenceAdapter; no KahaDB, no files on disk
        broker.setUseJmx(false);            // defaults to true and clashes across parallel test classes
        broker.setAdvisorySupport(advisorySupport);
        broker.setSchedulerSupport(false);
        broker.setUseShutdownHook(false);
        broker.setDeleteAllMessagesOnStartup(true);

        PolicyEntry truncating = new PolicyEntry();
        truncating.setQueue(TRUNCATING_PREFIX + ">");
        truncating.setMaxBrowsePageSize(BROWSE_CAP);

        PolicyEntry deep = new PolicyEntry();
        deep.setQueue(DEEP_PREFIX + ">");
        deep.setMaxPageSize(PAGE_CAP);
        deep.setMaxBrowsePageSize(400);

        PolicyMap policies = new PolicyMap();
        policies.setPolicyEntries(List.of(truncating, deep));
        broker.setDestinationPolicy(policies);

        TransportConnector connector = broker.addConnector("tcp://127.0.0.1:0"); // OS-assigned port
        broker.start();
        broker.waitUntilStarted();

        this.url = connector.getConnectUri().toString();
    }

    public void stop() throws Exception {
        if (broker != null) {
            broker.stop();
            broker.waitUntilStopped();
        }
    }

    /** The real listener URL, fed to the production connection factory builder. */
    public String url() {
        return url;
    }
}
