package com.baysansoft.mqmanager.support;

import java.net.ServerSocket;

import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;

/**
 * An in-process Artemis broker for integration tests.
 *
 * <p>{@code setPersistenceEnabled(false)} is the switch that matters: it keeps the journal, the paging
 * directory and — crucially — the AIO native library entirely out of the picture, so the test needs
 * nothing platform-specific. {@code JournalType.NIO} is belt and braces for the same reason.
 *
 * <p>Auto-delete is turned off explicitly. Artemis defaults {@code auto-delete-queues} and
 * {@code auto-delete-addresses} to true with no delay, so a queue created by sending vanishes again the
 * moment it is idle and empty — which makes any send/reconnect/browse sequence flake.
 */
public class EmbeddedArtemisBroker {

    private EmbeddedActiveMQ server;
    private String url;

    public void start() throws Exception {
        int port = freePort();

        Configuration configuration = new ConfigurationImpl()
                .setPersistenceEnabled(false)
                .setJournalType(JournalType.NIO)
                .setSecurityEnabled(false)
                .setJMXManagementEnabled(false)
                .setJournalDirectory("target/artemis/journal")
                .setBindingsDirectory("target/artemis/bindings")
                .setLargeMessagesDirectory("target/artemis/large")
                .setPagingDirectory("target/artemis/paging");

        configuration.addAcceptorConfiguration("netty", "tcp://127.0.0.1:" + port);
        configuration.addAddressSetting("#", new AddressSettings()
                .setAutoDeleteQueues(false)
                .setAutoDeleteAddresses(false));

        server = new EmbeddedActiveMQ();
        // A non-null configuration means broker.xml is never consulted.
        server.setConfiguration(configuration);
        server.start();

        this.url = "tcp://127.0.0.1:" + port;
    }

    public void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    public String url() {
        return url;
    }

    /** Artemis acceptors want a concrete port, so one is reserved and released first. */
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
